package com.chiarasystems.chiara.data

import com.chiarasystems.chiara.network.DemoBackend
import com.chiarasystems.chiara.network.FileResponseCache
import com.chiarasystems.chiara.network.InMemorySecureStore
import com.chiarasystems.chiara.network.SupabaseClient
import com.chiarasystems.chiara.network.SupabaseConfig
import java.io.File
import java.time.LocalDate
import org.junit.Test
import org.junit.Assert.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationTest {
    @Test fun languageResolution() {
        assertEquals("es", SettingsStore.resolve(AppLanguage.System, listOf("it-IT")))
        assertEquals("de", SettingsStore.resolve(AppLanguage.System, listOf("it-IT", "de-AT")))
        assertEquals("fr", SettingsStore.resolve(AppLanguage.Fr, listOf("en")))
    }

    @Test fun settingsPersist() {
        val prefs = InMemoryPreferences()
        SettingsStore(prefs).apply { setLanguage(AppLanguage.En); setAppearance(AppearanceMode.Dark) }
        val again = SettingsStore(prefs)
        assertEquals(AppLanguage.En, again.language.value)
        assertEquals(AppearanceMode.Dark, again.appearance.value)
    }

    @Test fun routerStacksPerTab() {
        val r = Router { LocalDate.of(2026, 1, 5) }
        r.push(Route.Patient("p1"))
        r.select(AppTab.Reports); r.push(Route.Report("r1"))
        assertEquals(listOf<Route>(Route.Patient("p1")), r.path(AppTab.Agenda).value)
        assertEquals(listOf<Route>(Route.Report("r1")), r.path(AppTab.Reports).value)
        r.reset()
        assertEquals(AppTab.Agenda, r.tab.value)
        assertEquals(emptyList<Route>(), r.path(AppTab.Agenda).value)
    }

    @Test fun toastDurations() = runTest {
        val toasts = ToastCenter(TestScope(StandardTestDispatcher(testScheduler)))
        toasts.show("hola")
        advanceTimeBy(2_700); assertEquals("hola", toasts.toast.value?.message)
        advanceTimeBy(200); assertEquals(null, toasts.toast.value)
        var undone = false
        toasts.show("borrada", "Deshacer") { undone = true }
        advanceTimeBy(7_000); assertEquals("Deshacer", toasts.toast.value?.actionTitle)
        toasts.performAction()
        assertEquals(true, undone); assertEquals(null, toasts.toast.value)
    }

    @Test fun demoBackendSignsInAndLoadsPatients() = runTest {
        val cache = File.createTempFile("cache", "").also { it.delete() }
        val client = SupabaseClient(
            SupabaseConfig("http://demo.invalid/", "anon"), InMemorySecureStore(), FileResponseCache(cache),
            OkHttpClient.Builder().addInterceptor(DemoBackend()).build(),
        )
        val auth = AuthStore(client, InMemorySecureStore())
        auth.signIn("lucia.ferrer@clinicamediterranea.es", "x")
        assertEquals(AuthPhase.SignedIn, auth.phase.value)
        assertEquals("Clínica Mediterránea", auth.user.value?.clinicName)
        val patients = PatientsStore(client, TestScope(StandardTestDispatcher(testScheduler)))
        patients.load()
        assertEquals(4, patients.patients.value.size)
        cache.deleteRecursively()
    }
}
