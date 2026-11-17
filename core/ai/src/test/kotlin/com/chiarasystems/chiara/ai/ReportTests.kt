package com.chiarasystems.chiara.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTemplatesTest {
    @Test fun theDesktopsNineTemplatesLoadWithTheirPlaceholders() {
        assertEquals(
            listOf("vascular", "psychology", "dermatology", "plastic_surgery", "gynecology", "general_medicine", "internista", "nutritionist", "visitador"),
            ReportTemplates.all.map { it.id },
        )
        for (t in ReportTemplates.all) {
            assertTrue(t.id, "{LANGUAGE}" in t.reportSystemPrompt)
            assertTrue(t.id, "{LANGUAGE}" in t.reviewSystemPrompt)
            assertTrue(t.id, "{SECTION}" in t.reviewIntro || t.id == "visitador")
            assertTrue(t.id, "{TEXT}" in t.summaryUserPrompt)
            assertTrue(t.id, t.generationSections.isNotEmpty())
        }
        assertEquals(listOf("No consta", "Not stated", "Keine Angabe", "Non renseigné"), ReportTemplates.common.noConsta)
    }

    @Test fun specialtiesPickTemplatesAndVascularIsTheDefault() {
        assertEquals(listOf("plastic_surgery", "dermatology"), ReportTemplates.forSpecialties(listOf("plasticsurgery", "Dermatology", "unknown")).map { it.id })
        assertEquals(listOf("vascular"), ReportTemplates.forSpecialties(emptyList()).map { it.id })
        assertEquals("vascular", ReportTemplates.resolve("nope").id)
    }

    @Test fun promptsAreAssembledLikeTheDesktops() {
        val vascular = ReportTemplates.resolve("vascular")
        assertTrue("idioma: ESPAÑOL" in ReportPrompts.reportSystemPrompt(vascular, "Español"))
        assertTrue("la paciente" in ReportPrompts.sexRule("female"))
        assertTrue(ReportPrompts.sexRule(null).isEmpty())
        assertEquals("Alergias: No consta", ReportPrompts.localizeSectionText("Alergias: No consta", "Español"))
        val english = ReportPrompts.localizeSectionText("Alergias: No consta", "English")
        assertTrue(english.startsWith("Allergies: Not stated Escribe TODO el contenido"))
        assertTrue(english.endsWith("exclusivamente en el idioma: ENGLISH."))
        assertEquals("Keine Angabe.", ReportPrompts.noConsta("Deutsch"))
        assertTrue(ReportPrompts.isNoConsta(" no consta. ") && !ReportPrompts.isNoConsta("No consta información sobre la dieta"))
    }

    /** The desktop's own output for fixed inputs (`parity` in ReportTemplates.json) must match byte for byte. */
    @Test fun everyTemplatesPromptsAreByteForByteTheDesktops() {
        val text = ReportTemplates::class.java.getResourceAsStream("/ReportTemplates.json")!!.bufferedReader().readText()
        val parity = Json.parseToJsonElement(text).jsonObject["parity"]!!.jsonArray
        assertEquals(ReportTemplates.all.size, parity.size)
        for (p in parity) {
            val e = p.jsonObject
            fun s(k: String) = e[k]!!.jsonPrimitive.content
            val t = ReportTemplates.byId(s("id"))!!
            val first = t.generationSections.first()
            val last = t.generationSections.last()
            assertEquals(t.id, s("reportSystemEsFemale"), ReportPrompts.reportSystemPrompt(t, "Español") + ReportPrompts.sexRule("female"))
            assertEquals(t.id, s("reviewSystemEnMale"), ReportPrompts.reviewSystemPrompt(t, "English") + ReportPrompts.sexRule("male"))
            assertEquals(
                t.id, s("reviewUserLast"),
                ReportPrompts.reviewUserMessage(t, last.name, ReportPrompts.localizeSectionText(last.reviewBody, "Deutsch"), "PRIOR\n\n", "DRAFT", ReportTemplates.common.noRepeat),
            )
            assertEquals(t.id, s("firstPromptFrench"), ReportPrompts.localizeSectionText(first.prompt, "Français"))
            assertEquals(s("noConstaGerman"), ReportPrompts.noConsta("Deutsch"))
        }
    }
}

