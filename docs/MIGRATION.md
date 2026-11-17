# Migración chiara-app-ios → chiara-app-android

Fuente: `aidx-dev/chiara-app-ios` (SwiftUI, iOS 26). Destino: Kotlin + Jetpack Compose, minSdk 31, `com.chiarasystems.chiara`.
Regla: paridad funcional y visual con iOS. Los tests de iOS (`chiaraTests/*`) son los casos dorados.

Leyenda: `[x]` hecho y verificado · `[~]` hecho pero sin verificar en dispositivo/modelo real · `[ ]` pendiente.

Estado de la verificación: **174 tests unitarios en verde** (`./gradlew test`), APK debug y release (R8) compilan, y el flujo
login → agenda → pacientes → ficha → informes/PDF → ajustes → grabar → asistente se ha ejecutado en un emulador con el backend demo.

Compilar con JDK 17+, p. ej. el JBR de Android Studio: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`.
Strings: `node scripts/xcstrings-to-json.mjs ../chiara-app-ios/chiara/Localizable.xcstrings` regenera `app/src/main/assets/i18n/*.json`
(catálogo iOS + `scripts/android-extra-*.json`); `node scripts/missing-strings.mjs` lista claves usadas sin traducir.

## Módulos
| Módulo | Contenido |
|---|---|
| `core:model` (JVM) | Modelos, fechas, normalizaciones (paciente, cita, informe, usuario) |
| `core:domain` (JVM) | AgendaRules, UuidV7, PatientMatcher, guardas, RuleBasedEngine, DayBriefing, AudioLanguage |
| `core:network` (JVM) | SupabaseClient (GoTrue + RPC, refresh serializado, caché offline), DemoBackend (interceptor) |
| `core:data` (JVM) | Stores (Auth, Patients, Appointments, Reports, Settings, Router/Toast), AssistantStore, ClinicReader, agente JSON de herramientas |
| `core:ai` (JVM) | Plantillas de informe + paridad con escritorio, ReportGenerator, OpenRouterChat, CloudTranscriber, pipeline, workflow |
| `core:designsystem` | Paleta claro/oscuro, componentes, `tr()`/`CalFormat` por CompositionLocal |
| `app` | Pantallas Compose, Keystore, SQLite de grabaciones, grabadora/importador de audio, servicios, MediaPipe |

## Fase 0 – Base
- [x] Repo clonado, rama `migration/ios-port`; Gradle + version catalog; wrapper 8.10.2; módulos
- [x] BuildConfig `SUPABASE_URL`/`SUPABASE_ANON_KEY`/`DEMO_BACKEND` (de `local.properties`, no versionados); debug apunta a `10.0.2.2:54321`
- [x] Manifest con permisos y servicios; `allowBackup=false`; `network_security_config` (cleartext solo debug, solo 10.0.2.2/localhost)
- [x] Icono adaptativo (monograma, capa monochrome) · [x] CI (`.github/workflows/android.yml`) · [x] R8/ProGuard verificado (`assembleRelease`)
- [ ] Hilt (se usa un contenedor manual `AppContainer`), convention plugins · [ ] Fuente Outfit (tampoco está en iOS: usa la del sistema)

## Fase 1 – Dominio puro + tests
- [x] AgendaRules, UuidV7, AppointmentStatus, Dates, Appointment (notes `Tipo — motivo`), Patient, PatientDraft, Report/ReportBuilder/HtmlText, UserProfile
- [x] DayBriefing, PatientMatcher, ClinicalAdviceGuard, GroundingCheck, RuleBasedEngine/FallbackEngine, AudioLanguageResolver, DictationVocabulary
- [x] ReportText (con los casos de `reports_service.rs`), ReportTemplates + ReportPrompts con **paridad byte a byte** con el JSON exportado del escritorio

## Fase 2 – Red / Auth / Datos
- [x] SupabaseClient (headers, refresh con Mutex y persistencia inmediata, 400/401/422 = credenciales, offline→caché), `FileResponseCache` cifrada con `AesGcmCodec` (Keystore)
- [x] RPCs de pacientes, citas, informes, branding; `profiles`/`clinics`; `get_my_ai_config`; explicit nulls
- [x] Stores + `KeystoreSecureStore` + `SettingsStore` + Router/Toast; `DemoBackend` (debug)

## Fase 3 – Design system + shell
- [x] Paleta completa, Surface/Hairline, Avatar, StatusBadge, MenuPicker, GroupedList, FieldRow, OfflineBanner, ErrorNote, GlassSearchField, botones, monograma
- [x] "Glass" = tinte translúcido + borde fino. [ ] Blur real del contenido de detrás (haze/RenderEffect): pendiente, solo estético
- [x] MainShell (4 pestañas, pila por pestaña, toast con "Deshacer", botón asistente), RootScreen, reset de stores al cerrar sesión

## Fase 4 – Pantallas
- [x] Login (validaciones, recuperar contraseña, BiometricPrompt) · [x] Agenda (semana/mes, resumen del día, filas, refresco) · [x] BookingSheet + MonthCalendar
- [x] Pacientes · NewPatientSheet · PatientDetail · [x] Informes por mes · ReportDetail · PDF A4 (`PdfDocument`) + compartir (FileProvider)
- [x] Ajustes: idioma, apariencia, versión, licencias, modelo de IA, experimental (auto-transcribir, grabaciones), cerrar sesión

## Fase 5 – Grabación y pipeline
- [x] Grabadora (`AudioRecord` 16 kHz mono → AAC ADTS, pausa, audio focus, medidor) + servicio en primer plano `microphone`. ADTS en vez de m4a: lo grabado antes de un fallo sigue siendo reproducible
- [x] Importador (cualquier audio/vídeo → AAC) · [x] Almacén SQLite con propietario, audio en `noBackupFilesDir`
- [x] `ConsultationPipeline` (cola, reintentos, estados, notificaciones sin nombres, servicio `dataSync`) — 27 tests de iOS portados
- [x] CloudTranscriber (MAI-Transcribe 2) con troceado por silencios, bisección y diarización — **WAV en vez de FLAC** (sin codificador FLAC en la plataforma; ~9 min por petición)
- [x] OpenRouterChat (fallback de modelos, backoff), ReportGenerator (resumen rodante, 2 pasadas), UserAIConfig (clave solo en memoria)
- [x] UI: grabar, importar, detalle (transcripción, plantilla, informe, compartir), lista, sección en la ficha
- [ ] Verificado en emulador: grabar (AAC, servicio, UI). [~] Importar y la cadena completa con la nube necesitan una cuenta con clave de IA

## Fase 6 – IA en el dispositivo
- [x] `AssistantStore` completo (órdenes, desambiguación, tarjeta de confirmación, deshacer, huecos, datos de contacto) — 28 tests
- [x] `ClinicReader` + herramientas + agente por protocolo JSON (`ToolCallingAgent`) + `LlmIntentEngine` + resumidor — 9 tests con un LLM de mentira
- [x] AssistantSheet (burbujas, tarjetas, dictado con `SpeechRecognizer` y vocabulario de pacientes), botón flotante, deshacer
- [x] Con el reconocedor por reglas el asistente funciona hoy (verificado: "Show me tomorrow's agenda" abre el día siguiente)
- [~] LLM local con MediaPipe LLM Inference (`MediaPipeLlm`): compila, R8 ok, instalación del modelo por selector de archivo en Ajustes. **Nunca ejecutado con un modelo real**: calidad del protocolo JSON sin validar
- [ ] ASR en el dispositivo (sherpa-onnx + Parakeet TDT v3): sin integrar (el AAR no está en Maven Central y pesa ~500 MB). `LocalAsr` es la interfaz; hoy lanza `LocalModelUnavailable` y la transcripción usa la nube (MAI)
- [ ] `AssistantBench` (19 casos) como test instrumentado con un modelo real · [ ] GlowBorder animado del campo del asistente

## Fase 7 – Localización y calidad
- [x] 426 claves × es/en/de/fr (catálogo iOS + extras Android: grabación, notificaciones, IA); cambio de idioma inmediato; "Face ID" → "biometría"
- [x] Tests: 174 (model 16, domain 37, network 11, data 61, ai 49); R8 ok
- [ ] Tests instrumentados de Compose y capturas claro/oscuro · [ ] Revisión de accesibilidad con TalkBack · [ ] Tablets/WindowSizeClass
- [ ] Política Play: declaración de FGS `microphone`/`dataSync`, política de privacidad, firma de release

## Riesgos abiertos
1. Sin tool-calling nativo en Android: protocolo JSON propio; la calidad con un modelo pequeño (Gemma 1–3B) debe medirse con `AssistantBench` antes de fiarse.
2. Parakeet 0.6B en CPU: lento y pesado en gama media; sin integrar. Mientras tanto, la nube (MAI) es el camino de transcripción.
3. APK de ~88 MB (debug) por las libs nativas de MediaPipe, ya limitadas a arm64-v8a y x86_64: valorar app bundle.
4. Datos clínicos en reposo: caché y secretos cifrados con Keystore; las grabaciones y transcripciones locales están en `noBackupFilesDir` **sin cifrar** (como en iOS, protegidas por el sandbox).
5. Diarización por trozos sin reconciliar entre peticiones (limitación heredada de iOS).
6. El backend real no se ha probado: todo el flujo se ha ejecutado contra `DemoBackend`; falta una pasada contra Supabase con RLS.
