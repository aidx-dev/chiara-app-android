# Migración chiara-app-ios → chiara-app-android

Fuente: `aidx-dev/chiara-app-ios` (SwiftUI, iOS 26). Destino: Kotlin + Jetpack Compose, minSdk 31, `com.chiarasystems.chiara`.
Regla: paridad funcional y visual con iOS. Los tests de iOS (`chiaraTests/*`) son los casos dorados.

Leyenda: `[x]` hecho · `[ ]` pendiente.

## Fase 0 – Base
- [x] Repo clonado, rama `migration/ios-port`
- [x] Gradle + version catalog (AGP 8.7.3, Kotlin 2.0.21, Compose BOM)
- [x] Módulos iniciales: `app`, `core:model`, `core:domain`, `core:designsystem`
- [x] BuildConfig `SUPABASE_URL`/`SUPABASE_ANON_KEY` por buildType (desde `local.properties`/`secrets.properties`, no versionados)
- [x] `./gradlew :app:assembleDebug` compila (APK debug) y `:core:*:test` en verde
- [x] Manifest con permisos (INTERNET, RECORD_AUDIO, USE_BIOMETRIC, POST_NOTIFICATIONS, FGS microphone/dataSync), `allowBackup=false`
- [x] Gradle wrapper 8.10.2 (compilar con JDK 17+, p. ej. el JBR de Android Studio: `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"`)
- [ ] Hilt, convention plugins (`build-logic`), CI
- [ ] `network_security_config` (cleartext solo debug, para `10.0.2.2:54321`)
- [ ] Icono adaptativo (desde `docs/design_handoff_chiara_ios/assets/app-icon.svg`, capa monochrome)
- [ ] Fuente Outfit SemiBold (wordmark)

## Fase 1 – Dominio puro + tests (copiar casos de `chiaraTests`)
- [x] `AgendaRules` (+ test) · [x] `UuidV7` (+ test) · [x] `AppointmentStatus.normalize`
- [x] `Dates` (parseo ISO, lunes, minutos, `ServerTimestamp`) · [ ] formatos de visualización por idioma (capa UI)
- [x] `Appointment` + notes `Tipo — motivo` (U+2014), `VisitType`, `PatientAppointment`
- [x] `Patient` (edad 0–130, sexo, tint por bytes UUID), `PatientDraft`
- [x] `Report`, `ReportBuilder`, `HtmlText.plain`, `IcdCode`, `UserProfile`, `ClinicBranding`
- [x] `DayBriefing`, `PatientMatcher`, `ClinicalAdviceGuard`, `GroundingCheck`, `RuleBasedEngine`/`AssistantIntent`/`FallbackEngine`, `AudioLanguageResolver`, `DictationVocabulary` (tests de iOS portados: 37 en `core:domain`, 16 en `core:model`)
- [ ] Pendiente de fase 1: `ReportText`, `ReportPrompts`/`ReportTemplates` (se hacen con la Fase 5)

## Fase 2 – Red / Auth / Datos (`core:network`, `core:data`)
- [x] `SupabaseClient` (módulo JVM `core:network`, OkHttp): GoTrue (password, refresh, recover, logout), RPC, refresh con `Mutex` (tokens rotan), margen 60 s, reintento tras 401, 400/401/422 ⇒ `InvalidCredentials`, offline ⇒ caché; 11 tests con MockWebServer
- [x] Claims JWT (`app_metadata.clinic_id/clinic_role`)
- [x] `FileResponseCache` (SHA-256 de `clinic|rpc|body`, atómica, limpiar al salir) con `ByteCodec` · [ ] codec AES-GCM con Keystore (capa `app`)
- [x] Interfaz `SecureStore` · [ ] implementación Keystore/EncryptedSharedPreferences (capa `app`)
- [x] RPCs (contrato verificado con MockWebServer): `list_patients`, `get_patient`, `create_patient`, `update_patient`, `list_appointments_in_range`, `create_appointment_with_patient`, `update_appointment`, `archive_appointment`, `patient_appointment_history`, `list_consultations_with_latest_report`, `get_clinic_branding`; REST `profiles`, `clinics` · [ ] `get_my_ai_config` (Fase 5)
- [x] JSON: `@SerialName` snake_case, nulls explícitos en RPC (`rpcParams`), `ignoreUnknownKeys`
- [x] Stores en `core:data` (18 tests): `AuthStore` (+ `BiometricOutcome`), `PatientsStore` (paginación 100, búsqueda 300 ms, DNI duplicado, UUIDv7), `AppointmentsStore` (`revision`, undo), `ReportsStore` (concurrencia 6)
- [ ] Stores pendientes: Settings (DataStore: idioma, apariencia), Router/Toast (con la capa UI, Fase 3)
- [x] `TestEnv` (MockWebServer) para tests · [ ] `DemoBackend` de debug con los datos de iOS (`-demoBackend`)

