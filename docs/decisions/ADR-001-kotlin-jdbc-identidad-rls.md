# ADR-001: Backend Kotlin con JDBC, identidad Supabase Auth y RLS en PostgreSQL

- **Estado:** arquitectura aceptada para la plataforma. La implementación y las pruebas de seguridad permanecen pendientes.
- **Fecha:** 2026-11-22
- **Contexto:** Android y la API local SQLite mantienen datos independientes. El backend SaaS tendrá un arranque separado. El diagnóstico y las dependencias se detallan en el [plan](../implementation-plan.md).

## Decisión

### 1. Distribución de responsabilidades

- **Android:** Kotlin + Jetpack Compose; consume HTTPS del backend y usa Supabase Auth para login/sesión. No se conecta directamente a PostgreSQL/JDBC.
- **Web:** React + TypeScript, con el mismo contrato REST e identidad que Android.
- **Backend:** Kotlin + Ktor con endpoints REST versionados, validación de JWT, casos de uso y autorización de negocio.
- **Base de datos:** PostgreSQL en Supabase. JDBC directo mediante pgJDBC y HikariCP en el servidor, transacciones explícitas y migraciones SQL versionadas con Flyway. Autorización en el backend y segunda comprobación mediante RLS en PostgreSQL.
- **Identidad:** Supabase Auth, una identidad `auth.users.id` UUID global para Android y web. `profiles` replica únicamente atributos de dominio no secretos; contraseñas y sesiones son responsabilidad de Auth.
- **Recursos multimedia:** referencias en PostgreSQL; almacenamiento externo en bucket privado o proveedor compatible y URLs temporales; no incrustar vídeos en filas.

### 2. Autenticación y autorización

