package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.PatientMatcher
import com.chiarasystems.chiara.domain.UuidV7
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.PatientDraft
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import com.chiarasystems.chiara.network.CachePolicy
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.rpcParams
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class DuplicateDocumentException : Exception("Ya existe un paciente con ese DNI.")

/** Port of iOS `PatientsStore`. [scope] runs the debounced search. */
class PatientsStore(private val client: SupabaseClient, private val scope: CoroutineScope) {
    private val _patients = MutableStateFlow<List<Patient>>(emptyList())
    val patients: StateFlow<List<Patient>> = _patients.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Patient>>(emptyList())
    val searchResults: StateFlow<List<Patient>> = _searchResults.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _loadError = MutableStateFlow<Throwable?>(null)
    val loadError: StateFlow<Throwable?> = _loadError.asStateFlow()

    private var searchJob: Job? = null

    fun reset() {
        _patients.value = emptyList()
        _searchResults.value = emptyList()
        _loadError.value = null
        _isSearching.value = false
        searchJob?.cancel()
    }

    fun patient(id: String): Patient? = _patients.value.firstOrNull { it.id == id }

    @Serializable
    private data class Page(val items: List<Patient> = emptyList(), val total: Int = 0)

    private suspend fun fetch(search: String?, page: Int, cache: CachePolicy): Page {
        val text = client.rpc(
            "list_patients",
            rpcParams("p_search" to search?.trimmed?.nilIfEmpty, "p_page" to page, "p_page_size" to PAGE_SIZE),
            cache,
        )
        return client.decode<Page>(text)
    }

    /** Loads every page of the clinic's patients (the server orders by last name). */
    suspend fun load() {
        _isLoading.value = true
        try {
            val all = mutableListOf<Patient>()
            var page = 1
            while (true) {
                val result = fetch(null, page, CachePolicy.FallbackToDisk)
                all += result.items
                if (all.size >= result.total || result.items.isEmpty()) break
                page++
            }
            _patients.value = all
            _loadError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _loadError.value = e
        } finally {
            _isLoading.value = false
        }
    }

    /** Server-side search (name, DNI, phone), debounced 300 ms; offline it filters what is already loaded. */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trimmed
        if (q.isEmpty()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            try {
                _searchResults.value = fetch(q, 1, CachePolicy.None).items
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline: filter what is already loaded (name + document, accent- and case-insensitive).
                val needle = PatientMatcher.fold(q)
                _searchResults.value = _patients.value.filter {
                    PatientMatcher.fold(it.fullName + " " + (it.documentId ?: "")).contains(needle)
                }
            }
            _isSearching.value = false
        }
    }

    suspend fun fetchOne(id: String): Patient? {
        patient(id)?.let { return it }
        return try {
            client.decode<Patient?>(client.rpc("get_patient", rpcParams("p_id" to id), CachePolicy.FallbackToDisk))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** True when another patient already has this document id. */
    suspend fun documentExists(documentId: String): Boolean {
        val dni = documentId.trimmed
        if (dni.isEmpty()) return false
        if (_patients.value.any { it.documentId.equals(dni, ignoreCase = true) }) return true
        return try {
            fetch(dni, 1, CachePolicy.None).items.any { it.documentId.equals(dni, ignoreCase = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Changes some fields of a patient. The server keeps what is not in the patch (`coalesce`). */
    suspend fun update(id: String, patch: Map<String, String>): Patient {
        val updated = client.decode<Patient>(client.rpc("update_patient", rpcParams("p_id" to id, "p_patch" to patch)))
        _patients.update { list -> list.map { if (it.id == id) updated else it } }
        return updated
    }

    /** The id is generated here (UUIDv7) so a retry after a dropped connection cannot duplicate the row. */
    suspend fun create(draft: PatientDraft, id: String? = null): Patient {
        val newId = id ?: UuidV7.generate().toString()
        if (documentExists(draft.documentId)) throw DuplicateDocumentException()
        val created = client.decode<Patient>(
            client.rpc(
                "create_patient",
                rpcParams(
                    "p_id" to newId,
                    "p_first_name" to draft.firstName.trimmed,
                    "p_last_name" to draft.lastName.trimmed,
                    "p_document_id" to draft.documentId.trimmed.uppercase().nilIfEmpty,
                    "p_phone" to draft.phone.trimmed.nilIfEmpty,
                    "p_email" to draft.email.trimmed.nilIfEmpty,
                    "p_date_of_birth" to draft.dateOfBirth?.let { Dates.iso(it) },
                    "p_sex" to draft.sex.server,
                    "p_insurance_company" to draft.insuranceCompany.trimmed.nilIfEmpty,
                    "p_referring_clinic" to draft.referringClinic.trimmed.nilIfEmpty,
                ),
            ),
        )
        val collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.SECONDARY }
        _patients.update { (it + created).sortedWith { a, b -> collator.compare(a.lastName.orEmpty(), b.lastName.orEmpty()) } }
        return created
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