## Fase 3 – Design system + shell
- [x] Palette claro/oscuro base (resto de roles pendiente)
- [ ] Surface/Hairline, glass (blur), Avatar, StatusBadge, MenuPicker, GroupedList, FieldRow, OfflineBanner, ErrorNote, GlassSearchField, botones
- [ ] Monograma Chiara (Canvas/VectorDrawable)
- [ ] MainShell: 4 pestañas, NavHost por pestaña, toast con "Deshacer" (2.8 s / 8 s), FAB asistente, RootView + reset de stores al cerrar sesión

## Fase 4 – Pantallas
- [ ] Login (validaciones, recuperar contraseña, BiometricPrompt)
- [ ] Agenda (semana/mes expandible, briefing, filas, pull-to-refresh) · BookingSheet · MonthCalendar
- [ ] Pacientes · NewPatientSheet · PatientDetail
- [ ] Informes (por mes) · ReportDetail · PDF A4 (`PdfDocument`) + compartir (`FileProvider`)
- [ ] Ajustes (idioma, apariencia, versión, avisos legales, borrar grabaciones, cerrar sesión)

## Fase 5 – Grabación y pipeline
- [ ] Servicio de grabación (`AudioRecord` 16 kHz mono → AAC/m4a, FGS microphone, pausa, audio focus, cambio de ruta, checkpoint 20 s)
- [ ] Importador de audio (SAF → mono 16 kHz AAC)
- [ ] Room `RecordingStore` (owner, adoptar huérfanas), audio en `noBackupFilesDir`
- [ ] `ConsultationPipeline` (queued→transcribing→reviewing→writingReport→done, 3 intentos) con WorkManager + `ForegroundInfo`, notificaciones sin nombres
- [ ] `CloudTranscriber` (OpenRouter MAI-Transcribe 2; FLAC/WAV, trozos por silencio, bisección, reintentos), `OpenRouterChat` (fallback de modelos, backoff), `UserAIConfig` (clave solo en memoria)
- [ ] `ReportGenerator`/`RollingSummary`/`ReportText`/`ReportTemplate` + `ReportTemplates.json` y test de paridad byte a byte
- [ ] UI: Record, Import, RecordingDetail, lista con swipe

## Fase 6 – IA on-device (paridad con iOS)
- [ ] `Transcriber`: sherpa-onnx + Parakeet TDT 0.6B v3 int8 (gating RAM, descarga ~500 MB)
- [ ] Dictado streaming con hotwords (nombres de pacientes)
- [ ] LLM local + extracción estructurada (JSON validado / GBNF) tras `AssistantEngine`; `FallbackEngine` con `RuleBasedEngine`
- [ ] Agente + herramientas (`getAgenda`, `findPatients`, `getPatientSummary`, `getPatientReports`, `searchReports`, `proposeChange`), `ClinicReader`, `PatientSummarizer`
- [ ] `AssistantStore` (proponer → confirmar → deshacer), `AssistantSheet`, `GlowBorder`
- [ ] `AssistantBench` (19 casos) como test instrumentado

## Fase 7 – Localización y calidad
- [ ] `scripts/xcstrings-to-android.py` (271 claves × es/en/de/fr; `%@`→`%s`, `%lld`→`%d`)
- [ ] Idioma in-app (`setApplicationLocales`, `locales_config.xml`)
- [ ] Tests: JUnit, MockK, Turbine, coroutines-test, MockWebServer, Room in-memory, Compose UI, capturas claro/oscuro
- [ ] ThirdPartyNotices (Parakeet CC-BY-4.0, sherpa-onnx, ONNX Runtime, modelo LLM)
- [ ] R8/ProGuard, accesibilidad, política Play (FGS), tablets (opcional)

## Riesgos abiertos
1. Sin tool-calling nativo en Android: protocolo JSON propio; validar con `AssistantBench`.
2. Parakeet 0.6B en CPU: lento y pesado en gama media; decidir umbral de RAM y fallback a MAI cloud.
3. Codificación FLAC con `MediaCodec` no garantizada: elegir WAV (≈8 min/trozo) o libFLAC.
4. Datos clínicos en reposo: Room/ficheros cifrados, backups desactivados.
5. Diarización por trozos sin reconciliar entre peticiones (limitación heredada de iOS).
