package com.chiarasystems.chiara.data

/** Turns a chat into the single prompt string a local model expects. */
object ChatTemplate {
    /**
     * Gemma's turn format. Gemma has no system role: the system text goes at the top of the first user turn.
     * `<start_of_turn>user … <end_of_turn>` / `<start_of_turn>model …`, ending with an open model turn.
     */
    fun gemma(messages: List<ChatMessage>): String {
        val system = messages.filter { it.role == ChatMessage.Role.System }.joinToString("\n\n") { it.content }
        val turns = messages.filter { it.role != ChatMessage.Role.System }
        val out = StringBuilder()
        var first = true
        for (m in turns) {
            when (m.role) {
                ChatMessage.Role.User -> {
                    out.append("<start_of_turn>user\n")
                    if (first && system.isNotEmpty()) out.append(system).append("\n\n")
                    out.append(m.content).append("<end_of_turn>\n")
                    first = false
                }
                ChatMessage.Role.Assistant -> out.append("<start_of_turn>model\n").append(m.content).append("<end_of_turn>\n")
                ChatMessage.Role.System -> Unit
            }
        }
        if (first && system.isNotEmpty()) out.append("<start_of_turn>user\n").append(system).append("<end_of_turn>\n")
        return out.append("<start_of_turn>model\n").toString()
    }
}
