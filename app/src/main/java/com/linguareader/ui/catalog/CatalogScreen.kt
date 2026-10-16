package com.linguareader.ui.catalog

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguareader.R
import com.linguareader.core.model.CefrLevel
import com.linguareader.ui.common.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    onOpenBook: (String) -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenSettings: () -> Unit,
    vm: CatalogViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val covers by vm.covers.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Biblioteca") },
                actions = {
                    IconButton(onClick = onOpenVocabulary) {
                        Icon(painterResource(R.drawable.ic_star), contentDescription = "Vocabulario")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_tune), contentDescription = "Ajustes")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "filters") {
                Filters(
                    readingLanguage = state.readingLanguage,
                    translationLanguage = state.translationLanguage,
                    levels = state.levels,
                    onReadingLanguage = vm::setReadingLanguage,
                    onTranslationLanguage = vm::setTranslationLanguage,
                    onToggleLevel = vm::toggleLevel,
                )
            }
            when {
                state.error != null -> item(key = "error") { Text(state.error!!, color = MaterialTheme.colorScheme.error) }
                !state.loading && state.items.isEmpty() -> item(key = "empty") {
                    Text(
                        "No hay libros con estos filtros.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                else -> items(state.items, key = { it.summary.id }) { item ->
                    BookCard(item, covers[item.summary.id], onClick = { onOpenBook(item.summary.id) })
                }
            }
        }
    }
}

@Composable
private fun Filters(
    readingLanguage: String?,
    translationLanguage: String,
    levels: Set<CefrLevel>,
    onReadingLanguage: (String?) -> Unit,
    onTranslationLanguage: (String) -> Unit,
    onToggleLevel: (CefrLevel) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Idioma del texto", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Languages.readingOptions.forEach { tag ->
                FilterChip(
                    selected = readingLanguage == tag,
                    onClick = { onReadingLanguage(tag) },
                    label = { Text(Languages.name(tag)) },
                )
            }
        }
        Text("Nivel", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CefrLevel.entries.forEach { level ->
                FilterChip(selected = level in levels, onClick = { onToggleLevel(level) }, label = { Text(level.name) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Traducir al", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            var open by remember { mutableStateOf(false) }
            Box {
                AssistChip(onClick = { open = true }, label = { Text(Languages.name(translationLanguage)) })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Languages.translationTargets.forEach { tag ->
                        DropdownMenuItem(text = { Text(Languages.name(tag)) }, onClick = { onTranslationLanguage(tag); open = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCard(item: CatalogItem, cover: ImageBitmap?, onClick: () -> Unit) {
    val s = item.summary
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).semantics(mergeDescendants = true) {
            contentDescription = "${s.title}, ${Languages.name(s.learningLanguage)}, nivel ${s.cefrLevel.name}"
        },
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 7f).background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    "${s.learningLanguage.uppercase()} · ${s.cefrLevel.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .padding(12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                s.description?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val translations = s.translationLanguages.sorted().joinToString(", ") { Languages.name(it) }
                Text(
                    listOfNotNull(
                        Languages.name(s.learningLanguage),
                        s.genre,
                        "≈ ${s.estimatedMinutes} min",
                        "${s.passageCount} pasajes",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (translations.isNotEmpty()) {
                    Text("Traducciones: $translations", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.progress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                    item.progressLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
