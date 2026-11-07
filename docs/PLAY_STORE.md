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
| Ficha: textos y 6 capturas por idioma, icono 512, gráfico destacado | ✅ `fastlane/metadata/android/` |
| Formularios de Play Console | ✅ enviados (confirmado el 30/10/2026) |
| Prueba cerrada: 12 testers durante 14 días (cuentas personales nuevas) | 🟡 en curso: 5 de 14 días transcurridos a fecha de 30/10/2026; quedan 9 días |
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

También se puede actualizar el canal cerrado existente mediante la API:

```powershell
python tools/play_release.py stage --aab "<AAB firmado>" --code 6 --name 0.4.2 --track Test
python tools/play_release.py commit --transaction "<transaction.json que imprimió stage>"
```

La herramienta admite canales cerrados personalizados ya activos; rechaza producción, pruebas abiertas
e internas. Comprueba el código y SHA-256 del AAB, guarda el estado anterior y valida que solo cambie
el canal solicitado. Las notas se leen de `fastlane/metadata/android/<idioma>/changelogs/<código>.txt`.
El commit no cancela revisiones en curso y se comprueba después mediante una edición nueva.
No ejecutar otra edición API mientras haya una subida preparada pendiente de commit.

### Preparación 0.4.2 (código 6) — 04/11/2026

- Se recompiló el código actual: el AAB anterior era del 30/10/2026 y Play ya utilizaba el código 5.
- `bundleRelease`, `lintRelease`, `testDebugUnitTest` y las suites JVM de core, data y domain correctos:
  **223 pruebas**, sin fallos. Lint: 0 errores, 68 avisos y 4 sugerencias. Comprobador de cadenas correcto.
- Se ajustó `KoinModulesTest` para reconocer únicamente en `SentenceClipPipeline` el sintetizador
  proporcionado explícitamente por `AppModule`; no se añadió una exclusión global ni se cambió el motor.
- Firma JAR comprobada y huella del certificado coincidente con la clave de subida documentada.
- AAB conservado: `artifacts/play-console/release-2026-11-04/LingoAudioBook-0.4.2-6.aab`.
  SHA-256: `20945b101ac751e33afe079e77a21034b0799e61ba54231775239bab14d21149`.
- Novedades en seis idiomas: biblioteca online, fichas de libros, generación de audio y traducción
  con reanudación, mejoras de lectura bilingüe y correcciones de estabilidad/visualización.
- Las pruebas instrumentadas de la biblioteca e importación de portadas del 03/11 terminaron correctamente;
  en esta preparación se ejecutaron compilación, lint y pruebas unitarias, sin repetir la prueba física.
- **Pendiente de publicación:** Google aceptó el archivo en una edición temporal, pero rechazó asignarlo al
  canal con HTTP 403: `You must let us know whether your app uses any Foreground Service permissions.`
  Esa edición se descartó; no se confirmó ningún cambio de versión. La subida simple agotó el tiempo de
  espera; la subida reanudable por bloques sí entregó el AAB y debe usarse en próximos intentos.
- **05/11/2026:** el usuario declaró `MEDIA_PLAYBACK`, añadió un vídeo de YouTube que demuestra reproducción
  con pantalla apagada y envió el formulario a revisión. Solo aparecía el permiso de reproducción.
  Consulta API posterior: el canal `Test` sigue con **0.4.1 (5)** y los bundles registrados son 3, 4 y 5.
  Evidencia: `artifacts/play-console/release-2026-11-04/after-fgs-submission.json`.
- La nueva 0.4.2 también declara `MEDIA_PROCESSING` (generación de audio desde Android 15) y `DATA_SYNC`
  (procesamiento local en versiones anteriores). Falta cargar el AAB nuevo en la interfaz web y completar
  las declaraciones de permisos nuevos que detecte Play. La generación es opcional, iniciada por el usuario,
  muestra progreso y permite detenerla. Su demostración debe mostrar esa función, además de la reproducción.
  No cancelar la revisión actual ni afirmar que el código 6 está disponible para testers.
