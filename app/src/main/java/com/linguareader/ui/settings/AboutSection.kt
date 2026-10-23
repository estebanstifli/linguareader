package com.linguareader.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.linguareader.BuildConfig
import com.linguareader.R
import com.linguareader.legal.AppLinks
import com.linguareader.legal.ThirdPartyNotice
import com.linguareader.legal.ThirdPartyNotices

/** Ajustes › Acerca de: versión, privacidad, contacto, web y avisos de terceros (licencias). */
@Composable
fun AboutSection() {
    val context = LocalContext.current
    val emailSubject = stringResource(R.string.about_email_subject, stringResource(R.string.app_name), BuildConfig.VERSION_NAME)
    fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // Sin navegador ni app de correo: no hay nada que abrir.
        }
    }
    fun openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LinkRow(R.drawable.ic_visibility, stringResource(R.string.about_privacy_policy), AppLinks.PRIVACY_POLICY) {
            openUrl(AppLinks.PRIVACY_POLICY)
        }
        LinkRow(R.drawable.ic_language, stringResource(R.string.about_website), AppLinks.WEBSITE) { openUrl(AppLinks.WEBSITE) }
        LinkRow(R.drawable.ic_edit, stringResource(R.string.about_contact), AppLinks.CONTACT_EMAIL) {
            open(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(AppLinks.CONTACT_EMAIL))
                    .putExtra(Intent.EXTRA_SUBJECT, emailSubject),
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.about_privacy_summary), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.about_licenses_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.about_licenses_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ThirdPartyNotices.included.forEach { NoticeRow(it, ::openUrl) }
        Text(
            stringResource(R.string.about_licenses_downloadable),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        ThirdPartyNotices.downloadable.forEach { NoticeRow(it, ::openUrl) }
        Text(
            stringResource(R.string.about_content_licenses),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun LinkRow(icon: Int, title: String, detail: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NoticeRow(notice: ThirdPartyNotice, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpen(notice.url) }.padding(vertical = 6.dp)) {
        Text(notice.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${notice.author} · ${notice.license}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
