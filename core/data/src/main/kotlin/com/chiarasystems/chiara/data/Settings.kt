package com.chiarasystems.chiara.data

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppLanguage(val raw: String, val nativeName: String?) {
    System("system", null), Es("es", "Español"), En("en", "English"), De("de", "Deutsch"), Fr("fr", "Français");

    companion object {
        val supported = listOf(Es, En, De, Fr)
        fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: System
    }
}

enum class AppearanceMode(val raw: String) {
    System("system"), Light("light"), Dark("dark");

    companion object { fun from(raw: String?) = entries.firstOrNull { it.raw == raw } ?: System }
}

/** Tiny persistence seam (SharedPreferences on Android). Nothing stored here is clinical data. */
interface Preferences {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

class InMemoryPreferences : Preferences {
    private val map = HashMap<String, String>()
    override fun getString(key: String) = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
}

/** Port of iOS `AppSettings`: language and appearance. */
class SettingsStore(private val prefs: Preferences, private val deviceLanguages: () -> List<String> = { listOf(Locale.getDefault().language) }) {
    private val _language = MutableStateFlow(AppLanguage.from(prefs.getString(LANGUAGE_KEY)))
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _appearance = MutableStateFlow(AppearanceMode.from(prefs.getString(APPEARANCE_KEY)))
    val appearance: StateFlow<AppearanceMode> = _appearance.asStateFlow()

    fun setLanguage(value: AppLanguage) { prefs.putString(LANGUAGE_KEY, value.raw); _language.value = value }
    fun setAppearance(value: AppearanceMode) { prefs.putString(APPEARANCE_KEY, value.raw); _appearance.value = value }

    /** The language actually in use: `System` resolved against the device, falling back to Spanish. */
    val code: String
        get() = resolve(_language.value, deviceLanguages())

    companion object {
        const val LANGUAGE_KEY = "settings.language"
        const val APPEARANCE_KEY = "settings.appearance"

        fun resolve(language: AppLanguage, device: List<String>): String =
            if (language != AppLanguage.System) language.raw
            else device.map { it.substringBefore('-').substringBefore('_').lowercase() }
                .firstOrNull { c -> AppLanguage.supported.any { it.raw == c } } ?: "es"
    }
}