- **Reintento tras aprobación comunicada por el usuario (05/11/2026):** se volvió a enviar el AAB
  firmado de código 6 mediante subida reanudable. Google aceptó el archivo y su SHA-256, pero
  `edits.tracks.update` volvió a responder HTTP 403 por la declaración de Foreground Service.
  La edición temporal se descartó sin commit: la 0.4.2 sigue sin publicar. Continuar desde Play Console,
  canal cerrado `Test`, creando una versión y cargando el AAB conservado arriba; completar las declaraciones
  que aparezcan para ese bundle. No retirar permisos para evitar el formulario.
  Notas listas para pegar en la consola, con etiquetas de los seis idiomas:
  `artifacts/play-console/release-2026-11-04/PlayConsole-release-notes-0.4.2.txt`.

## Ficha de Play Store

### Conexión local a la API

La variable de entorno de usuario `PLAY_STORE_JSON_KEY` apunta al JSON privado fuera del repositorio.
`fastlane/Appfile` usa esa variable y el paquete `com.lingoaudiobook.app`. Abrir una terminal nueva
después de configurar la variable para que la herede.

Para comprobar acceso y descargar una copia de los textos actuales sin publicar cambios:

```powershell
python tools/play_store.py fetch
```

Requiere los paquetes Python `google-auth`, `requests` y `Pillow`. El comando `fetch` crea una edición temporal,
consulta las fichas y descarta esa edición; no ejecuta commit ni sobrescribe los textos locales de Fastlane.
Las copias se guardan en `artifacts/play-console/<fecha UTC>/`.

Conexión verificada el 02/11/2026 tras activar Google Play Android Developer API. Se descargaron
las fichas de 28 idiomas en `artifacts/play-console/20261102T205002438055Z/`. La edición temporal
se descartó correctamente. Esa comprobación inicial no publicó cambios.

Textos en `fastlane/metadata/android/<idioma>/` (formato de fastlane *supply*, se pueden copiar a mano o subir con
`fastlane supply`): `title.txt` (≤30), `short_description.txt` (≤80), `full_description.txt` (≤4000) y
`changelogs/<versionCode>.txt`. Textos de ficha en los 28 idiomas existentes en Play;
capturas localizadas para en-US (predeterminado), es-ES, fr-FR, de-DE, it-IT y pt-BR.

Gráficos en `images/`: `icon.png` (512×512), `featureGraphic.png` (1024×500, seis idiomas) y
`phoneScreenshots/` (6 por cada uno de esos idiomas). Las nuevas portadas utilizan ilustraciones reales
de la biblioteca, fondo crema y textos verde petróleo. Se regeneran junto con los textos mediante
`python tools/refresh_play_listing_assets.py`; la fuente de textos es `tools/play_listing_copy.json`.
Las composiciones usan pantallas reales del emulador en lienzos de 1080×1920. Faltan capturas de tablet
(opcionales; recomendables porque la app admite tablets).

### Actualizar textos y gráficos mediante API

```powershell
python tools/refresh_play_listing_assets.py
python tools/play_store.py check-local
python tools/play_store.py stage-listing
python tools/play_store.py commit-listing --transaction "<ruta transaction.json que imprimió stage-listing>"
```

`stage-listing` respalda los textos y las imágenes anteriores, conserva los vídeos, sustituye únicamente
los textos y los gráficos de teléfono/destacado seleccionados, y comprueba los hashes y el orden de las
imágenes. No cambia versiones ni canales. `commit-listing` valida otra vez y confirma la edición con
`ERROR_IF_IN_REVIEW`: no cancela una revisión ya en curso. Después comprueba una copia nueva del servidor.
No ejecutar `fetch` ni abrir otra edición API entre `stage-listing` y `commit-listing`.

