package com.linguareader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
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
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Escuchar palabra")
                }
            }
            val expressionIsSameEntry = lookup.expression?.let { !it.isMultiToken } == true
            if (!expressionIsSameEntry) {
                lookup.wordLemma?.let { Text("Lema: $it", style = MaterialTheme.typography.labelLarge) }
                TranslationLine(lookup.word, card.targetLanguage)
            }

            lookup.expression?.let { match ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${Labels.termKind(match.entry.kind)} · «${match.surface}»",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (match.entry.lemma != match.surface.lowercase()) {
                            Text("Forma base: ${match.entry.lemma}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        lookup.expressionTranslation?.let { TranslationLine(it, card.targetLanguage) }
                    }
                }
            }

            val examples = (lookup.expressionTranslation as? TranslationResult.Translated)?.examples.orEmpty() +
                (lookup.word as? TranslationResult.Translated)?.examples.orEmpty()
            if (examples.isNotEmpty()) {
                Text("Ejemplos", style = MaterialTheme.typography.titleSmall)
                examples.forEach { ex ->
                    Text(ex.text, style = MaterialTheme.typography.bodyMedium)
                    ex.translation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic) }
                }
            }

            HorizontalDivider()
            Text("En la frase", style = MaterialTheme.typography.titleSmall)
            Text(lookup.sentence.text, style = MaterialTheme.typography.bodyMedium)
            TranslationLine(lookup.sentenceTranslation, card.targetLanguage, italic = true)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onSave, enabled = !card.saved) {
                    Icon(painterResource(R.drawable.ic_star), contentDescription = null)
                    Text(if (card.saved) " Guardada" else " Guardar")
                }
                OutlinedButton(onClick = onReadFromHere) { Text("Leer desde aquí") }
            }
            if (card.pausedForCard) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Reanudar lectura") }
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
                Text("Frase", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSpeak(card.text) }) {
                    Icon(painterResource(R.drawable.ic_volume_up), contentDescription = "Escuchar frase")
                }
            }
            Text(card.text, style = MaterialTheme.typography.bodyLarge)
            card.translation?.let { TranslationLine(it, card.targetLanguage, italic = true) }
            if (card.pausedForCard) {
                Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("Reanudar lectura") }
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
    bookLanguage: String?,
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
                selectedVoice = bookLanguage?.let { prefs.voiceByLanguage[LanguageTags.primary(it)] },
                onVoice = onVoice,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    book: Book,
    currentPassageId: String?,
    bookmarks: List<BookmarkEntity>,
    translationLanguage: String,
    onSelectPassage: (String) -> Unit,
    onSelectBookmark: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp)) {
            if (bookmarks.isNotEmpty()) {
                item { Text("Marcadores", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                items(bookmarks, key = { "b${it.id}" }) { b ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSelectBookmark(b) }) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("Pasaje ${b.passageIndex + 1}", style = MaterialTheme.typography.labelMedium)
                            Text(b.excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        }
                        IconButton(onClick = { onDeleteBookmark(b.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = "Borrar marcador")
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            }
            book.chapters.forEach { chapter ->
                item(key = "c-${chapter.id}") {
                    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Text(chapter.title, style = MaterialTheme.typography.titleMedium)
                        LanguageTags.lookup(chapter.titleTranslations, translationLanguage)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                        }
                    }
                }
                items(chapter.passages, key = { "p-${it.id}" }) { p ->
                    val current = p.id == currentPassageId
                    Text(
                        "${p.index + 1}. ${p.sentences.first().text}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clickable { onSelectPassage(p.id) }.padding(vertical = 10.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
