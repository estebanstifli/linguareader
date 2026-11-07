package com.linguareader.shared

import com.linguareader.core.model.UserPreferences
import com.linguareader.engine.packs.LibraryPackManager
import com.linguareader.engine.packs.LibraryPackState

/**
 * Lo que hace falta para aprender un idioma, como `LanguageInstaller` en Android: su biblioteca de lecturas graduadas
 * (si existe) y los modelos de traducción entre ese idioma y el nativo. Se puede llamar las veces que haga falta: lo ya
 * descargado o en curso no se repite. (El paquete de voces llegará a iOS con Supertonic.)
 */
internal class IosLanguageInstaller(private val libraries: LibraryPackManager, private val models: IosTranslationModels?) {
    suspend fun installAll(p: UserPreferences) = p.learningLanguages.forEach { install(it, p) }

    suspend fun install(lang: String, p: UserPreferences) {
        downloadLibrary(lang)
        models?.missingFor(lang, p.translationLanguage)?.forEach { models.download(it) }
    }

    /** Biblioteca del idioma si la hay y no está (o falló). */
    fun downloadLibrary(lang: String) {
        val state = libraries.states.value[lang]
        if (lang in libraries.catalog.value && (state == null || state is LibraryPackState.NotInstalled || state is LibraryPackState.Failed)) {
            libraries.download(lang)
        }
    }
}