El estado y las pruebas quedan en `artifacts/play-console/listing-refresh-<fecha UTC>/`.
Una confirmación aceptada por la API no significa que Google haya terminado la revisión ni que la ficha
ya esté visible públicamente. Si un commit devuelve error, consultar `transaction.json` antes de reintentar;
no repetir a ciegas una petición cuyo resultado sea incierto.

Mensaje comercial: **libros bilingües gratis para aprender idiomas**, biblioteca por niveles, audio,
traducción al tocar y lectura personalizada. La gratuidad aparece al inicio de la descripción completa;
el título conserva la marca `LingoAudioBook`. La integración de Project Gutenberg y la nueva web están
en preparación según el usuario: no anunciar todavía miles de libros como disponibles. Actualizar
esa promesa cuando el catálogo esté accesible y se haya comprobado su alcance.

**Subida realizada el 02/11/2026:** la API aceptó el commit de 28 fichas localizadas, 6 gráficos
destacados y 36 capturas de teléfono. Una consulta posterior mediante una edición nueva confirmó
los textos, los hashes y el orden de las imágenes. El vídeo de en-US y todos los canales/versiones
quedaron conservados. Estado local: `committed-and-verified` en
`artifacts/play-console/listing-refresh-20261102T210708288347Z/transaction.json`.
Copias anteriores en esa misma carpeta; prueba posterior en `committed-verification.json`.
La comprobación acredita el contenido guardado en Play Console, no su visibilidad pública ni la
finalización de la revisión de Google.

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

Las descargas de files.lingoaudiobook.eu (bucket R2 de Cloudflare: bibliotecas, voces, modelo Supertonic y narraciones),
la importación de páginas web y el motor de voz del sistema no cuentan como
«recogida» por la app (son peticiones iniciadas por el usuario o del sistema), pero están explicadas en la política.

**Permisos y API sensibles**:
Actualización 06/11/2026: los paquetes descargables ya no vienen de GitHub sino de `files.lingoaudiobook.eu`
(Cloudflare R2, mismo proveedor que el catálogo online). El modelo Supertonic también se entrega desde ese dominio,
conservando la licencia y los hashes del archivo original de Supertone; HF queda como referencia de procedencia.
La política de privacidad debe reflejar también este cambio de proveedor del modelo, publicarse y revisarse junto
a «Seguridad de los datos» antes de la siguiente subida a Play.
Actualización preparada el 03/11/2026 para Library: la app consulta el catálogo propio en Cloudflare
(búsqueda, filtros e identificador del libro) y descarga portadas/EPUB desde Gutenberg. Las peticiones
incluyen la IP y datos técnicos HTTP; no envían progreso, vocabulario ni archivos locales. La política
local en `../lingoaudiobook-site/privacy/index.html` ya describe estas conexiones en inglés y español.
Antes de publicar esta versión en Play, publicar esa política y revisar la declaración de Seguridad de
los datos según la retención real de Cloudflare (búsquedas/interacciones e IP); no se ha modificado
Play Console ni se ha publicado la política desde esta tarea.

