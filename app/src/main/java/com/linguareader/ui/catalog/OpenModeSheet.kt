package com.linguareader.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linguareader.R
import com.linguareader.core.model.OriginalOpenMode

/**
 * Al pulsar un documento con visor propio (PDF): leerlo como texto (voz, resaltado, traducción) o
 * verlo con su formato original. La elección puede recordarse (se cambia en Ajustes › Archivos).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenModeSheet(item: CatalogItem, onChoose: (mode: OriginalOpenMode, remember: Boolean) -> Unit, onDismiss: () -> Unit) {
    var keep by remember { mutableStateOf(false) }
    val format = item.original?.format?.uppercase() ?: "PDF"
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                item.summary.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            ModeRow(
                icon = R.drawable.ic_record_voice_over,
                title = stringResource(R.string.open_mode_reader),
                detail = stringResource(R.string.open_mode_reader_detail),
                onClick = { onChoose(OriginalOpenMode.READER, keep) },
            )
            ModeRow(
                icon = R.drawable.ic_find_in_page,
                title = stringResource(R.string.catalog_view_original, format),
                detail = stringResource(R.string.open_mode_original_detail),
                onClick = { onChoose(OriginalOpenMode.ORIGINAL, keep) },
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it })
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = keep, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(stringResource(R.string.open_mode_remember, format), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ModeRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