class ReportTextTest {
    @Test fun cleanSectionMatchesTheDesktop() {
        val background = "Alergias: No consta.\nHábitos Tóxicos: No consta.\nPatologías Previas: Ehlers-Danlos tipo V.\nIntervenciones: No consta."
        assertEquals(background, ReportText.cleanSection(background))
        assertEquals(
            "Capacidad vesical de 1.5 litros en el primer episodio descrito.",
            ReportText.cleanSection("Capacidad vesical de 1.5 litros en el primer episodio descrito. Capacidad vesical de 1.5 litros en el primer episodio descrito."),
        )
        assertEquals(
            "Síndrome de esófago funcional\nHernia de hiato",
            ReportText.cleanSection("Síndrome de esófago funcional\nNo consta información sobre antecedentes de alergia\nHernia de hiato\nNo se menciona tratamiento previo"),
        )
        assertEquals("No consta.", ReportText.cleanSection("No consta."))
        assertEquals(
            "Dolor epigástrico de dos años. No tiene alergias conocidas.",
            ReportText.cleanSection("Dolor epigástrico de dos años. No consta información sobre la dieta. No tiene alergias conocidas."),
        )
        assertEquals("Alergias: No consta\nHábitos Tóxicos: No consta", ReportText.cleanSection("Alergias: No consta\nHábitos Tóxicos: No consta"))
        assertEquals("Der Patient.", ReportText.cleanSection("**Historie persönliche und Kontext** \nDer Patient."))
        assertEquals("Corps.", ReportText.cleanSection("**Titre :**\nCorps."))
        assertEquals("Femme de 26 ans.", ReportText.cleanSection("Historique personnel, antécédents et contexte :\nFemme de 26 ans."))
        assertEquals("Der Patient leidet.\nWeiter.", ReportText.cleanSection("Der Patient leidet.\nWeiter."))
        assertEquals("Nur eine Zeile:", ReportText.cleanSection("Nur eine Zeile:"))
        assertEquals("Punto uno.", ReportText.cleanSection("<think>razono</think>\n# Título\n- Punto uno."))
    }

    @Test fun tailsAndYesNoMatchTheDesktop() {
        assertEquals("Primera frase. Segunda frase.", ReportText.trimUnfinishedTail("Primera frase. Segunda frase. Tercera sin ter"))
        assertEquals("Línea uno", ReportText.trimUnfinishedTail("Línea uno\nLínea dos sin"))
        assertEquals("sin ningún final", ReportText.trimUnfinishedTail("sin ningún final"))
        for (yes in listOf("Sí", "SI.", "**Sí**", "yes", "Ja", "Oui")) assertEquals(yes, true, ReportText.parseYesNo(yes))
        for (no in listOf("No", "NO.", "no, no hay exploración", "Nein", "Non")) assertEquals(no, false, ReportText.parseYesNo(no))
        for (unclear in listOf("", "Quizá", "Nota: sí")) assertNull(unclear, ReportText.parseYesNo(unclear))
    }

    @Test fun aLongTranscriptIsSummarizedInBlocksWithAnOverlap() {
        val lines = (0 until 300).map { i -> (0 until 10).joinToString(" ") { "p${i}_$it" } }
        val blocks = RollingSummary.blocks(lines)
        assertEquals(2, blocks.size)
        assertEquals(blocks[0].text, blocks[0].input)
        val tail = RollingSummary.lastWords(blocks[0].text, 200)
        assertEquals("$tail\n${blocks[1].text}", blocks[1].input)
        assertEquals(listOf("Hola.\nAdiós."), RollingSummary.blocks(listOf("Hola.", "  ", "Adiós.")).map { it.text })
    }
}

class ReportGeneratorTest {
    private class Call(val system: String, val user: String, val maxTokens: Int)

    /** Answers like a model would: a summary, each section's text (examination "No consta"), a review of each. */
    private fun generator(calls: MutableList<Call>, template: ReportTemplate) = ReportGenerator({ system, user, maxTokens ->
        calls += Call(system, user, maxTokens)
        if (system == template.summarySystemPrompt) return@ReportGenerator OpenRouterChat.Completion("Resumen: dolor en la pierna izquierda.", "m1")
        val gen = template.generationSections.firstOrNull { user.endsWith(it.prompt) }
        if (gen != null) {
            val text = if (gen.fieldId == "examination") "No consta." else "Borrador de ${gen.fieldId} con datos."
            return@ReportGenerator OpenRouterChat.Completion(text, "m1")
        }
        val section = template.generationSections.firstOrNull { user.contains(template.reviewIntro.replace("{SECTION}", it.name)) }
        OpenRouterChat.Completion("**${section?.name ?: "?"}**\nRevisado de ${section?.fieldId ?: "?"}.", "m2")
    })

