package com.linguareader.importer.extract

import com.linguareader.core.platform.decodeLatin1
import com.linguareader.importer.DocumentExtractor
import com.linguareader.importer.DocumentSource
import com.linguareader.importer.ExtractedDocument
import com.linguareader.importer.ExtractedImage
import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import kotlin.io.encoding.Base64

/** Página HTML/XHTML guardada en el dispositivo. Las imágenes remotas no se descargan (offline). */
class HtmlExtractor : DocumentExtractor {
    override val id = "html"
    override val extensions = setOf("html", "htm", "xhtml", "xht")

    override fun sniff(source: DocumentSource): Boolean {
        if (!TextDecoding.looksLikeText(source.head)) return false
        val head = source.head.decodeLatin1().trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") ||
            (head.startsWith("<?xml") && "<html" in head)
    }

    override fun extract(source: DocumentSource): ExtractedDocument =
        // charsetName = null: jsoup detecta BOM y <meta charset>, con UTF-8 por defecto.
        fromDocument(Ksoup.parse(TextDecoding.decodeMarkup(source.readBytes()), ""), source.titleFromName)

    /**
     * [webPage]: página descargada de internet. Se quitan menús, cabeceras, pies, barras laterales
     * y comentarios, y se lee solo el contenido principal (estilo "modo lectura").
     * Para un HTML local (un capítulo, un libro guardado) se lee todo el cuerpo.
     */
    fun fromDocument(document: Document, fallbackTitle: String?, webPage: Boolean = false): ExtractedDocument {
        val walker = HtmlContentWalker(resolveImage = ::dataUri)
        val chapters = walker.walk(if (webPage) mainContent(document) else document.body())
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title().takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: fallbackTitle
        return ExtractedDocument(
            format = id,
            title = HtmlContentWalker.clean(title.orEmpty()).ifEmpty { null },
            authors = listOfNotNull(document.selectFirst("meta[name=author]")?.attr("content")?.takeIf { it.isNotBlank() }),
            language = document.selectFirst("html[lang]")?.attr("lang")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("html[xml:lang]")?.attr("xml:lang")?.takeIf { it.isNotBlank() },
            chapters = chapters,
        )
    }

    private fun mainContent(document: Document): Element {
        val body = document.body()
        body.select(BOILERPLATE).remove()
        val total = paragraphChars(body)
        // 1) Marcado semántico del artículo, si concentra la mayor parte del texto.
        document.select("article, main, [role=main], [itemprop=articleBody]")
            .maxByOrNull(::paragraphChars)
            ?.takeIf { paragraphChars(it) * 2 >= total }
            ?.let { return it }
        // 2) Contenedor con más texto en párrafos propios (heurística de Readability simplificada).
        val scores = HashMap<Element, Int>()
        body.select("p").forEach { p ->
            val length = p.text().length
            // Tipos explícitos: jsoup anota parent() con @Nullable de jspecify, que no está en el classpath.
            val parent: Element? = p.parent()
            val grandparent: Element? = parent?.parent()
            if (parent != null) scores[parent] = (scores[parent] ?: 0) + length
            if (grandparent != null) scores[grandparent] = (scores[grandparent] ?: 0) + length / 2
        }
        return scores.maxByOrNull { it.value }?.key?.takeIf { paragraphChars(it) * 2 >= total } ?: body
    }

    private fun paragraphChars(element: Element): Int = element.select("p").sumOf { it.text().length }

    private fun dataUri(src: String): ExtractedImage? {
        if (!src.startsWith("data:image/") || ";base64," !in src) return null
        return runCatching {
            ExtractedImage(Base64.Mime.decode(src.substringAfter(";base64,")), src.substring(5).substringBefore(';'))
        }.getOrNull()
    }

    private companion object {
        const val BOILERPLATE = "nav, header, footer, aside, form, dialog, [role=navigation], [role=banner], " +
            "[role=contentinfo], [role=complementary], [aria-hidden=true], #comments, .comments, .advertisement, " +
            ".share, .social, .related, .newsletter, .cookie, .breadcrumb"
    }
}
