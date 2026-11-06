package com.linguareader.ui.languages

import com.linguareader.core.model.UserPreferences
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState
import com.linguareader.engine.packs.VoicePackManager
import com.linguareader.engine.translation.ModelDownload
import com.linguareader.engine.translation.TranslationModelManager

/**
 * Lo que hace falta para aprender un idioma: su biblioteca de lecturas graduadas (si existe), los modelos de
 * traducción entre ese idioma y el nativo, y el paquete de voz (si el motor Supertonic está instalado). Se puede
 * llamar las veces que haga falta: lo ya descargado o en curso no se repite.
 */
class LanguageInstaller(
    private val libraries: LibraryPackManager,
    private val models: TranslationModelManager,
    private val voicePacks: VoicePackManager,
) {
    /** Prepara todos los idiomas de [p]. */
    fun installAll(p: UserPreferences) = p.learningLanguages.forEach { install(it, p) }

    fun install(lang: String, p: UserPreferences) {
        downloadLibrary(lang)
        neededModels(lang, p.translationLanguage).forEach(::downloadModel)
        voicePacks.ensure(lang)
    }

    /** Biblioteca del idioma si la hay y no está (o falló). */
    fun downloadLibrary(lang: String) {
        val state = libraries.states.value[lang]
        if (lang in libraries.catalog.value && (state == null || state is LibraryPackState.NotInstalled || state is LibraryPackState.Failed)) {
            libraries.download(lang)
        }
    }

    fun downloadModel(code: String) {
        if (code !in models.downloaded.value && models.downloads.value[code] != ModelDownload.Running) models.download(code)
    }

    /** Códigos ML Kit entre los que se traduce al aprender [lang]: el suyo y el nativo (nada si son el mismo). */
    fun neededModels(lang: String, native: String): List<String> =
        listOf(lang, native).mapNotNull(models::code).distinct().takeIf { it.size > 1 }.orEmpty()
}