- *Servicio en primer plano — Reproducción multimedia* (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`): declarar el uso
  «Seguir leyendo el libro en voz alta con la pantalla apagada, con controles en la notificación; empieza cuando el
  usuario pulsa reproducir». Piden un **vídeo** corto (enlace de YouTube oculto) que lo muestre: abrir un libro,
  reproducir, bloquear la pantalla y seguir oyéndolo, pausar desde la notificación.
- *Servicio en primer plano — Procesamiento multimedia / Sincronización de datos* (`FOREGROUND_SERVICE_MEDIA_PROCESSING`
  en Android 15+ y `FOREGROUND_SERVICE_DATA_SYNC` en anteriores; **pendiente de declarar** en Play Console desde la
  versión con la ficha del libro): «Generar en segundo plano la voz sintética (Supertonic, en el dispositivo) de un libro
  entero cuando el usuario pulsa “Generar audio” en la ficha del libro y acepta seguir con la pantalla apagada; muestra el
  progreso y un botón “Detener” en la notificación». Vídeo: ficha de un libro, «Generar audio», aceptar el diálogo,
  bloquear la pantalla y ver la notificación de progreso; detener desde ella.
- `READ_EXTERNAL_STORAGE` solo hasta Android 10 (`maxSdkVersion=29`): no requiere declaración.

**Otras declaraciones**: no es app de noticias, salud, finanzas, gobierno ni VPN; no usa ID de publicidad
(declarar «No» en la pregunta del ID de publicidad).

## Prueba cerrada (beta)

**Estado actualizado el 30/10/2026:** la prueba ya está en marcha desde hace 5 días y los formularios de Play Console
ya están enviados, según confirmación del usuario. Inicio aproximado: **25/10/2026**; los 14 días se cumplirían el
**08/11/2026**, si la prueba continúa sin interrupciones. Después quedará solicitar el acceso a producción.

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

## Capturas comerciales Android — 02/11/2026

- Serie de **6 capturas por idioma**: en-US, es-ES, fr-FR, de-DE, it-IT y pt-BR.
- Orden: libros bilingües, audio y dedo, traducción al tocar, biblioteca por niveles,
  vocabulario en contexto y lectura cómoda. El idioma nativo coincide con la ficha: inglés → español,
  francés, alemán, italiano o portugués; para en-US, francés → inglés.
- PNG RGB **1080 × 1920**, sin transparencia, menos de 8 MB por imagen. Estética crema,
  verde petróleo y dorado; pantallas reales del emulador, sin redibujar la interfaz.
- Archivos listos: `fastlane/metadata/android/<idioma>/images/phoneScreenshots/`.
- Paquete: `captures/phone/store-2026-11/LingoAudioBook-PlayStore-6-languages.zip`.
- Vistas generales: `captures/phone/store-2026-11/preview-<idioma>.jpg`.
- Originales nuevos: `captures/phone/store-2026-11/raw/`; copia de las capturas anteriores
  de Fastlane: `captures/phone/store-2026-11/previous-fastlane/`.
- Textos editables: `fastlane/metadata/android/<idioma>/screenshot_copy.json`.
- Regenerar: `python tools/make_play_screenshots.py --install` (requiere Pillow).
  Todas las escenas usan ahora su captura propia por idioma, incluida la traducción al tocar
  y la ficha de vocabulario. El generador comprueba los metadatos de idioma de cada original.
- Preparar el emulador: compilar `:app:assembleDebugAndroidTest -PstoreCapture=true`, instalar el
  APK debug y el APK androidTest y ejecutar `python tools/refresh_play_captures.py es-ES --download`.
  Esto descarga los seis modelos reales de ML Kit. El runner especial usa la app y la base reales;
  debe usarse solo en un emulador de capturas, porque cambia preferencias y reinicia el progreso de lectura.
  Capturar: `python tools/refresh_play_captures.py es-ES --capture` (repetir para cada idioma).
- Paginación bilingüe corregida: separación interna **3 dp**, frente a **14 dp** entre parejas.
  Cada frase y su traducción pasan juntas a la página siguiente si no caben. Una pareja más alta
  que una página completa se mantiene en esa página con desplazamiento vertical, conservando el
  tamaño de letra. Pruebas: 9 casos de `PageBuilderTest`, compilación debug y lint correctos.
- Se simplificaron las etiquetas técnicas del reproductor en los seis idiomas. Se mantiene la
  indicación de voz sintética. Validación: `:app:assembleDebug`, `:app:lintDebug` y comprobador
  de cadenas literales. Para distribuir este cambio del reproductor hará falta una nueva versión de app.
- Las seis series se subieron por API el 02/11/2026 junto con las nuevas portadas. Los JPG de
  vista general no se suben. Para futuras sustituciones manuales, seleccionar el idioma y usar
  únicamente los PNG numerados 01–06; comprobar siempre el idioma nativo de las traducciones.

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
