# Publicación en Google Play: lista de comprobación

Borrador técnico para rellenar la ficha y el formulario de seguridad de datos. Lo que pide una cuenta de desarrollador o una
decisión legal está marcado con **[tuyo]**.

## Compilar la release
1. Crea `keystore.properties` en la raíz (ignorado por git) con `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.
2. En `local.properties`: `SUPABASE_URL`, `SUPABASE_ANON_KEY` (la release nunca usa el backend demo).
3. `./gradlew :app:bundleRelease` (AAB para Play) o `:app:assembleRelease`. R8 está activo; las reglas están en `app/proguard-rules.pro`.
4. Sube el AAB a una pista interna antes de producción. **[tuyo]**: cuenta de Play, firma de Play App Signing.

## Permisos y servicios en primer plano
| Permiso / servicio | Para qué | Cuándo se pide |
|---|---|---|
| `INTERNET` | Supabase y, si la cuenta tiene IA en la nube, OpenRouter | siempre |
| `RECORD_AUDIO` | Grabar la consulta y dictar al asistente | al pulsar "Empezar a grabar" / el micrófono del asistente |
| `USE_BIOMETRIC` | Reabrir la sesión con huella o rostro | al activarlo en el login |
| `POST_NOTIFICATIONS` | Avisar de que una consulta está lista (sin nombres de paciente) | al empezar a grabar |
| `FOREGROUND_SERVICE_MICROPHONE` (`RecordingService`) | Que la grabación siga con la pantalla bloqueada | solo mientras se graba |
| `FOREGROUND_SERVICE_DATA_SYNC` (`PipelineService`) | Transcribir y redactar informes en segundo plano con una notificación de progreso | solo mientras hay trabajo en la cola |

Declaración de servicios en primer plano (formulario de Play): tipo *microphone* = grabación de consultas iniciada por la
persona usuaria; tipo *dataSync* = procesado (transcripción/informe) de esas grabaciones, iniciado con la app en primer plano.
Ambos terminan al acabar la tarea y muestran una notificación visible.

## Seguridad de los datos (borrador del formulario)
- **Datos clínicos** (pacientes, citas, informes): vienen de Supabase del propio cliente; la app no los envía a terceros salvo lo indicado abajo.
- **Cifrado en tránsito**: HTTPS en release (`network_security_config`: el tráfico en claro solo se permite en builds debug hacia 10.0.2.2/localhost).
- **En reposo**: el token de refresco y la caché de respuestas se cifran con una clave AES-GCM del Android Keystore; `allowBackup=false`.
  Las grabaciones y transcripciones se guardan solo en el dispositivo (`noBackupFilesDir`), sin sincronizarse.
- **Sin analítica ni publicidad**: la app no incluye SDKs de analítica, de publicidad ni de informes de fallos.
- **IA en la nube (opcional, solo si la cuenta tiene clave)**: el audio o el texto de una consulta se envía a OpenRouter
  (MAI-Transcribe 2 de Microsoft; modelos de redacción de informes) con `provider.zdr = true` y `data_collection = deny`,
  y solo cuando la persona usuaria lo elige y confirma el consentimiento del paciente.
- **IA en el dispositivo**: el modelo de lenguaje elegido en Ajustes se ejecuta en el teléfono; lo que lee no sale de él.
- **Eliminación**: Ajustes → "Borrar todas las consultas grabadas"; cerrar sesión borra la caché y los tokens (las grabaciones se conservan, como en iOS, y solo las ve quien las hizo).

## Ficha y política
- Categoría sugerida: *Medicina*. Público objetivo: profesionales sanitarios (no niños). **[tuyo]**
- Política de privacidad publicada en una URL: ver `docs/PRIVACY.md` como base. **[tuyo]** revisión legal (RGPD, datos de salud).
- Declaración de "app de salud": gestión de citas y notas clínicas para clínicas; no es un producto sanitario ni da diagnóstico.
  El asistente se niega a sugerir diagnósticos o tratamientos (`ClinicalAdviceGuard`). **[tuyo]** confirmar el encuadre regulatorio.
- Licencias de terceros: pantalla Ajustes → "Licencias de terceros" (`app/src/main/assets/third_party_notices.txt`).
