# Política de privacidad (borrador)

> Borrador técnico, no es asesoramiento legal. Debe revisarlo quien sea responsable del tratamiento de datos de la clínica.

**Qué es Chiara.** Una aplicación para el personal de una clínica: agenda, fichas de pacientes e informes de consulta.

**Quién es responsable.** La clínica que da de alta a sus usuarios es la responsable del tratamiento; la aplicación es una
herramienta suya. El proveedor del servicio (Supabase) actúa como encargado.

## Qué datos trata la aplicación
- Cuenta del profesional: correo, nombre, rol y clínica.
- Datos de pacientes y citas que la clínica ya tiene en su sistema; informes de consulta (solo lectura en el móvil).
- Opcional y local: grabaciones de consultas y sus transcripciones, y los informes redactados a partir de ellas.

## Dónde se guardan
- En el servidor de la clínica (Supabase), con acceso restringido por clínica y rol.
- En el dispositivo: el token de sesión y una caché de las últimas respuestas, cifrados con el Keystore de Android y excluidos de las copias de seguridad.
- Las grabaciones y transcripciones se guardan solo en el dispositivo, visibles únicamente para quien las hizo. Pueden borrarse en Ajustes.

## Qué sale del dispositivo
- Nada, salvo lo anterior, **a menos que la persona usuaria elija** la transcripción o la redacción de informes en la nube (cuenta con clave de IA):
  entonces el audio o el texto de esa consulta se envía a OpenRouter, que lo reenvía a Microsoft (transcripción) o al modelo de lenguaje configurado,
  pidiendo retención cero de datos y sin recogida de datos. La aplicación pide confirmar el consentimiento del paciente antes de cada envío.
- La IA en el dispositivo (asistente, resúmenes) se ejecuta en el teléfono y no envía nada.

## Lo que no hace
Sin analítica, sin publicidad, sin perfiles, sin venta de datos, sin seguimiento entre aplicaciones.

## Derechos y contacto
Las solicitudes de acceso, rectificación o supresión se dirigen a la clínica responsable. **[completar]** datos de contacto y delegado de protección de datos.
