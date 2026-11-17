package com.chiarasystems.chiara.model

import java.time.LocalDate
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `PatientResponse` of the desktop app (RPC `list_patients` / `get_patient` / `create_patient`). */
@Serializable
data class Patient(
    val id: String,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("document_id") val documentId: String? = null,
    val phone: String? = null,
    val email: String? = null,
    @SerialName("date_of_birth") val dateOfBirth: String? = null,
    val sex: String? = null,
    /** The language the clinic registered for the patient ("Español", "English"...). */
    val language: String? = null,
    @SerialName("insurance_company") val insuranceCompany: String? = null,
    @SerialName("referring_clinic") val referringClinic: String? = null,
) {
    val fullName: String
        get() = listOf(firstName, lastName).mapNotNull { it?.trimmed?.nilIfEmpty }.joinToString(" ")

    val initials: String
        get() {
            val a = firstName?.trimmed?.firstOrNull()?.toString().orEmpty()
            val b = lastName?.trimmed?.firstOrNull()?.toString().orEmpty()
            return (a + b).uppercase().ifEmpty { "?" }
        }

    val birthDate: LocalDate? get() = Dates.parseIso(dateOfBirth)

    /** Null when unknown or impossible (future date, typo like 1826): "-195 años" was shown otherwise. */
    fun age(today: LocalDate = LocalDate.now()): Int? =
        birthDate?.let { Dates.age(it, today) }?.takeIf { it in 0..130 }

    enum class SexKind { Female, Male, Other }

    /** `female|mujer`, `male|hombre`, any other non-empty value is Other; null when empty. */
    val sexKind: SexKind?
        get() = when (val s = sex?.lowercase()) {
            "female", "mujer" -> SexKind.Female
            "male", "hombre" -> SexKind.Male
            null, "" -> null
            else -> SexKind.Other
        }

    /**
     * Index into the 4 rotating avatar tints: `(uuid[15] + uuid[14] + uuid[13])` wraps at 8 bits (UInt8 in Swift) before
     * the modulo, so the same patient gets the same colour on both platforms.
     */
    val tintIndex: Int
        get() {
            val u = runCatching { UUID.fromString(id) }.getOrNull() ?: return 0
            val lsb = u.leastSignificantBits
            val b15 = (lsb and 0xFF).toInt()
            val b14 = ((lsb ushr 8) and 0xFF).toInt()
            val b13 = ((lsb ushr 16) and 0xFF).toInt()
            return ((b15 + b14 + b13) and 0xFF) % 4
        }
}

/** Fields of the "Nuevo paciente" sheet. [Sex.server] is what is sent to the server. */
data class PatientDraft(
    val firstName: String = "",
    val lastName: String = "",
    val documentId: String = "",
    val dateOfBirth: LocalDate? = null,
    val sex: Sex = Sex.Female,
    val phone: String = "",
    val email: String = "",
    val insuranceCompany: String = "",
    val referringClinic: String = "",
) {
    enum class Sex(val server: String) { Female("Female"), Male("Male"), Other("Other") }

    val canSave: Boolean get() = firstName.trimmed.isNotEmpty() && lastName.trimmed.isNotEmpty()

    val emailIsValid: Boolean
        get() = email.trimmed.let { it.isEmpty() || EMAIL.matches(it) }

    companion object {
        val EMAIL = Regex("""^\S+@\S+\.\S+$""")
    }
}