    @Test fun aVascularReportGoesThroughSummaryBothPassesAndAssembly() = runTest {
        val vascular = ReportTemplates.resolve("vascular")
        val calls = mutableListOf<Call>()
        val steps = mutableListOf<ReportProgress>()
        val report = generator(calls, vascular).generate(
            listOf("Médico: ¿Qué le pasa?", "Paciente: Me duele la pierna izquierda."), vascular, "Español", "female",
        ) { steps += it }

        assertEquals(14, calls.size) // 1 summary + 7 sections + 6 reviews (examination is "No consta" and is not reviewed)
        assertTrue(calls[0].maxTokens == 3000 && "Paciente: Me duele la pierna izquierda." in calls[0].user)
        val pass1 = calls.subList(1, 8)
        assertTrue(pass1.all { "idioma: ESPAÑOL" in it.system && it.system.endsWith(ReportPrompts.sexRule("female")) })
        assertTrue(pass1.first().user.startsWith("Resumen: dolor en la pierna izquierda.\n\n"))
        assertTrue(pass1.any { it.user.startsWith("Borrador de anamnesis con datos.\n\n") })
        assertTrue(pass1.zip(vascular.generationSections).all { (c, s) -> c.maxTokens == s.maxTokens })
        val reviews = calls.subList(8, calls.size)
        assertTrue("ya han sido redactadas" !in reviews[0].user && ReportTemplates.common.noRepeat !in reviews[0].user)
        assertTrue(reviews[1].user.startsWith("Las siguientes secciones del informe ya han sido redactadas:\n\n## Antecedentes\nRevisado de background."))
        assertTrue(reviews[1].user.endsWith(ReportTemplates.common.noRepeat))

        assertFalse(report.sections.any { it.fieldId == "examination" })
        assertEquals("Revisado de anamnesis.", report.sections.first { it.fieldId == "anamnesis" }.text)
        val order = vascular.fields.map { it.id }.filter { id -> report.sections.any { it.fieldId == id } }
        assertEquals(order, report.sections.map { it.fieldId })
        assertEquals(vascular.field("diagnosis")?.titles?.get(0), report.sections.first { it.fieldId == "diagnosis" }.title)
        assertEquals(listOf("m1", "m2"), report.models)
        assertTrue(report.templateId == "vascular" && report.language == "Español")
        assertEquals(ReportProgress.Summarizing(1, 1), steps.first())
        assertEquals(ReportProgress.Reviewing(7, 7), steps.last())
    }

    @Test fun emptySectionsGetTheirFixedLineAndNothingAtAllIsAnError() {
        val vascular = ReportTemplates.resolve("vascular")
        val texts = vascular.generationSections.map { if (it.fieldId == "anamnesis") "Dolor pélvico de dos años de evolución." else "No consta." }
        val report = ReportGenerator.assemble(vascular, texts, "Español", emptyList())
        assertEquals("Pendiente de filiación diagnóstica.", report.sections.first { it.fieldId == "diagnosis" }.text)
        assertEquals("Sin cambios terapéuticos indicados en esta consulta.", report.sections.first { it.fieldId == "treatmentPlan" }.text)
        assertThrows(ReportGeneratorError.NoContent::class.java) {
            ReportGenerator.assemble(vascular, vascular.generationSections.map { "No consta." }, "Español", emptyList())
        }
    }

    @Test fun aFailedSummaryKeepsTheTranscriptAndAKeyProblemStopsEverything() = runTest {
        val visitador = ReportTemplates.resolve("visitador")
        val calls = mutableListOf<Call>()
        val failing = ReportGenerator({ system, user, maxTokens ->
            calls += Call(system, user, maxTokens)
            if (system == visitador.summarySystemPrompt) throw ChatError.Rejected(500)
            OpenRouterChat.Completion("Visita comercial registrada.", "m1")
        })
        failing.generate(listOf("Texto tal cual."), visitador, "Español", null)
        assertTrue(calls[1].user.startsWith("Texto tal cual.\n\n"))

        val noKey = ReportGenerator({ _, _, _ -> throw ChatError.Rejected(401) })
        val e = runCatching { noKey.generate(listOf("Hola."), visitador, "Español", null) }.exceptionOrNull()
        assertEquals(ChatError.Rejected(401), e)
    }
}

class OpenRouterChatTest {
    private class Sent {
        val posts = mutableListOf<Pair<String, String>>() // key, body
        var answers = listOf<HttpReply>()
        val waits = mutableListOf<Long>()
    }

    private val target = OpenRouterChat.Target("https://openrouter.ai/api/v1/chat/completions", "sk-or-test", listOf("primary/model", "fallback/model"))
    private fun ok(text: String, model: String = "primary/model", finish: String = "stop") =
        HttpReply(200, """{"model":"$model","choices":[{"message":{"content":"$text"},"finish_reason":"$finish"}]}""".toByteArray())

