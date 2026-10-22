package com.linguareader.ui.common

import com.linguareader.R
import com.linguareader.engine.packs.PackError
import com.linguareader.engine.translation.TranslationError

/** Texto de un fallo tipado de descarga o instalación (código compartido → recursos de Android). */
fun PackError.toUiText(): UiText = when (this) {
    is PackError.FolderInaccessible -> UiText.res(R.string.model_folder_inaccessible, detail)
    PackError.FolderCreateFailed -> UiText.res(R.string.model_folder_create_failed)
    is PackError.NoSpace -> UiText.res(R.string.model_no_space, neededMb, freeMb)
    is PackError.DownloadInterruptedResumable -> UiText.res(R.string.model_download_interrupted_resumable, detail)
    is PackError.DownloadInterrupted -> UiText.res(R.string.model_download_interrupted, detail)
    is PackError.DownloadError -> UiText.res(R.string.model_download_error, detail)
    is PackError.FilesCorrupt -> UiText.res(R.string.model_files_corrupt, files)
    is PackError.VerifyError -> UiText.res(R.string.model_verify_error, detail)
    PackError.RepoNotConfigured -> UiText.res(R.string.model_repo_not_configured)
    PackError.VoicePackCatalogUnavailable -> UiText.res(R.string.voice_pack_catalog_unavailable)
    is PackError.LibraryFailed -> UiText.res(R.string.library_failed, detail)
}

/** Texto de un fallo tipado de pretraducción. */
fun TranslationError.toUiText(): UiText = when (this) {
    is TranslationError.Failed -> UiText.res(R.string.book_translation_failed, detail)
    TranslationError.PairUnsupported -> UiText.res(R.string.book_translation_pair_unsupported)
    is TranslationError.ModelFailed -> UiText.res(R.string.book_translation_model_failed, code)
    is TranslationError.Partial -> UiText.res(R.string.book_translation_partial, failures, total)
}
