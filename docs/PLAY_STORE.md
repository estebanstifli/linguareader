# Publicación en Google Play

App: **LingoAudioBook** · `applicationId` **`com.lingoaudiobook.app`** (definitivo: Play no deja cambiarlo) · gratuita,
sin anuncios ni compras · categoría Educación.

Web, ayuda y política de privacidad (repositorio público `estebanstifli/lingoaudiobook`, GitHub Pages):

| Página | URL |
|---|---|
| Inicio (en) | https://estebanstifli.github.io/lingoaudiobook/ |
| Inicio (es) | https://estebanstifli.github.io/lingoaudiobook/es/ |
| **Política de privacidad** (en + es) | https://estebanstifli.github.io/lingoaudiobook/privacy/ |

Contacto público: estebandezafra@gmail.com. La app enlaza las tres cosas en Ajustes › Acerca de (`legal/AppLinks.kt`).

## Estado

| Requisito | Estado |
|---|---|
| `targetSdk` 36 (exigido a apps nuevas desde agosto de 2026) | ✅ |
| Librerías nativas alineadas a 16 KB (exigido desde noviembre de 2025) | ✅ comprobado en el APK arm64 |
| Solo 64 bits (arm64-v8a, x86_64) | ✅ Play filtra los móviles de 32 bits |
| Sin `MANAGE_EXTERNAL_STORAGE` («Acceso a todos los archivos») | ✅ Android 11+: carpetas (`OpenDocumentTree`) y archivos sueltos (`OpenMultipleDocuments`) elegidos por el usuario. Android no deja elegir como carpeta Download ni la raíz: para ellos, «Añadir archivos» |
| Telemetría de ONNX Runtime desactivada | ✅ (`AndroidManifest.xml`) |
| Copia de seguridad solo de base de datos y preferencias (< 25 MB) | ✅ `xml/data_extraction_rules.xml`, `xml/backup_rules.xml` |
| Política de privacidad pública y enlazada en la app | ✅ |
| Pantalla de licencias de terceros | ✅ Ajustes › Acerca de (`legal/ThirdPartyNotices`) |
| Firma de release (clave de subida) | ✅ local, ver «Firma» |
| AAB de release (`bundleRelease`) | ✅ genera y firma (~100 MB con dos ABI; cada móvil descarga solo el suyo) |
| Interfaz en en, es, fr, de, it, pt | ✅ |
| Ficha: textos en 6 idiomas, icono 512, gráfico destacado, capturas en/es | ✅ `fastlane/metadata/android/` |
| Formularios de Play Console | ⬜ hay que rellenarlos (respuestas abajo) |
| Prueba cerrada: 12 testers durante 14 días (cuentas personales nuevas) | ⬜ |
| Probar el AAB de release en un móvil real | ⬜ |
| Reducción de código con R8 | ⬜ opcional; antes hay que añadir reglas (kotlinx.serialization) y probar |

## Firma

- Clave de subida: `C:\Users\Esteban\.android\lingoaudiobook-upload.jks` (PKCS12, alias `upload`, RSA 4096, válida
  hasta 2054). SHA-256 del certificado:
  `F3:B4:5F:34:85:D5:4F:35:30:88:98:84:D6:88:68:13:D8:72:B3:5E:21:4C:D6:08:85:99:66:FA:B0:18:63:BF`.
- Contraseña y ruta en `keystore.properties` (raíz del repo, **fuera de git**). `app/build.gradle.kts` lo lee; sin ese
  fichero la release sale sin firmar.
- **Guardar una copia** del `.jks` y de `keystore.properties` fuera del PC (gestor de contraseñas o disco cifrado).
- En la primera subida, aceptar **Play App Signing**: Google guarda la clave de firma definitiva y esta solo sirve
  para subir. Si se pierde, se pide un restablecimiento de la clave de subida en Play Console (no se pierde la app).

## Generar una versión

1. Subir `versionCode` (y `versionName`) en `app/build.gradle.kts`. Añadir las novedades en
   `fastlane/metadata/android/<idioma>/changelogs/<versionCode>.txt` (≤500 caracteres).
2. Comprobar: `python -I tools/check_hardcoded_strings.py` y
   `./gradlew :app:testDebugUnitTest :app:lintDebug` (con `JAVA_HOME` = JBR de Android Studio).
3. `./gradlew :app:bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`.
4. Play Console › Pruebas › Prueba cerrada › Crear versión › subir el AAB y pegar las novedades.

## Ficha de Play Store

Textos en `fastlane/metadata/android/<idioma>/` (formato de fastlane *supply*, se pueden copiar a mano o subir con
`fastlane supply`): `title.txt` (≤30), `short_description.txt` (≤80), `full_description.txt` (≤4000) y
`changelogs/3.txt`, para en-US (predeterminado), es-ES, fr-FR, de-DE, it-IT y pt-BR.

Gráficos en `images/`: `icon.png` (512×512), `featureGraphic.png` (1024×500, uno por idioma) — se generan con
`python -I tools/make_store_graphics.py` a partir del icono de la app — y `phoneScreenshots/` (en-US: 5, es-ES: 3;
los demás idiomas usan las inglesas). Las capturas se hicieron en el emulador (1080×2400). Faltan capturas de tablet
(opcionales; recomendables porque la app admite tablets).

