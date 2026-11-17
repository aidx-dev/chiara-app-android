package com.chiarasystems.chiara.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Signed-in user. Role and clinic are informative only; RLS on the server enforces access. */
data class UserProfile(
    val id: String,
    val email: String,
    val fullName: String? = null,
    val role: String,
    val clinicId: String? = null,
    val clinicName: String? = null,
    val specialties: List<String> = emptyList(),
) {
    val displayName: String get() = fullName?.nilIfEmpty ?: email

    val initials: String
        get() {
            val words = displayName.split(' ', '@', '.').filter { it.isNotEmpty() }
                .filter { it.lowercase() !in setOf("dr", "dra", "dr.", "dra.") }
            return words.take(2).mapNotNull { it.firstOrNull()?.toString() }.joinToString("").uppercase().ifEmpty { "?" }
        }

    /** doctor / staff / owner map to string resources; anything else is shown capitalised. */
    enum class RoleKind { Doctor, Staff, Owner, Other }

    val roleKind: RoleKind
        get() = when (role) {
            "doctor" -> RoleKind.Doctor
            "staff" -> RoleKind.Staff
            "owner" -> RoleKind.Owner
            else -> RoleKind.Other
        }

    /** Normalised key of the first specialty (lowercase, no `_` or `-`): vascular, dermatology, plasticsurgery... */
    val specialtyKey: String? get() = specialties.firstOrNull()?.lowercase()?.filterNot { it == '_' || it == '-' }

    /** Fallback label for a specialty that has no translated name. */
    val specialtyFallback: String? get() = specialties.firstOrNull()?.humanized
}

/** Result of RPC `get_clinic_branding` (used in the PDF footer). */
@Serializable
data class ClinicBranding(
    @SerialName("clinic_name") val clinicName: String? = null,
    @SerialName("clinic_address") val clinicAddress: String? = null,
    @SerialName("clinic_phone") val clinicPhone: String? = null,
    @SerialName("clinic_email") val clinicEmail: String? = null,
    @SerialName("doctor_name") val doctorName: String? = null,
    @SerialName("doctor_specialty") val doctorSpecialty: String? = null,
    @SerialName("doctor_license") val doctorLicense: String? = null,
)
