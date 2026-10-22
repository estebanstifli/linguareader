package com.linguareader.core.model

object LanguageTags {
    fun primary(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()

    fun sameLanguage(a: String, b: String): Boolean = primary(a) == primary(b)

    /** Busca en un mapa por etiqueta exacta y, si no, por subetiqueta primaria. */
    fun <V> lookup(map: Map<String, V>, tag: String): V? =
        map[tag] ?: map.entries.firstOrNull { sameLanguage(it.key, tag) }?.value
}
