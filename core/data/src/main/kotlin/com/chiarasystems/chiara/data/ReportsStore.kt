package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.model.ConsultationDto
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.ReportBuilder
import com.chiarasystems.chiara.network.CachePolicy
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.rpcParams
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Port of iOS `ReportsStore`. Reports are read-only on the phone. The backend has no clinic-wide listing RPC, only
 * `list_consultations_with_latest_report(p_patient_id)`, so the global list is assembled from one call per patient.
 */
class ReportsStore(private val client: SupabaseClient) {
    private val _reports = MutableStateFlow<List<Report>>(emptyList())
    val reports: StateFlow<List<Report>> = _reports.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var loaded = false
    private val perPatient = ConcurrentHashMap<String, List<Report>>()

    fun reset() {
        _reports.value = emptyList()
        perPatient.clear()
        loaded = false
    }

    fun report(id: String): Report? = _reports.value.firstOrNull { it.id == id }

    fun reports(patientId: String): List<Report> = _reports.value.filter { it.patientId == patientId }

    /** On failure returns what was loaded before for this patient. */
    suspend fun fetch(patientId: String): List<Report> {
        val items = try {
            client.decode<List<ConsultationDto>>(
                client.rpc(
                    "list_consultations_with_latest_report",
                    rpcParams("p_patient_id" to patientId, "p_limit" to 200),
                    CachePolicy.FallbackToDisk,
                ),
            ).map { it.toItem() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return perPatient[patientId] ?: emptyList()
        }
        val built = items.mapNotNull(ReportBuilder::build).sortedByDescending { it.date ?: DISTANT_PAST }
        perPatient[patientId] = built
        merge()
        return built
    }

    /** One request per patient, six at a time. Runs once unless [force]. */
    suspend fun loadAll(patients: List<Patient>, force: Boolean = false) {
        if ((!force && loaded) || patients.isEmpty()) return
        _isLoading.value = true
        try {
            val gate = Semaphore(MAX_IN_FLIGHT)
            coroutineScope {
                for (p in patients) launch { gate.withPermit { fetch(p.id) } }
            }
            loaded = true
        } finally {
            _isLoading.value = false
        }
    }

    private fun merge() {
        _reports.value = perPatient.values.flatten().sortedByDescending { it.date ?: DISTANT_PAST }
    }

    private companion object {
        const val MAX_IN_FLIGHT = 6
        val DISTANT_PAST: OffsetDateTime = OffsetDateTime.MIN
    }
}
