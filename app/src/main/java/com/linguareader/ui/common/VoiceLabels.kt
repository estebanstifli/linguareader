package com.linguareader.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.linguareader.R
import com.linguareader.core.engine.VoiceGender
import com.linguareader.core.engine.VoiceInfo
import java.util.Locale

/** Nombre legible de una voz del sistema: «Voz femenina 2», «Alan»… en vez de «en-us-x-sfg#female_2-local». */
@Composable
fun voiceTitle(v: VoiceInfo): String = when {
    v.named -> v.displayName
    v.gender == VoiceGender.FEMALE -> stringResource(R.string.voice_female_n, v.number)
    v.gender == VoiceGender.MALE -> stringResource(R.string.voice_male_n, v.number)
    else -> stringResource(R.string.voice_n, v.number)
}

/** Detalle: variante del idioma, calidad y si necesita conexión: «English (United States) · High quality · Offline». */
@Composable
fun voiceDetail(v: VoiceInfo): String {
    val locale = Locale.forLanguageTag(v.languageTag).getDisplayName(Locale.getDefault())
    val quality = when {
        v.quality == null -> null
        v.quality!! >= 400 -> stringResource(R.string.voice_quality_high)
        v.quality!! >= 300 -> stringResource(R.string.voice_quality_normal)
        else -> stringResource(R.string.voice_quality_low)
    }
    val network = stringResource(if (v.requiresNetwork) R.string.voice_needs_network else R.string.voice_offline)
    val gender = if (v.named) when (v.gender) {
        VoiceGender.FEMALE -> stringResource(R.string.voice_gender_female)
        VoiceGender.MALE -> stringResource(R.string.voice_gender_male)
        VoiceGender.UNKNOWN -> null
    } else null
    return listOfNotNull(locale, gender, quality, network).joinToString(" · ")
}
