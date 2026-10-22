package com.linguareader.core.reader

import com.linguareader.core.model.Passage
import com.linguareader.core.text.PassageBuilder
import com.linguareader.core.text.SentenceInput

/** Páginas de prueba con frases únicas (para comprobar el orden exacto de síntesis y alineación). */
object TestPages {
    fun build(count: Int, sentencesPerPage: Int = 3): List<Passage> = (0 until count).map { page ->
        PassageBuilder.build(
            passageId = "pg$page", chapterId = "c1", globalIndex = page, languageTag = "en",
            sentences = (1..sentencesPerPage).map { s -> SentenceInput("s$s", "Page $page says sentence number $s.", emptyMap()) },
            image = null,
        )
    }
}
