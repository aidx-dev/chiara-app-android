package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.domain.AgendaRules
import com.chiarasystems.chiara.domain.AssistantIntent
import com.chiarasystems.chiara.domain.PatientMatcher
import com.chiarasystems.chiara.model.AppointmentStatus
import com.chiarasystems.chiara.model.Dates
import com.chiarasystems.chiara.model.Patient
import com.chiarasystems.chiara.model.Report
import com.chiarasystems.chiara.model.ReportSection
import com.chiarasystems.chiara.model.nilIfEmpty
import com.chiarasystems.chiara.model.trimmed
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Read-only view of the clinic's data for the assistant's tools. Everything it returns is plain text for the language model,
 * short enough for its small context window. Nothing here writes: a change the model asks for goes to [AssistantStore]
 * through [proposeChange], and only happens after the user confirms. Port of iOS `ClinicReader`.
 */
class ClinicReader(
    private val patients: PatientsStore,
    private val appointments: AppointmentsStore,
    private val reports: ReportsStore,
    private val days: DayFormatter,
    /** Translates the activity labels shown to the person ("Consultando la agenda…"). */
    private val tr: (String) -> String = { it },
    private val today: () -> LocalDate = { LocalDate.now() },
    private val nowMinutes: () -> Int = { Dates.minutesNow() },
) {
    /** Tells the sheet what is being looked up ("Consultando la agenda…"). */
    var onActivity: ((String?) -> Unit)? = null
    /** What the user asked (set by the agent before each answer). Tool arguments the model made up are checked against it. */
    var request = ""
    /** What the tools returned since the agent last cleared it (the grounding check compares the answer with it). */
    var sources = mutableListOf<String>()
    /** The last patient a tool looked up, so a follow-up ("¿y su próxima cita?") knows who it is about. */
    var lastPatient: Patient? = null
    /** Set by the store (through the agent): runs a change the way a typed order runs. */
    var proposeChange: (suspend (AssistantIntent) -> String)? = null
    /** Every tool call with its arguments and what it returned (the assistant bench listens). */
    var onToolCall: ((tool: String, arguments: String, result: String) -> Unit)? = null

    /** Reports a tool call and hands its result back. */
    fun trace(tool: String, arguments: String, result: String): String {
        onToolCall?.invoke(tool, arguments, result)
        sources += result
        return result
    }

    /** The change tool's way to the store. */
    suspend fun propose(intent: AssistantIntent): String {
        onActivity?.invoke(tr("Preparando el cambio…"))
        val p = proposeChange ?: return "Changes are not available here."
        return p(intent)
    }

    // --- Agenda

    /** Appointments and free 30-minute slots of [first]…[last] (at most 7 days). */
    suspend fun agenda(first: String, last: String?): String {
        onActivity?.invoke(tr("Consultando la agenda…"))
        val start = Dates.parseIso(first) ?: return BAD_DATE
        val parsedEnd = last?.let(Dates::parseIso) ?: start
        val end = minOf(maxOf(parsedEnd, start), start.plusDays(6))
        appointments.load(start, end)
        appointments.loadError.value?.let { return "The agenda could not be loaded: ${it.message}" }
        val t0 = today()
        val single = start == end
        val lines = mutableListOf<String>()
        var day = start
        while (!day.isAfter(end)) { lines += agendaLine(day, t0, if (single) 10 else 5); day = day.plusDays(1) }
        return limit(lines.joinToString("\n"))
    }

    private fun agendaLine(day: LocalDate, today: LocalDate, freeLimit: Int): String {
        val title = "${days.longDay(day)} (${Dates.iso(day)})"
        if (day.dayOfWeek == DayOfWeek.SUNDAY) return "$title: closed (Sunday)."
        val active = appointments.appointments(day).filter { it.statusKey != AppointmentStatus.Canceled }
        var text = "$title: " + if (active.isEmpty()) "no appointments." else "${active.size} appointment(s)."
        for (a in active) {
            val state = if (a.statusKey == AppointmentStatus.Scheduled) "" else " [${a.statusKey.name}]"
            text += "\n  ${Dates.hhmm(a.startMinutes)}-${Dates.hhmm(a.endMinutes)} ${a.patientName}, ${clip(subtitle(a), 60)}$state"
        }
        if (day.isBefore(today)) return text
        val busy = appointments.busyIntervals(day)
        val free = AgendaRules.slots.filter { AgendaRules.violation(day, it, 30, busy, today, nowMinutes()) == null }
        text += if (free.isEmpty()) "\n  Free 30-minute slots: none."
        else "\n  Free 30-minute slots: ${free.take(freeLimit).joinToString(", ") { Dates.hhmm(it) }}${if (free.size > freeLimit) ", …" else ""}."
        return text
    }

    private fun subtitle(a: com.chiarasystems.chiara.model.Appointment): String {
        val v = a.visit
        return v.type?.let { if (v.reason.isEmpty()) it.raw else "${it.raw} · ${v.reason}" } ?: v.reason.ifEmpty { "Consulta" }
    }

    // --- Patients

    /** The clinic's patients matching some filters, or all of them: how many and who. */
    suspend fun findPatients(name: String?, insurance: String?, sex: String?, minAge: Int?, maxAge: Int?): String {
        onActivity?.invoke(tr("Consultando los pacientes…"))
        // The model fills filters nobody asked for ("female", an insurer, "up to 120 years"): only the ones the request mentions apply.
        val said = PatientMatcher.fold(request)
        val words = said.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        fun mentioned(value: String) = request.isEmpty() || said.contains(PatientMatcher.fold(value))
        // "más de 60" arrives as 61: an age next to a number of the request was asked for.
        val numbers = said.split(Regex("[^\\p{N}]+")).mapNotNull { it.toIntOrNull() }
        fun mentionedAge(age: Int) = request.isEmpty() || numbers.any { kotlin.math.abs(it - age) <= 1 }
        fun mentionedSex(sex: String): Boolean {
            if (request.isEmpty()) return true
            val (exact, stems) = if (sex == "female") FEMALE_WORDS else MALE_WORDS
            return words.any { it in exact } || words.any { w -> stems.any { w.startsWith(it) } }
        }
        val nameF = name?.takeIf { mentioned(it) }
        val insuranceF = insurance?.takeIf { mentioned(it) }
        val sexF = sex?.takeIf { mentionedSex(it) }
        val minF = minAge?.takeIf { mentionedAge(it) }
        val maxF = maxAge?.takeIf { it < 110 && mentionedAge(it) }

        if (patients.patients.value.isEmpty()) patients.load()
        if (patients.patients.value.isEmpty()) patients.loadError.value?.let { return "The patient list could not be loaded: ${it.message}" }
        namedPatientSummary()?.let { return it }

        var found = patients.patients.value
        val filters = mutableListOf<String>()
        // A "name" that is nobody's is usually not a name ("¿Cuántos pacientes tiene la clínica?" sent "clínica"): left out, and the model is told so.
        var note = ""
        if (nameF != null) {
            val named = found.filter { PatientMatcher.matches(nameF, it) }
            if (named.isEmpty()) note = "\n(No patient's name matches \"$nameF\", so that was not used as a filter.)"
            else { found = named; filters += "name \"$nameF\"" }
        }
        if (insuranceF != null) {
            val wanted = PatientMatcher.fold(insuranceF)
            found = found.filter { PatientMatcher.fold(it.insuranceCompany.orEmpty()).contains(wanted) }
            filters += "insurance \"$insuranceF\""
        }
        if (sexF != null) {
            val accepted = if (sexF == "female") setOf("female", "mujer") else setOf("male", "hombre")
            found = found.filter { it.sex.orEmpty().lowercase() in accepted }
            filters += sexF
        }
        if (minF != null) { found = found.filter { (it.age() ?: -1) >= minF }; filters += "age $minF or more" }
        if (maxF != null) { found = found.filter { (it.age() ?: Int.MAX_VALUE) <= maxF }; filters += "age $maxF or less" }

        var text = if (filters.isEmpty()) "The clinic has ${found.size} patient(s) in total."
        else "${found.size} of the clinic's ${patients.patients.value.size} patients match (${filters.joinToString(", ")})."
        val rows = found.take(12).map { p ->
            "  " + listOfNotNull(p.fullName, p.documentId?.let { "ID $it" }, p.age()?.let { "$it years" }, p.insuranceCompany)
                .mapNotNull { it.trimmed.nilIfEmpty }.joinToString(", ")
        }
        if (rows.isNotEmpty()) text += "\n" + rows.joinToString("\n")
        if (found.size > rows.size) text += "\n  …and ${found.size - rows.size} more."
        return limit(text + note)
    }

    private sealed interface Resolved {
        /** [note] says the name was not an exact match, so the model can tell the user. */
        data class Found(val patient: Patient, val note: String?) : Resolved
        /** Already worded for the model: no match, or several to choose from. */
        data class Message(val text: String) : Resolved
    }

    private suspend fun resolve(name: String): Resolved {
        if (patients.patients.value.isEmpty()) patients.load()
        val all = patients.patients.value
        (PatientMatcher.match(name, all) as? PatientMatcher.Match.One)?.let { lastPatient = it.patient; return Resolved.Found(it.patient, null) }
        return when (val m = PatientMatcher.closest(name, all)) {
            PatientMatcher.Match.None -> Resolved.Message("No patient matches \"$name\". Use findPatients to look for similar names.")
            is PatientMatcher.Match.One -> {
                lastPatient = m.patient
                Resolved.Found(m.patient, "No patient is called exactly \"$name\"; the closest one is ${m.patient.fullName}. Tell the user.")
            }
            is PatientMatcher.Match.Many -> {
                val list = m.patients.take(6).joinToString("; ") { p -> listOfNotNull(p.fullName, p.documentId, p.age()?.let { "$it years" }).mapNotNull { it.nilIfEmpty }.joinToString(", ") }
                Resolved.Message("Several patients match \"$name\": $list. Ask the user which one.")
            }
        }
    }

    /** Runs [body] for the patient [name] points to, or answers with why there is none. */
    private suspend fun withPatient(name: String, body: suspend (Patient) -> String): String = when (val r = resolve(name)) {
        is Resolved.Message -> r.text
        is Resolved.Found -> { val text = body(r.patient); limit(r.note?.let { "$it\n$text" } ?: text) }
    }

    /** Everything about one patient in one answer: details, appointments and latest reports. */
    suspend fun patientSummary(name: String): String {
        onActivity?.invoke(tr("Preparando el resumen…"))
        return withPatient(name) { summary(it) }
    }

    /** The record of one patient for the "Resumir" button: all the model gets there, so it may be twice as long. */
    suspend fun summaryText(patient: Patient): String {
        lastPatient = patient
        val budget = MAX_CHARACTERS * 2
        return summary(patient, budget).take(budget)
    }

    private suspend fun summary(p: Patient, budget: Int = MAX_CHARACTERS): String {
        val details = info(p).take(400)
        val visits = appointmentsText(p).take(450)
        val room = budget - details.length - visits.length - 4
        val count = if (budget > MAX_CHARACTERS) 3 else 2
        return listOf(details, visits, reportsText(p, count, room)).joinToString("\n\n")
    }

    /** The model sometimes answers a question about one patient with a tool for the whole clinic: then the patient's summary answers. */
    private suspend fun namedPatientSummary(): String? {
        val patient = PatientMatcher.named(request, patients.patients.value) ?: return null
        lastPatient = patient
        return limit("The question is about one patient, ${patient.fullName}:\n" + summary(patient))
    }

    suspend fun patientInfo(name: String): String {
        onActivity?.invoke(tr("Consultando la ficha…"))
        return withPatient(name) { info(it) }
    }

    private fun info(p: Patient): String {
        val sex = when (p.sexKind) { Patient.SexKind.Female -> "Female"; Patient.SexKind.Male -> "Male"; Patient.SexKind.Other -> "Other"; null -> null }
        val fields = listOf(
            "Name" to p.fullName, "Age" to p.age()?.toString(), "Sex" to sex, "ID document" to p.documentId, "Phone" to p.phone,
            "Email" to p.email, "Insurance" to p.insuranceCompany, "Referring clinic" to p.referringClinic,
        )
        return limit(fields.mapNotNull { (label, value) -> value?.trimmed?.nilIfEmpty?.let { "$label: $it" } }.joinToString("\n"))
    }

    // --- Appointments of a patient

    suspend fun patientAppointments(name: String): String {
        onActivity?.invoke(tr("Consultando las citas…"))
        return withPatient(name) { appointmentsText(it) }
    }

    private suspend fun appointmentsText(p: Patient): String {
        val t0 = today()
        appointments.load(t0, t0.plusDays(180))
        val upcoming = appointments.upcoming(p.id, t0, nowMinutes()).take(5)
        val history = appointments.history(p.id)
        val past = history.filter { a -> a.day?.let { it.isBefore(t0) || a.statusKey == AppointmentStatus.Completed || a.statusKey == AppointmentStatus.NoShow } == true }
            .sortedWith(compareByDescending<com.chiarasystems.chiara.model.PatientAppointment> { it.date }.thenByDescending { it.startMinutes })
        fun line(date: String, start: Int, status: AppointmentStatus) = "  $date ${Dates.hhmm(start)} [${status.name}]"
        // Said in so many words: the model took a missed appointment for "the last consultation".
        val lastVisit = past.firstOrNull { it.statusKey != AppointmentStatus.NoShow && it.statusKey != AppointmentStatus.Canceled }
        var text = "${p.fullName}\nLast visit the patient came to: " + (lastVisit?.let { "${it.date} ${Dates.hhmm(it.startMinutes)}." } ?: "none recorded.")
        text += "\nUpcoming appointments:"
        text += if (upcoming.isEmpty()) " none." else "\n" + upcoming.joinToString("\n") { line(it.date, it.startMinutes, it.statusKey) }
        text += "\nPast appointments (newest first):"
        text += if (past.isEmpty()) " none." else "\n" + past.take(6).joinToString("\n") { line(it.date, it.startMinutes, it.statusKey) }
        val missed = history.count { it.statusKey == AppointmentStatus.NoShow }
        if (missed > 0) text += "\nMissed appointments in total: $missed."
        return limit(text)
    }

    // --- Reports

    /** The latest [count] reports of a patient, the way they read in the app. */
    suspend fun patientReports(name: String, count: Int): String {
        onActivity?.invoke(tr("Consultando los informes…"))
        return withPatient(name) { p ->
            val text = reportsText(p, count)
            // Without reports, the visits are the only record of when the patient came.
            if (reports.reports(p.id).isNotEmpty()) text else limit(text + "\n" + appointmentsText(p))
        }
    }

    /** [budget]: characters the reports may take, shared between them. */
    private suspend fun reportsText(p: Patient, count: Int, budget: Int = MAX_CHARACTERS): String {
        val found = reports.fetch(p.id)
        if (found.isEmpty()) return "${p.fullName} has no reports."
        val shown = found.take(count.coerceIn(1, 3))
        val perReport = maxOf(budget - 60, 200) / shown.size
        var text = "${p.fullName}: ${found.size} report(s), newest first."
        for (report in shown) {
            var entry = "\n${report.date?.toLocalDate()?.let(Dates::iso).orEmpty()} · ${report.title ?: "Informe de consulta"}"
            for (section in report.sections) entry += "\n  ${section.title}: ${clip(textOf(section), 240)}"
            text += entry.take(perReport)
        }
        return text.take(budget)
    }

    /** Reports of the whole clinic that mention [query] (diagnosis, ICD-10 code, medication, any text). */
    suspend fun searchReports(query: String): String {
        onActivity?.invoke(tr("Buscando en los informes…"))
        if (patients.patients.value.isEmpty()) patients.load()
        reports.loadAll(patients.patients.value)
        // The model sometimes looks a patient's name up here: answer with that patient's data.
        namedPatientSummary()?.let { return it }
        when (val m = PatientMatcher.match(query, patients.patients.value)) {
            is PatientMatcher.Match.One -> { lastPatient = m.patient; return limit(summary(m.patient)) }
            is PatientMatcher.Match.Many -> return "\"$query\" is the name of several patients, not something in a report. Ask the user which patient, then use getPatientReports."
            PatientMatcher.Match.None -> Unit
        }
        val needle = PatientMatcher.fold(query)
        if (needle.isEmpty()) return "Nothing to search for."
        val hits = reports.reports.value.filter { r ->
            PatientMatcher.fold((listOf(r.title ?: "Informe de consulta") + r.sections.map(::textOf)).joinToString(" ")).contains(needle)
        }
        if (hits.isEmpty()) return "No report mentions \"$query\"."
        val rows = hits.take(8).map { r ->
            val name = patients.patient(r.patientId)?.fullName ?: "?"
            "$name, ${r.date?.toLocalDate()?.let(Dates::iso).orEmpty()}: ${clip(r.title ?: "Informe de consulta", 80)}"
        }
        return limit("${hits.size} report(s) mention \"$query\":\n" + rows.joinToString("\n"))
    }

    private fun textOf(section: ReportSection): String = when (val b = section.body) {
        is ReportSection.Body.Text -> b.text
        is ReportSection.Body.Diagnoses -> (listOf(b.text) + b.codes.map { "${it.code} ${it.label}" }).filter { it.isNotEmpty() }.joinToString("; ")
        is ReportSection.Body.Medication -> b.lines.joinToString("; ") { listOf(it.name, it.dose).filter { s -> s.isNotEmpty() }.joinToString(" ") }
    }

    // --- Text

    /** One line: sentences of a report are collapsed and cut. */
    private fun clip(s: String, limit: Int): String {
        val flat = s.replace("\n", " ").replace("  ", " ").trimmed
        return if (flat.length > limit) flat.take(limit - 1) + "…" else flat
    }

    private fun limit(s: String) = if (s.length > MAX_CHARACTERS) s.take(MAX_CHARACTERS - 1) + "…" else s

    companion object {
        /** What one tool may hand back (~400 tokens): the model's context window is small. */
        const val MAX_CHARACTERS = 1500
        private const val BAD_DATE = "That date is not valid. Use yyyy-MM-dd taken from the calendar."
        private val FEMALE_WORDS = setOf("mujer", "mujeres", "female", "females", "woman", "women", "frau", "frauen", "femme", "femmes") to listOf("femenin", "weiblich", "feminin")
        private val MALE_WORDS = setOf("hombre", "hombres", "varon", "varones", "male", "males", "man", "men", "mann", "manner", "homme", "hommes") to listOf("masculin", "mannlich")
    }
}
