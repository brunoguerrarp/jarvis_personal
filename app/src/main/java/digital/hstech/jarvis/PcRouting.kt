package digital.hstech.jarvis

import java.text.Normalizer

/** Pedido de desenvolvimento destinado ao PC (Claude Code), extraído da fala/texto. */
data class PedidoPc(val projeto: String?, val novo: Boolean, val texto: String)

/**
 * Roteamento compartilhado entre o chat (JarvisViewModel) e a escuta por voz
 * (WakeWordService): decide se uma frase é um pedido para o PC, interpreta
 * projeto/criação e reconhece as confirmações (sim/não). Fonte ÚNICA dos
 * gatilhos — mexa aqui e vale para os dois caminhos.
 */
object PcRouting {

    // "thor"/"tor" = apelido do Senhor para o Claude ("tor" cobre a transcrição
    // mais provável do reconhecedor para a palavra inglesa "Thor").
    val GATILHOS_PC = listOf(
        "claude", "thor", "tor", "no computador", "no pc", "no desktop", "pelo computador",
    )
    // Pedido para PARAR a escuta (desliga o mãos-livres / encerra o serviço de voz).
    // NÃO pode virar consulta ao PC.
    val GATILHOS_PARAR = listOf(
        "para de escutar", "pare de escutar", "parar de escutar", "pode parar de escutar",
        "para de ouvir", "pare de ouvir", "parar de ouvir", "chega de escutar",
        "desligar escuta", "desliga a escuta", "desligue a escuta", "encerrar escuta",
    )
    val CONFIRMA_SIM = listOf(
        "sim", "pode", "confirmo", "confirmado", "claro", "positivo",
        "manda", "prossiga", "faca", "ok", "beleza", "isso mesmo",
    )
    val CONFIRMA_NAO = listOf(
        "nao", "cancela", "cancelar", "negativo", "esquece", "deixa pra la",
    )

    // Artigos/preposições que NÃO são nome de projeto (evita "projeto do X" -> "do").
    private val STOPWORDS_PROJETO = setOf(
        "do", "da", "de", "dos", "das", "no", "na", "nos", "nas",
        "o", "a", "os", "as", "um", "uma", "meu", "minha", "projeto",
    )

    fun normalizar(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun ehPedidoPc(texto: String): Boolean = GATILHOS_PC.any { normalizar(texto).contains(it) }

    fun ehPedidoParar(texto: String): Boolean = GATILHOS_PARAR.any { normalizar(texto).contains(it) }

    fun ehSim(texto: String): Boolean = CONFIRMA_SIM.any { normalizar(texto).contains(it) }

    fun ehNao(texto: String): Boolean = CONFIRMA_NAO.any { normalizar(texto).contains(it) }

    /** Extrai (de forma simples) o projeto e se é criação, mantendo o texto original. */
    fun interpretar(comando: String): PedidoPc {
        val t = normalizar(comando)
        val novo = Regex("\\b(novo|nova|inicia|iniciar|cria|criar|comeca|comecar)\\b").containsMatchIn(t)
        // Para o NOME do projeto, preserve "_" e "-" (são nomes de pasta): tira só
        // acento e caixa, sem a normalização agressiva que trocaria "_" por espaço
        // (senao "jarvis_personal" viraria "jarvis personal" e so pegaria "jarvis").
        val base = Normalizer.normalize(comando.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        // "projeto do OpenJarvis" / "projeto da casa" -> pula artigos/preposições
        // (do, da, de, no, na, o, a, um, uma, meu, minha) e pega o nome real.
        val projeto =
            Regex(
                "projeto\\s+(?:(?:do|da|de|dos|das|no|na|nos|nas|o|a|os|as|um|uma|meu|minha)\\s+)*" +
                    "([a-z0-9][\\w-]*)",
            ).find(base)?.groupValues?.get(1)
                ?.takeUnless { it in STOPWORDS_PROJETO }
                ?: Regex("\\b([a-z0-9]+(?:[_-][a-z0-9]+)+)\\b").find(base)?.groupValues?.get(1)
        return PedidoPc(projeto, novo, comando)
    }
}
