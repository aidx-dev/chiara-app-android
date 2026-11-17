package com.chiarasystems.chiara.network

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Fake Supabase for UI work without a backend (port of iOS `DemoBackend`). Wire it as an OkHttp interceptor in debug
 * builds only; decoding and store logic then run exactly as against the server. All data is invented.
 */
class DemoBackend(private val today: () -> LocalDate = { LocalDate.now() }) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        val body = runCatching { Json.parseToJsonElement(buffer.readUtf8()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val (status, payload) = route(request.url.encodedPath, body)
        return Response.Builder()
            .request(request).protocol(Protocol.HTTP_1_1).code(status).message("demo")
            .body(Json.encodeToString(JsonElement.serializer(), payload).toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    internal fun route(path: String, body: JsonObject): Pair<Int, JsonElement> {
        if (path.endsWith("/auth/v1/token")) return 200 to tokenResponse()
        if (path.endsWith("/auth/v1/logout")) return 204 to JsonNull
        if (path.endsWith("/auth/v1/recover")) return 200 to JsonObject(emptyMap())
        if (path.endsWith("/rest/v1/profiles")) return 200 to buildJsonArray {
            add(buildJsonObject {
                put("clinic_id", "demo-clinic"); put("role", "doctor"); put("full_name", "Dra. Lucía Ferrer")
                put("specialties", buildJsonArray { add(JsonPrimitive("dermatology")) })
            })
        }
        if (path.endsWith("/rest/v1/clinics")) return 200 to buildJsonArray { add(buildJsonObject { put("name", "Clínica Mediterránea") }) }

        return when (path.substringAfterLast("/rpc/", "")) {
            "get_clinic_branding" -> 200 to buildJsonObject {
                put("clinic_name", "Clínica Mediterránea"); put("clinic_address", "C/ Mar 12, Valencia")
                put("doctor_name", "Dra. Lucía Ferrer"); put("doctor_license", "46/12345")
            }
            "list_patients" -> {
                val search = str(body, "p_search")?.lowercase().orEmpty()
                val items = patients.filter {
                    search.isEmpty() || "${str(it, "first_name")} ${str(it, "last_name")} ${str(it, "document_id")}".lowercase().contains(search)
                }
                200 to buildJsonObject { put("items", JsonArray(items)); put("total", items.size) }
            }
            "get_patient" -> {
                val id = str(body, "p_id")?.lowercase()
                200 to (patients.firstOrNull { str(it, "id")?.lowercase() == id } ?: JsonNull)
            }
            "create_patient" -> 200 to JsonObject(body.entries.associate { it.key.drop(2) to it.value })
            "list_appointments_in_range" -> 200 to JsonArray(appointments())
            "patient_appointment_history" -> {
                val pid = str(body, "p_patient_id")?.lowercase()
                200 to JsonArray(appointments().filter { str(it, "patient_id")?.lowercase() == pid }.map { a ->
                    buildJsonObject {
                        for (k in listOf("id", "date", "start_time", "end_time", "status")) put(k, a.getValue(k))
                        put("effective_status", a.getValue("status"))
                    }
                })
            }
            "create_appointment_with_patient" -> 200 to buildJsonObject {
                put("id", str(body, "p_appointment_id").orEmpty()); put("patient_id", str(body, "p_patient_id").orEmpty())
                put("date", str(body, "p_date").orEmpty())
                put("start_time", "${str(body, "p_start_time").orEmpty()}:00"); put("end_time", "${str(body, "p_end_time").orEmpty()}:00")
                put("status", "Scheduled"); put("notes", body["p_notes"] ?: JsonNull)
            }
            "list_consultations_with_latest_report" -> {
                val pid = str(body, "p_patient_id")?.lowercase()
                200 to JsonArray(consultations.filter { str(it, "patient_id")?.lowercase() == pid })
            }
            else -> 404 to buildJsonObject { put("message", "Demo backend: unknown route $path") }
        }
    }

    private fun b64url(s: String) = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun tokenResponse(): JsonElement {
        val payload = b64url("""{"sub":"demo-user","app_metadata":{"clinic_id":"demo-clinic","clinic_role":"doctor"}}""")
        return buildJsonObject {
            put("access_token", "${b64url("""{"alg":"HS256","typ":"JWT"}""")}.$payload.sig")
            put("refresh_token", "demo-refresh"); put("expires_in", 3600)
            put("user", buildJsonObject { put("id", "demo-user"); put("email", "lucia.ferrer@clinicamediterranea.es") })
        }
    }

    private fun pid(n: Int) = "00000000-0000-7000-8000-%012d".format(n)

    private fun p(
        n: Int, first: String, last: String, doc: String, phone: String, dob: String, sex: String, ins: String,
        email: String? = null, ref: String? = null,
    ) = buildJsonObject {
        put("id", pid(n)); put("first_name", first); put("last_name", last); put("document_id", doc); put("phone", phone)
        if (email != null) put("email", email)
        put("date_of_birth", dob); put("sex", sex); put("insurance_company", ins)
        if (ref != null) put("referring_clinic", ref)
    }

    private val patients: List<JsonObject> = listOf(
        p(1, "Lucía", "Ferrer", "12345678Z", "600 123 456", "1988-04-12", "Female", "Sanitas", "lucia@correo.es", "Clínica Mediterránea"),
        p(2, "Marcos", "Alonso", "23456789D", "611 222 333", "1975-11-30", "Male", "Adeslas"),
        p(3, "Carmen", "Beltrán", "34567890B", "622 333 444", "1962-02-03", "Female", "Privado", "carmen.b@correo.es"),
        p(4, "Iván", "Soler", "45678901C", "633 444 555", "1999-07-21", "Male", "Mapfre"),
    )

    private fun appointments(): List<JsonObject> {
        fun day(offset: Int) = today().plusDays(offset.toLong()).toString()
        fun row(n: Int, patient: Int, offset: Int, start: String, end: String, status: String, notes: String) = buildJsonObject {
            val pt = patients[patient - 1]
            put("id", pid(100 + n)); put("patient_id", pid(patient)); put("date", day(offset))
            put("start_time", "$start:00"); put("end_time", "$end:00"); put("status", status); put("notes", notes)
            put("patients", buildJsonObject { put("first_name", pt.getValue("first_name")); put("last_name", pt.getValue("last_name")) })
        }
        return listOf(
            row(1, 1, 0, "09:00", "09:30", "Completed", "Revisión — analítica"),
            row(2, 2, 0, "10:30", "11:15", "Confirmed", "Primera visita"),
            row(3, 3, 0, "12:00", "12:30", "Scheduled", "Seguimiento"),
            row(4, 4, 0, "16:00", "16:30", "Canceled", "Resultados"),
            row(5, 1, 1, "09:30", "10:00", "Scheduled", "Revisión"),
            row(6, 3, 2, "11:00", "12:00", "Scheduled", "Resultados — entrega de informe"),
            row(7, 3, -2, "10:00", "10:30", "NoShow", "Revisión"),
            row(8, 3, -20, "09:00", "09:30", "Completed", "Revisión"),
        )
    }

    private val consultations: List<JsonObject> = listOf(
        buildJsonObject {
            put("id", pid(200)); put("patient_id", pid(1)); put("occurred_at", "2026-10-12T09:30:00.123456+00:00")
            put("template_id", "dermatology"); put("status", "Finalized")
            put("content", buildJsonObject {
                put("anamnesis", "<p>Paciente de 38 años con lesión pigmentada en espalda, <strong>evolución de 6 meses</strong>.</p>")
                put("additionalTests", "<p>Dermatoscopia: patrón reticular regular.</p>")
                put("diagnosis", "<p>Nevus melanocítico benigno.</p>")
                put("icdCodes", """[{"code":"D22.5","label":"Nevus melanocítico del tronco"},{"code":"L82","label":"Queratosis seborreica"}]""")
                put("medication", "<p>Fotoprotector SPF50 — cada 3 horas</p>")
                put("treatmentPlan", "<ul><li>Fotoprotección diaria</li><li>Revisión en 6 meses</li></ul>")
            })
        },
        buildJsonObject {
            put("id", pid(201)); put("patient_id", pid(3)); put("occurred_at", "2026-09-28T11:00:00+00:00")
            put("template_id", "dermatology"); put("status", "Finalized")
            put("content", buildJsonObject {
                put("anamnesis", "<p>Control de psoriasis en placas.</p>"); put("diagnosis", "<p>Psoriasis en placas estable.</p>")
                put("icdCodes", """[{"code":"L40.0","label":"Psoriasis vulgar"}]""")
            })
        },
        buildJsonObject {
            put("id", pid(202)); put("patient_id", pid(2)); put("occurred_at", "2026-10-20T10:00:00+00:00")
            put("template_id", "dermatology"); put("status", "Draft"); put("content", JsonObject(emptyMap()))
        },
    )
}
