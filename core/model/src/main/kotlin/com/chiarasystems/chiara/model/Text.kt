package com.chiarasystems.chiara.model

import java.util.Locale

/** Port of `String.trimmed` / `nilIfEmpty` / `humanized` / `capitalizedFirst`. */
val String.trimmed: String get() = trim { it.isWhitespace() }

val String.nilIfEmpty: String? get() = if (isEmpty()) null else this

/** "treatmentPlan" -> "Treatment plan" */
val String.humanized: String
    get() {
        val out = StringBuilder()
        for (ch in this) {
            if (ch.isUpperCase() && out.isNotEmpty()) out.append(' ')
            out.append(if (out.isEmpty()) ch.uppercase(Locale.ROOT) else ch.lowercase(Locale.ROOT))
        }
        return out.toString()
    }

val String.capitalizedFirst: String
    get() = if (isEmpty()) this else substring(0, 1).uppercase() + substring(1)