    private fun chat(sent: Sent, config: UserAIConfig = UserAIConfig("sk-or-test")) = OpenRouterChat(
        config = { config },
        sender = { _, key, body, _ ->
            sent.posts += key to body
            sent.answers[minOf(sent.posts.size, sent.answers.size) - 1]
        },
        sleep = { sent.waits += it },
    )

    private fun model(body: String) = Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content

    @Test fun theRequestIsTheDesktopsPlusZeroRetention() = runTest {
        val sent = Sent().apply { answers = listOf(ok("Texto.")) }
        val answer = chat(sent).complete("S", "U", 600, target)
        assertEquals(OpenRouterChat.Completion("Texto.", "primary/model"), answer)
        val (key, body) = sent.posts.single()
        assertEquals("sk-or-test", key)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("primary/model", json["model"]!!.jsonPrimitive.content)
        assertEquals("600", json["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("false", (json["reasoning"] as JsonObject)["enabled"]!!.jsonPrimitive.content)
        assertEquals("false", (json["chat_template_kwargs"] as JsonObject)["enable_thinking"]!!.jsonPrimitive.content)
        val provider = json["provider"] as JsonObject
        assertEquals("true", provider["zdr"]!!.jsonPrimitive.content)
        assertEquals("deny", provider["data_collection"]!!.jsonPrimitive.content)
        assertEquals("system", json["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenRouterChat.endpoint("https://openrouter.ai/api/v1/"))
        assertEquals("https://openrouter.ai/api/v1/chat/completions", OpenRouterChat.endpoint("https://openrouter.ai/api"))
    }

    @Test fun transientAnswersAreRetriedThenTheNextModelIsTried() = runTest {
        val sent = Sent().apply { answers = listOf(HttpReply(429, "{}".toByteArray(), "3"), HttpReply(503, "{}".toByteArray()), ok("Del segundo.", "fallback/model")) }
        val answer = chat(sent).complete("S", "U", 10, target)
        assertEquals("fallback/model", answer.model)
        assertEquals(listOf("primary/model", "primary/model", "fallback/model"), sent.posts.map { model(it.second) })
        assertEquals(listOf(3000L), sent.waits)
    }

    @Test fun aModelErrorFallsBackButAKeyProblemNeverDoes() = runTest {
        val notFound = Sent().apply { answers = listOf(HttpReply(404, """{"error":{"message":"No endpoints found"}}""".toByteArray()), ok("Bien.", "fallback/model")) }
        assertEquals("fallback/model", chat(notFound).complete("S", "U", 10, target).model)
        for (status in listOf(401, 402, 403)) {
            val refused = Sent().apply { answers = listOf(HttpReply(status, "{}".toByteArray())) }
            val e = runCatching { chat(refused, UserAIConfig("sk-or-test")).complete("S", "U", 10, target) }.exceptionOrNull()
            assertEquals(ChatError.Rejected(status), e)
            // 401 re-reads the key once (same key: no repeat); the others never repeat.
            assertEquals(status.toString(), 1, refused.posts.size)
        }
    }

    @Test fun theLastModelGetsFourAttemptsAndACutReplyLosesItsTail() = runTest {
        val sent = Sent().apply { answers = listOf(HttpReply(500, "{}".toByteArray())) + List(4) { HttpReply(429, "{}".toByteArray()) } }
        val e = runCatching { chat(sent).complete("S", "U", 10, target) }.exceptionOrNull()
        assertEquals(ChatError.Rejected(429), e)
        assertEquals(5, sent.posts.size)
        assertEquals(listOf(1000L, 2000L, 4000L), sent.waits)

        val cut = Sent().apply { answers = listOf(ok("Primera frase. Segunda sin ter", finish = "length")) }
        assertEquals("Primera frase.", chat(cut).complete("S", "U", 10, target).text)
    }

    @Test fun withoutAKeyOrAnLlmNothingIsSent() = runTest {
        val sent = Sent()
        suspend fun targetOf(c: UserAIConfig) = runCatching { chat(sent, c).target() }
        assertEquals(ChatError.NotAvailable, targetOf(UserAIConfig(null, listOf(UserAIConfig.Role("llm", "https://openrouter.ai/api/v1", listOf("m"))))).exceptionOrNull())
        assertEquals(ChatError.NotAvailable, targetOf(UserAIConfig("k", listOf(UserAIConfig.Role("asr", "https://x.y", listOf("m"))))).exceptionOrNull())
        val t = targetOf(UserAIConfig("k", listOf(UserAIConfig.Role("llm", "https://openrouter.ai/api/v1", listOf("a", "b"))))).getOrThrow()
        assertEquals(listOf("a", "b"), t.models)
        assertEquals("https://openrouter.ai/api/v1/chat/completions", t.url)
    }
}