## Play Console › Contenido de la aplicación: qué responder

**Política de privacidad**: https://estebanstifli.github.io/lingoaudiobook/privacy/

**Acceso a la app**: toda la funcionalidad está disponible sin credenciales ni acceso especial.

**Anuncios**: la app no contiene anuncios.

**Clasificación de contenido (IARC)**: categoría «Referencia, noticias o educación». Sin violencia, sexo, lenguaje
ofensivo, drogas ni apuestas. Sin interacción entre usuarios ni contenido compartido con otros usuarios. Ojo a la
pregunta sobre acceso a contenido de internet sin restricciones: la app importa el texto de cualquier página web
que el usuario indique; contestar con esa realidad (puede subir algo la clasificación).

**Público objetivo**: 13 años o más (13–15, 16–17, 18+). No elegir menores de 13: activaría el programa de Familias,
con requisitos de SDK y de anuncios que no se han revisado. Los cuentos clásicos de la biblioteca pueden hacer que Play
pregunte si la app atrae a niños; la respuesta es que está pensada para estudiantes de idiomas adolescentes y adultos.

**Seguridad de los datos** (Data safety). La app (nosotros) no recoge nada; lo que cuenta es el SDK de **ML Kit**, que
envía a Google diagnósticos y uso ([declaración de Google](https://developers.google.com/ml-kit/android-data-disclosure);
revisarla al rellenar por si cambió):

| Pregunta | Respuesta |
|---|---|
| ¿Recoge o comparte datos de usuario? | Sí (por ML Kit) |
| Tipos | *Información y rendimiento de la app* › Diagnósticos; *Dispositivo u otros identificadores* (identificador de instalación de Firebase que usa ML Kit) |
| ¿Se comparten con terceros? | No (Google los trata como proveedor del SDK) |
| ¿Recogida opcional? | No, es necesaria para el traductor |
| Finalidad | Analíticas (diagnóstico y mejora del SDK) |
| ¿Cifrados en tránsito? | Sí |
| ¿Se pueden eliminar a petición? | No hay cuenta; los datos locales se borran desinstalando |

Las descargas de GitHub y Hugging Face, la importación de páginas web y el motor de voz del sistema no cuentan como
«recogida» por la app (son peticiones iniciadas por el usuario o del sistema), pero están explicadas en la política.

**Permisos y API sensibles**:
- *Servicio en primer plano — Reproducción multimedia* (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`): declarar el uso
  «Seguir leyendo el libro en voz alta con la pantalla apagada, con controles en la notificación; empieza cuando el
  usuario pulsa reproducir». Piden un **vídeo** corto (enlace de YouTube oculto) que lo muestre: abrir un libro,
  reproducir, bloquear la pantalla y seguir oyéndolo, pausar desde la notificación.
- `READ_EXTERNAL_STORAGE` solo hasta Android 10 (`maxSdkVersion=29`): no requiere declaración.

**Otras declaraciones**: no es app de noticias, salud, finanzas, gobierno ni VPN; no usa ID de publicidad
(declarar «No» en la pregunta del ID de publicidad).

## Prueba cerrada (beta)

Si la cuenta de desarrollador es **personal y posterior a noviembre de 2023**, Play exige una prueba cerrada con
**al menos 12 testers inscritos durante 14 días seguidos** antes de poder solicitar producción, y valora que la
usen de verdad.

1. Play Console › Pruebas › Prueba cerrada › crear un canal (p. ej. «beta») y añadir testers por lista de correos o
   con un grupo de Google.
2. Subir el AAB y enviar el enlace de inscripción a los testers (tienen que aceptarlo desde la cuenta de Google con
   la que usan Play).
3. Mantener al menos 12 inscritos los 14 días; recoger comentarios (el correo de contacto o los comentarios privados
   de Play).
4. Después: Panel › Solicitar acceso a producción (preguntan por la prueba y los cambios hechos).

## Pendiente y riesgos conocidos

- **Probar la release** en un móvil real antes de subirla (la versión debug sí se probó en el emulador).
- **Nombre en el lanzador**: «LingoAudioBook» puede cortarse en algunos lanzadores con letra grande; si molesta,
  poner `android:label` de la actividad a un nombre corto (p. ej. «LingoAudio») sin cambiar el de Play.
- **Modelos de alineación FR/ES** con licencia CC BY-NC 4.0 (solo uso no comercial): compatibles con una app gratuita
  sin anuncios; si algún día hay monetización, cambiarlos ([ALIGNMENT.md](ALIGNMENT.md)).
- **Supertonic 3** (OpenRAIL-M): el usuario acepta la licencia en la app antes de descargarlo; el audio se presenta
  como voz sintética.
- **App iOS**: sigue con el nombre LinguaReader; renombrarla cuando se publique en el App Store.
- La URL de la política depende del repositorio `lingoaudiobook`: si se renombra o se mueve, actualizar Play Console
  y `legal/AppLinks.kt` a la vez.
