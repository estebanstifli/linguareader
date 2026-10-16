package com.linguareader.core.model

enum class TermKind { WORD, PHRASAL_VERB, EXPRESSION, CONTRACTION }

data class GlossaryExample(
    val text: String,
    val translations: Map<String, String>,
)

/**
 * Entrada del glosario de un libro. [forms] son las formas superficiales que aparecen en el texto
 * (en minúsculas); pueden abarcar varios tokens ("wakes up", "qu'est-ce que").
 */
data class GlossaryEntry(
    val id: String,
    val lemma: String,
    val kind: TermKind,
    val partOfSpeech: String?,
    val forms: List<String>,
    val translations: Map<String, String>,
    val examples: List<GlossaryExample>,
)
