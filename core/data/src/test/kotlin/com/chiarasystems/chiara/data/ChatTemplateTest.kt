package com.chiarasystems.chiara.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTemplateTest {
    @Test fun gemmaPutsTheSystemTextInTheFirstUserTurnAndOpensAModelTurn() {
        val prompt = ChatTemplate.gemma(
            listOf(
                ChatMessage(ChatMessage.Role.System, "S"), ChatMessage(ChatMessage.Role.User, "Hola"),
                ChatMessage(ChatMessage.Role.Assistant, "Buenas"), ChatMessage(ChatMessage.Role.User, "Y?"),
            ),
        )
        assertEquals("<start_of_turn>user\nS\n\nHola<end_of_turn>\n<start_of_turn>model\nBuenas<end_of_turn>\n<start_of_turn>user\nY?<end_of_turn>\n<start_of_turn>model\n", prompt)
    }
}