1. El cliente inicia sesión en Supabase Auth y recibe un **access token**.
2. El cliente llama a Ktor con `Authorization: Bearer <access_token>`.
3. Ktor valida criptográficamente firma, algoritmo permitido, `iss`, `aud`, `exp`, `nbf` cuando exista, y `sub` UUID; obtiene claves desde JWKS con caché y rotación si el proyecto utiliza firma asimétrica. Si el proyecto utiliza firma simétrica, **no se implementará una validación JWT casera**: se deberá migrar a claves asimétricas o definir y verificar una integración validada con Auth antes de producción.
4. Ktor construye un **principal inmutable** con `userId` validado. Los roles de empresa, autorizaciones de curso y administración global se resuelven en el servidor/BD, no desde campos manipulables del cliente (`user_metadata`, `company_id` del body, etc.).
5. Cada caso de uso comprueba autorización de dominio y PostgreSQL aplica RLS como segunda barrera. No se concede acceso por el solo hecho de poseer un UUID, código de invitación o link de vídeo.
6. Un único administrador global inicial se identifica por el par emisor configurado y UUID verificado de Supabase Auth. La configuración protegida admite un solo UUID o deshabilita la capacidad. El alta de empresa exige autenticación reforzada y designación atómica del primer administrador. Véase el [procedimiento de plataforma](../security-model.md#administración-de-plataforma).

### 3. Rol JDBC real y contexto de RLS

- Crear rol PostgreSQL `app_runtime` con `LOGIN`, **sin** `SUPERUSER`, `BYPASSRLS`, `CREATEROLE` ni propiedad de tablas. No heredar roles privilegiados. Conceder solo `USAGE` en esquemas necesarios y permisos exactos por tabla/operación.
- Un rol separado de migraciones crea objetos y políticas. El runtime nunca usa credenciales de `postgres` o `service_role`.
- Activar RLS en todas las tablas privadas. Preferir `FORCE ROW LEVEL SECURITY` como protección adicional cuando resulte apropiado (no sustituye separar propietario y runtime).
- Cada operación protegida se ejecuta dentro de **una transacción**. Tras validar el JWT, el servidor establece un valor **local a la transacción** (p.ej. `SELECT set_config('app.user_id', ?, true)` con parámetro del `sub` validado). Las funciones de las políticas obtienen `nullif(current_setting('app.user_id',true),'')::uuid`; sin valor válido, política **deniega por defecto**.
- **Importante:** `app.user_id` es un contexto establecido por un backend confiable, **no** una afirmación criptográficamente segura en PostgreSQL: un actor que pueda ejecutar SQL arbitrario como `app_runtime` podría establecerlo. Evitar SQL arbitrario/SQL injection, usar parámetros, privilegios mínimos y limitar la superficie SQL. La seguridad de frontera de identidad depende de la verificación en Ktor.
- No usar `SET` de sesión para identidad ni confiar en estado de conexión reciclada. Hacer `BEGIN` → `set_config(..., true)` → consultas → `COMMIT/ROLLBACK`; jamás ejecutar una consulta protegida fuera de ese flujo. Si se usa pool en modo transacción, mantener todo dentro de una única transacción y validar compatibilidad con sentencias preparadas/JDBC.
- No asumir que `auth.uid()` funciona automáticamente bajo una conexión JDBC personalizada: esa función depende del contexto JWT establecido por Supabase API. Para esta arquitectura usar **una función/política explícita del contexto `app.user_id`** y probarla con el rol real; no mezclar convenciones sin test.
- No permitir que un parámetro `companyId` de una petición marque por sí solo la empresa activa. El servidor resuelve pertenencia y las políticas verifican vínculos por `userId` y `company_id`. Funciones `SECURITY DEFINER`, si se usan, deben tener propósito acotado, `search_path` fijo y tests de escalada de privilegios.

### 4. Modelo de permisos

- Una identidad puede ser miembro de varias empresas, pero roles empresariales solo valen dentro de su tenant.
- Administradores de empresa pueden administrar miembros, branding y asignaciones según permisos; no editar cursos automáticamente ni leer chats privados por su cargo.
- Formadores autorizados pueden crear/editar/publicar cursos de su empresa y gestionar contenidos de los cursos asignados; un curso admite varios formadores.
- Catálogo **público**: lectura de **proyección de metadatos publicados**, no lectura universal de tablas protegidas. Curso privado solo accesible por redención segura de código/enlace y autorización/inscripción; una invitación no salta la compra si la política del curso la exige.
- Cursos **públicos gratuitos** permiten matrícula directa autenticada sin orden; los cursos públicos de pago requieren compra simulada completada.
- Una invitación privada se emite con una modalidad: `complimentary` concede matrícula sin orden (regalo/promoción) o `simulated_purchase` requiere compra simulada completada antes de conceder matrícula. Validar expiración, revocación y cupos en servidor; un código por sí solo no concede lectura de contenido.
- Administradores de empresa tienen lectura de estadísticas agregadas y de progreso/calificaciones individuales **solo de alumnos inscritos en cursos de esa empresa**; sin derecho de edición de calificaciones ni acceso a chats privados.
- Las inscripciones, compras simuladas y progreso se escriben en casos de uso transaccionales, con comprobaciones de existencia, pertenencia al curso e idempotencia; clientes no actualizan directamente estados privilegiados.
- Comunidades solo para alumnos inscritos y formadores asignados; conversaciones privadas solo para participantes permitidos. La autorización se comprueba tanto en consultas como en WebSockets/eventos futuros.
- Los permisos concretos y excepciones se fijan en `docs/domain-model.md` y deberán traducirse a casos de prueba.

### 5. Capa pública vs privada

El marketplace requiere datos visibles entre empresas: catálogo de cursos publicados **explícitamente públicos**. Esta publicación no convierte tablas tenant-scoped en accesibles globalmente. Usar endpoint de catálogo con selección de columnas, filtrado por `published`+`public` y consultas preparadas, sin exponer matrículas, datos de empleados, vídeos privados ni conversaciones. Es una excepción de lectura deliberada y documentada.

### 6. Migraciones y despliegue

- Flyway aplicará migraciones versionadas con la credencial independiente de migración. El arranque del backend ordinario solo tendrá acceso a su credencial de ejecución.
- Primera migración objetivo: `profiles`, `companies`, `company_memberships`; inspeccionar primero `Community` existente y dependencias. No ejecutar `DROP` ni cambiar datos heredados sin reporte y plan de rollback.
- Cada migración establece columnas, restricciones, claves foráneas, índices, grants y RLS/policies. Por defecto, sin política permisiva = sin acceso, aun cuando exista un grant SQL.
- Para desarrollo local: PostgreSQL con esquema de autenticación de prueba o Supabase local para reproducir `auth.users`; datos semilla de empresas A y B y varios usuarios. Las pruebas no deben depender de una conexión de administrador con bypass RLS.
- El despliegue gratuito no está garantizado: Supabase Free es apropiado para la BD de prototipo dentro de cuotas, pero alojar Ktor siempre activo, medios y correo puede requerir recursos adicionales. Se puede ejecutar el backend localmente en la fase académica.

## Alternativas consideradas

| Alternativa | Razón para no elegirla ahora |
|---|---|
| Android/web conectando a Supabase Data API directamente | Ahorra backend pero dispersa la lógica de compras simuladas, permisos complejos y transacciones entre API y funciones. |
| JDBC desde Android | Expondría conexión/credenciales y complica protección de datos. Rechazada. |
| Backend Kotlin con credenciales `postgres`/`service_role` para consultas normales | Puede eludir RLS; imposibilita una prueba realista de aislamiento por fila. Rechazada. |
| Una BD por empresa | Aislamiento fuerte pero mayor coste operativo y migraciones múltiples para un proyecto académico. |
| Roles solo en JWT | Pueden quedar desactualizados y no bastan para permisos por curso/empresa. Fuente de verdad en BD. |
| Únicamente lógica de autorización en Ktor sin RLS | Un error de consulta puede filtrar datos de otro tenant; se desea defensa en profundidad. |
| Spring Boot | Se ha elegido Ktor para el nuevo adaptador HTTP. |

## Consecuencias

**Positivas:** lógica reutilizable entre web y Android; modelo SQL normalizado; separación de identidades y roles; aislamiento verificable; capacidad de ampliar cursos, marketplace, mensajería y progreso.

**Costes/riesgos:** mantener y alojar backend; rotación de JWKS y gestión de tokens; migraciones y pruebas de RLS más complejas; posible desajuste entre seguridad en aplicación y políticas de BD; acceso simulado no equivale a pagos reales; almacenamiento/distribución de vídeos depende de límites del proveedor.

## Verificación y seguimiento

### Pruebas obligatorias antes de aceptar migración 1

1. Prueba de integración con Ktor/JDBC conectando **como `app_runtime`**, no como propietario ni `postgres`.
2. Verificar permisos del rol: `rolsuper=false`, `rolbypassrls=false`, sin herencia privilegiada, RLS habilitado en todas las tablas privadas.
3. Usuario A, miembro de Empresa A, no ve ni escribe membresías de B. Usuario B obtiene el resultado simétrico.
4. Usuario con pertenencia a ambas empresas ve solo lo autorizado y sus roles no se mezclan.
5. Intentos de autoasignarse `company_admin` o de crear una empresa mediante endpoint ordinario reciben denegación.
6. Ausencia de JWT, token expirado, audiencia/issuer inválidos y `sub` ausente se rechazan antes de abrir consulta protegida.
7. Reutilización de conexión del pool y rollback no filtran el contexto del usuario anterior. Se comprueba con operaciones alternas A/B.
8. Comprobar que `companies` tiene creación exclusiva mediante endpoint de plataforma; el bootstrap del administrador global se documenta y no se basa en email manipulable.
9. Registrar comandos/reporte de prueba, migraciones y evidencia. **No declarar verificado** hasta ejecutar estas pruebas en el entorno local.

### Pruebas futuras al agregar cursos

- Invitación inexistente, caducada o revocada no permite matrícula/acceso; invitación gratuita válida permite matrícula sin compra; invitación de pago exige orden simulada completada y no permite duplicados.
- Alumno inscrito en Curso 1 no accede a vídeos/lecciones/comunidad/chat privados de Curso 2.
- Una compra simulada repetida no duplica inscripciones; cancelar/fallar no concede acceso; curso público gratuito permite inscripción sin orden ni pago.
- Curso privado no aparece en catálogo; curso público solo expone metadatos autorizados.
- Formador sin asignación y administrador empresarial no leen el chat privado ajeno.
- Administrador empresarial consulta estadísticas, progresos y calificaciones de **sus cursos** sin poder modificarlas, sin acceso a datos de empresas ajenas ni a chats; validar con el rol `app_runtime`.

## Decisiones abiertas

1. Seleccionar versiones concretas del nuevo conjunto y verificar la integración de JWT, pooling y migraciones sobre el modo de conexión elegido.
2. Confirmar modo `unlisted`, política de generación/expiración de invitaciones y descuentos parciales; las dos modalidades de invitación (`complimentary`, `simulated_purchase`) y los cursos públicos gratuitos están ya decididos.
3. Validar operativamente la custodia, rotación y recuperación del único administrador de plataforma. Cualquier ampliación a varios administradores requerirá otro ADR.
4. Definir proveedor de archivos, límites, visibilidad y reproducción de vídeos.
5. Definir políticas de retención, privacidad/consentimiento y estrategia real de pagos si deja de ser simulación.

## Fuentes técnicas

- PostgreSQL RLS: https://www.postgresql.org/docs/current/ddl-rowsecurity.html
- Supabase RLS: https://supabase.com/docs/guides/database/postgres/row-level-security
- Supabase JWT: https://supabase.com/docs/guides/auth/jwts
- Supabase JDBC/pooling: https://supabase.com/docs/guides/database/connecting-to-postgres
