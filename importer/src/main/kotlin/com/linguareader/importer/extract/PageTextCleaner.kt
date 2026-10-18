package com.linguareader.importer.extract

/**
 * Limpia texto extraído página a página (PDF): quita cabeceras y pies que se repiten, números
 * de página, y une párrafos o palabras partidos por el salto de página. Devuelve párrafos
 * separados por línea en blanco, con las líneas de cada párrafo aún cortadas (las une
 * [TextExtractor]).
 */
object PageTextCleaner {

    private val PAGE_NUMBER = Regex(
        "^[\\s\\-–—·|]*((page|página|pagina|seite|pag\\.?|p\\.)\\s*)?(\\d{1,4}|[ivxlcdm]{1,7})(\\s*(of|de|/|von)\\s*\\d{1,4})?[\\s\\-–—·|]*$",
        RegexOption.IGNORE_CASE,
    )
    private val DIGITS = Regex("\\d+")
    private const val EDGE_LINES = 2

    fun clean(pages: List<String>): String {
        val split = pages.map { page -> page.replace("\r\n", "\n").split('\n').map(String::trimEnd) }
        val repeated = repeatedEdgeLines(split)

        val cleanedPages = split.map { lines ->
            val content = lines.indices.filter { lines[it].isNotBlank() }
            val edgeCount = edgeLines(content.size)
            val edges = (content.take(edgeCount) + content.takeLast(edgeCount)).toSet()
            lines.filterIndexed { index, line ->
                index !in edges || (key(line) !in repeated && !PAGE_NUMBER.matches(line.trim()))
            }.joinToString("\n").trim()
        }.filter { it.isNotEmpty() }

        val out = StringBuilder()
        for (page in cleanedPages) {
            if (out.isEmpty()) {
                out.append(page)
                continue
            }
            val last = out.trimEnd().lastOrNull()
            val first = page.firstOrNull()
            when {
                // Palabra partida con guion entre páginas: "conti-" + "nuaba".
                last == '-' && first?.isLowerCase() == true && out.trimEnd().let { it.length > 1 && it[it.length - 2].isLetter() } -> {
                    out.setLength(out.trimEnd().length - 1)
                    out.append(page)
                }
                // El párrafo sigue en la página siguiente.
                last != null && last !in ".!?…:\"»”’)" && first?.isLowerCase() == true -> out.append('\n').append(page)
                else -> out.append("\n\n").append(page)
            }
        }
        return out.toString()
    }

    /** Líneas de borde (primeras/últimas de cada página) que se repiten en muchas páginas: cabeceras y pies. */
    private fun repeatedEdgeLines(pages: List<List<String>>): Set<String> {
        if (pages.size < 4) return emptySet()
        val counts = HashMap<String, Int>()
        pages.forEach { lines ->
            val content = lines.filter(String::isNotBlank)
            val edgeCount = edgeLines(content.size)
            (content.take(edgeCount) + content.takeLast(edgeCount)).map(::key).toSet().forEach { counts.merge(it, 1, Int::plus) }
        }
        val threshold = maxOf(3, (pages.size * 0.3).toInt())
        return counts.filterValues { it >= threshold }.keys
    }

    /** En páginas cortas (poesía, diapositivas) solo la primera y la última línea pueden ser cabecera/pie. */
    private fun edgeLines(contentLines: Int) = if (contentLines > 3) EDGE_LINES else 1

    /** Clave que ignora números (el pie "Capítulo 3 · 47" se repite con otro número en cada página). */
    private fun key(line: String) = line.trim().lowercase().replace(DIGITS, "#")
}
