package digital.hstech.jarvis

/** Papel de uma mensagem na conversa. */
enum class Role { USER, MODEL }

/** Uma mensagem da conversa — do Senhor (USER) ou do J.A.R.V.I.S. (MODEL). */
data class Message(val role: Role, val text: String)
