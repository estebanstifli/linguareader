package com.linguareader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.linguareader.R
import com.linguareader.core.engine.TranslationResult
import com.linguareader.core.engine.VoiceInfo
import com.linguareader.core.model.Book
import com.linguareader.core.model.LanguageTags
import com.linguareader.core.model.UserPreferences
import com.linguareader.data.db.BookmarkEntity
import com.linguareader.engine.translation.MlKitTranslationEngine
import com.linguareader.ui.common.Labels
import com.linguareader.ui.settings.ReaderPreferencesPanel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordCardSheet(
    card: WordCardState,
    onDismiss: () -> Unit,
    onSpeak: (String) -> Unit,
    onSave: () -> Unit,
    onReadFromHere: () -> Unit,
    onResume: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        val lookup = card.lookup
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lookup == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lookup.token.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSpeak(lookup.token.text) }) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = stringResource(R.string.word_listen))
                }
            }
            val expressionIsSameEntry = lookup.expression?.let { !it.isMultiToken } == true
            if (!expressionIsSameEntry) {
                lookup.wordLemma?.let { Text(stringResource(R.string.word_lemma, it), style = MaterialTheme.typography.labelLarge) }
                TranslationLine(lookup.word, card.targetLanguage)
            }

            lookup.expression?.let { match ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${Labels.termKind(match.entry.kind)} · ${stringResource(R.string.common_quoted, match.surface)}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (match.entry.lemma != match.surface.lowercase()) {
                            Text(
                                stringResource(R.string.word_base_form, match.entry.lemma),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        lookup.expressionTranslation?.let { TranslationLine(it, card.targetLanguage) }
                    }
                }
            }

            val examples = (lookup.expressionTranslation as? TranslationResult.Translated)?.examples.orEmpty() +
                (lookup.word as? TranslationResult.Translated)?.examples.orEmpty()
            if (examples.isNotEmpty()) {
                Text(stringResource(R.string.word_examples), style = MaterialTheme.typography.titleSmall)
                examples.forEach { ex ->
                    Text(ex.text, style = MaterialTheme.typography.bodyMedium)
                    ex.translation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.word_in_sentence), style = MaterialTheme.typography.titleSmall)
            Text(lookup.sentence.text, style = MaterialTheme.typography.bodyMedium)
            TranslationLine(lookup.sentenceTranslation, card.targetLanguage, italic = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSave, enabled = !card.saved) {
                    Icon(painterResource(R.drawable.ic_star), contentDescription = null)
                    Text(" " + stringResource(if (card.saved) R.string.word_saved else R.string.common_save))
                }
                OutlinedButton(onClick = onReadFromHere) { Text(stringResource(R.string.word_read_from_here)) }
            }
            if (card.pausedForCard) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reader_resume)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceCardSheet(card: SentenceCardState, onDismiss: () -> Unit, onSpeak: (String) -> Unit, onResume: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sentence_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSpeak(card.text) }) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = stringResource(R.string.sentence_listen))
                }
            }
            Text(card.text, style = MaterialTheme.typography.bodyLarge)
            card.translation?.let { TranslationLine(it, card.targetLanguage, italic = true) }
            if (card.pausedForCard) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.reader_resume)) }
            }
        }
    }
}

@Composable
private fun TranslationLine(result: TranslationResult, target: String, italic: Boolean = false) {
    when (result) {
        is TranslationResult.Translated -> Column {
            Text(
                result.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (italic) FontWeight.Normal else FontWeight.SemiBold,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            )
            result.partOfSpeech?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // Distinguir lo revisado (incluido en el libro) de lo calculado por la máquina.
            if (result.providerId == MlKitTranslationEngine.ID) {
                Text(stringResource(R.string.translation_machine), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is TranslationResult.Unavailable -> Text(
            Labels.unavailable(result.reason, target),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    prefs: UserPreferences,
    voices: List<VoiceInfo>,
    selectedVoice: String?,
    onUpdate: ((UserPreferences) -> UserPreferences) -> Unit,
    onVoice: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            ReaderPreferencesPanel(
                prefs = prefs,
                onUpdate = onUpdate,
                voices = voices,
                selectedVoice = selectedVoice,
                onVoice = onVoice,
            )
        }
    }
}

/**
 * Índice con tres pestañas: capítulos (solo títulos, con su página), marcadores del usuario y vocabulario guardado
 * de este libro.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexSheet(
    book: Book,
    currentChapter: Int?,
    chapterPages: Map<Int, Int>,
    pageOf: (passageIndex: Int, offset: Int) -> Int?,
    bookmarks: List<BookmarkEntity>,
    vocabulary: List<com.linguareader.data.db.VocabularyEntity>,
    translationLanguage: String,
    onSelectChapter: (Int) -> Unit,
    onSelectBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onSelectWord: (com.linguareader.data.db.VocabularyEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).navigationBarsPadding()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf(
                    stringResource(R.string.index_tab_chapters),
                    stringResource(R.string.index_tab_bookmarks, bookmarks.size),
                    stringResource(R.string.index_tab_vocabulary, vocabulary.size),
                ).forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> {
                    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, (currentChapter ?: 0) - 2))
                    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), state = list) {
                        itemsIndexed(book.chapters, key = { _, c -> c.id }) { i, chapter ->
                            val current = i == currentChapter
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable { onSelectChapter(i) }.padding(vertical = 12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        chapter.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                    )
                                    LanguageTags.lookup(chapter.titleTranslations, translationLanguage)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                                    }
                                }
                                chapterPages[i]?.let { page ->
                                    Text("${page + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                1 -> if (bookmarks.isEmpty()) {
                    EmptyTab(stringResource(R.string.index_bookmarks_empty))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        items(bookmarks, key = { it.id }) { b ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSelectBookmark(b) }) {
                                Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                    val chapter = book.passages.getOrNull(b.passageIndex)?.let(book::chapterOf)?.title.orEmpty()
                                    val page = pageOf(b.passageIndex, b.charOffset)?.let { stringResource(R.string.index_page, it + 1) }
                                    Text(listOfNotNull(page, chapter.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(b.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                }
                                IconButton(onClick = { onDeleteBookmark(b.id) }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.index_delete_bookmark))
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                else -> if (vocabulary.isEmpty()) {
                    EmptyTab(stringResource(R.string.index_vocabulary_empty))
                } else {
                    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        items(vocabulary, key = { it.id }) { v ->
                            Column(Modifier.fillMaxWidth().clickable { onSelectWord(v) }.padding(vertical = 10.dp)) {
                                Text(
                                    listOfNotNull(v.term, v.translation).joinToString(" — "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(v.context, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 2)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyTab(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}
