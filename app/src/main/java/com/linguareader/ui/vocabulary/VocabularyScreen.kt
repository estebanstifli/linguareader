package com.linguareader.ui.vocabulary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.linguareader.R
import com.linguareader.data.db.VocabularyDao
import com.linguareader.data.db.VocabularyEntity
import com.linguareader.ui.common.Languages
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VocabularyViewModel @Inject constructor(private val dao: VocabularyDao) : ViewModel() {
    val entries: StateFlow<List<VocabularyEntity>?> = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete(id: Long) = viewModelScope.launch { dao.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(onBack: () -> Unit, vm: VocabularyViewModel = hiltViewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.section_vocabulary)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        val list = entries
        if (list != null && list.isEmpty()) {
            Text(
                stringResource(R.string.vocabulary_empty),
                modifier = Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list.orEmpty(), key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(e.term, style = MaterialTheme.typography.titleMedium)
                            Text(
                                e.translation ?: stringResource(R.string.vocabulary_no_translation, Languages.nameInSentence(e.targetLanguage)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (e.translation == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            )
                            e.lemma?.takeIf { it != e.term }?.let { Text(stringResource(R.string.word_base_form, it), style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.common_quoted, e.context), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                            Text(
                                "${e.bookTitle} · ${Languages.name(e.sourceLanguage)} → ${Languages.name(e.targetLanguage)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.delete(e.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.vocabulary_delete, e.term))
                        }
                    }
                }
            }
        }
    }
}
