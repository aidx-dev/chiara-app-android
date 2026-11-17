package com.chiarasystems.chiara.data

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab { Agenda, Patients, Reports, Settings }

sealed interface Route {
    data class Patient(val id: String) : Route
    data class Report(val id: String) : Route
}

sealed interface ActiveSheet {
    data class Booking(val patientId: String?, val day: LocalDate) : ActiveSheet
    data object NewPatient : ActiveSheet
    data object Assistant : ActiveSheet
}

/** Port of iOS `Router`: selected tab, one back stack per tab, the sheet being shown and the agenda's selected day. */
class Router(private val today: () -> LocalDate = { LocalDate.now() }) {
    private val _tab = MutableStateFlow(AppTab.Agenda)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()
    private val _paths = mapOf(
        AppTab.Agenda to MutableStateFlow<List<Route>>(emptyList()),
        AppTab.Patients to MutableStateFlow<List<Route>>(emptyList()),
        AppTab.Reports to MutableStateFlow<List<Route>>(emptyList()),
    )
    private val _sheet = MutableStateFlow<ActiveSheet?>(null)
    val sheet: StateFlow<ActiveSheet?> = _sheet.asStateFlow()
    private val _agendaDay = MutableStateFlow(today())
    val agendaDay: StateFlow<LocalDate> = _agendaDay.asStateFlow()

    fun path(tab: AppTab): StateFlow<List<Route>> = _paths[tab]?.asStateFlow() ?: MutableStateFlow(emptyList<Route>()).asStateFlow()

    fun select(tab: AppTab) { _tab.value = tab }
    fun push(route: Route) { _paths[_tab.value]?.update { it + route } }
    fun pop(tab: AppTab = _tab.value) { _paths[tab]?.update { it.dropLast(1) } }
    fun show(sheet: ActiveSheet) { _sheet.value = sheet }
    fun dismissSheet() { _sheet.value = null }
    fun selectDay(day: LocalDate) { _agendaDay.value = day }

    fun reset() {
        _tab.value = AppTab.Agenda
        _paths.values.forEach { it.value = emptyList() }
        _sheet.value = null
        _agendaDay.value = today()
    }
}

data class Toast(val message: String, val actionTitle: String? = null)

/** Port of iOS `ToastCenter`. A toast with an action stays longer (8 s) so there is time to use it. */
class ToastCenter(private val scope: CoroutineScope) {
    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()
    private var action: (() -> Unit)? = null
    private var job: Job? = null

    fun show(text: String, actionTitle: String? = null, action: (() -> Unit)? = null) {
        job?.cancel()
        _toast.value = Toast(text, actionTitle)
        this.action = action
        val millis = if (action == null) 2_800L else 8_000L
        job = scope.launch { delay(millis); dismiss() }
    }

    fun performAction() {
        val run = action
        dismiss()
        run?.invoke()
    }

    fun dismiss() {
        job?.cancel()
        _toast.value = null
        action = null
    }
}
