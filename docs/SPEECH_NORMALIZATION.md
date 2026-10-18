# Normalización de texto antes del TTS

Supertonic (y cualquier motor que genere audio) recibe el texto tal cual: «12», «Dr.», «s. XIX» o
«3,5 %» se leen mal o se omiten, y el alineador no encuentra letras que alinear en «42». La capa de
normalización convierte el texto en una **forma hablada** sin tocar nunca el texto mostrado ni sus
tokens/offsets.

## Modelo

- `SpokenText(spoken, tokenSpoken)`: `spoken` es lo que se envía al TTS; `tokenSpoken[i]` es lo que se
  pronuncia por el token `i` de la frase («cuarenta y dos» para «42»). Así el alineador recibe una
  entrada por token visible y el resaltado cae en el token original. `""` = el token no tiene voz
  propia (GapFiller reparte su tiempo). `null` = no se puede relacionar el habla con los tokens → la
  frase se resalta entera (no se inventan tiempos).
- Puerto `SpeechTextNormalizer` (`core/.../text/speech/`), sustituible por DI. Implementaciones:
  `IdentityNormalizer` (sin reglas), `RuleBasedSpeechNormalizer` (por defecto) y `SwitchableNormalizer`
  (ajuste «Leer números y abreviaturas como palabras»).
- Puerto `NumberSpeller`; `SpellOutNumberSpeller` usa reglas SPELLOUT de ICU/CLDR a través de una
  fábrica: `AndroidIcuNumberSpeller` (app, `android.icu`) e ICU4J solo en las pruebas de JVM.

## Orden de las reglas (`RuleBasedSpeechNormalizer`)

1. Forma hablada del autor (`speech` en la frase del manifiesto): se usa tal cual. Las palabras que
   coinciden con el texto hacen de ancla (subsecuencia común más larga) y lo que queda entre anclas se
   reparte.
2. Léxico del libro (`pronunciations`) y abreviaturas del idioma (`LanguageRules`, solo datos).
3. Horas (`10:30`, `10h30`), ordinales (`1st`, `1.º`, `2.ª`, `1er`, `XIXe`), romanos tras «siglo»,
   decimales y separadores de miles del idioma, `%` y moneda (`€`, `$`, `£`, delante o detrás).
4. Enteros sueltos (años en inglés: `1999` → «nineteen ninety-nine»).
5. La limpieza propia del motor (`SupertonicTextProcessor.preprocess`) sigue en el adaptador del motor.

Reglas propias para `es`, `en` y `fr`; el resto de idiomas solo pasan los enteros a palabras. En
español se aplica la apócope ante sustantivo («veintiún años», «primer piso») salvo ante palabras
que no son lo que se cuenta («el veintiuno de mayo»). El género del sustantivo no se conoce: se usa
el masculino salvo en horas y monedas femeninas. Para casos dudosos, el autor da `speech`.

## Caché y alineación

- La clave del audio (`AudioCacheKey`) incluye el texto hablado: un cambio de reglas solo regenera
  las frases cuyo texto hablado cambia.
- Si `tokenSpoken` difiere de los tokens, la firma de la alineación persistida incluye su hash.
- Los alineadores (`MmsText`, `Wav2Vec2Text`) ya ignoran los espacios: «cuarenta y dos» se alinea como
  una sola palabra que corresponde al token «42». Su interfaz no cambia.

## Android TTS

No se normaliza: el motor del sistema ya lee bien números y abreviaturas, y sus eventos
`onRangeStart` son offsets sobre el texto enviado. Si hiciera falta, `SpokenText` tendría que llevar
también los rangos hablados por token para traducir esos offsets.

## Alternativas descartadas (octubre 2026)

- **`android.icu.text.RuleBasedNumberFormat`**: no es API pública en Android. Se usan las mismas
  reglas mediante `android.icu.text.MessageFormat` (`{0,spellout,%regla}`, API 24+). Como no permite
  listar las reglas y con un nombre desconocido usa en silencio las de por defecto,
  `SpellOutNumberSpeller` detecta qué reglas existen probando con varios números.
- **Gramáticas WFST (NeMo, Thrax/OpenFST, WeTextProcessing) con el NDK**: mejor calidad con texto
  muy «sucio» (fechas, teléfonos, unidades), pero exige C++, gramáticas `.far` por idioma y cubre pocas
  lenguas. Es excesivo para libros graduados; podría añadirse como otro `SpeechTextNormalizer`.
- **Sherpa-onnx**: es un motor TTS completo, no una capa de normalización.
- **doistx-normalize**: solo hace normalización Unicode, que ya hace `java.text.Normalizer`.
