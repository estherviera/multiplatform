# Backend de Nexora

Servicio local en Kotlin/JVM con SQLite. Requiere JDK 17 o superior. Desde la raíz del repositorio:

```powershell
.\gradlew.bat -p backend test
.\gradlew.bat -p backend run
```

El servicio escucha en `http://127.0.0.1:3000`. Puedes cambiar el puerto con `PORT` y la ruta de SQLite con `DATABASE_PATH`. Por defecto, Gradle ejecuta el servicio desde `backend/` y la base se guarda en `backend/data/nexora.db`.

| Método | Ruta | Resultado |
| --- | --- | --- |
| GET | `/health` | Estado del servidor |
| GET | `/api/communities` | Comunidades guardadas |
| POST | `/api/communities` | Crea una comunidad con un cuerpo JSON como `{"name":"Nexora"}` |
| GET | `/api/communities/{id}` | Consulta una comunidad por su UUID |
| PUT | `/api/communities/{id}` | Cambia el nombre con el mismo formato JSON |
| DELETE | `/api/communities/{id}` | Elimina la comunidad y devuelve `204` |

Las rutas individuales devuelven `400` si el UUID es inválido y `404` si no existe la comunidad.

La API todavía no autentica a los usuarios y está pensada para desarrollo local.

## Arquitectura hexagonal

- `domain` contiene la entidad `Community` y sus invariantes.
- `application` define los puertos de entrada para crear y listar, el contrato de persistencia y el servicio de comunidades.
- `adapter/http` recibe solicitudes y llama a los puertos de entrada.
- `adapter/persistence` implementa el contrato del repositorio usando SQLite.
- `bootstrap/Main.kt` configura y conecta los componentes del servicio local heredado.
- `test/` contiene las pruebas unitarias y de integración.

## Base Ktor SaaS (Fase 1 · Incrementos 1A, 1B y 1C)

El backend SaaS en Ktor convive con el servicio local heredado mediante un punto de arranque separado (`bootstrap/SaasMain.kt`) y **nunca monta** las rutas de demostración `/api/communities` ni `/health`.

Para ejecutar el servidor Ktor desde la raíz del repositorio:

```powershell
.\gradlew.bat -p backend runSaas
```

Por defecto escucha en `http://127.0.0.1:8080`. Variables de entorno soportadas:

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `NEXORA_ENV` | `local` | Entorno (`local`, `test`, `staging`, `production`) |
| `NEXORA_HOST` | `127.0.0.1` | Interfaz de escucha |
| `NEXORA_PORT` | `8080` | Puerto TCP (`0..65535`) |
| `NEXORA_SERVICE_NAME` | `nexora-saas-api` | Nombre del servicio reportado en readiness |
| `NEXORA_CORS_ALLOWED_ORIGINS` | *(vacío)* | Lista separada por comas de orígenes `http(s)` permitidos (sin comodín `*`) |
| `NEXORA_READINESS_TOKEN` | *(vacío en local)* | Token operativo exigido en `X-Nexora-Readiness-Token` para `/health/ready` (obligatorio en `staging` y `production`) |
| `NEXORA_SHUTDOWN_GRACE_MS` | `1000` | Tiempo de gracia de apagado |
| `NEXORA_SHUTDOWN_TIMEOUT_MS` | `5000` | Tiempo máximo de apagado |
| `NEXORA_AUTH_ISSUER` | *(vacío en local)* | Emisor exacto esperado (`iss`), p.ej. `http://127.0.0.1:55421` en local o `https://<ref>.supabase.co/auth/v1` (obligatorio en `staging`/`production`) |
| `NEXORA_AUTH_AUDIENCE` | `authenticated` | Audiencia esperada (`aud`) |
| `NEXORA_AUTH_JWKS_URL` | *(vacío en local)* | URL exacta del documento JWKS, p.ej. `http://127.0.0.1:55421/.well-known/jwks.json` (solo `https://` fuera de loopback local/test; obligatorio en `staging`/`production`) |
| `NEXORA_AUTH_ALLOWED_ALGS` | `ES256` | Lista separada por comas de algoritmos asimétricos admitidos (`ES256`, `ES384`, `ES512`, `RS256`, `RS384`, `RS512`, `PS256`); rechaza `none` y `HS*` |
| `NEXORA_AUTH_CLOCK_SKEW_SECONDS` | `30` | Tolerancia de reloj acotada (`0..120` segundos) para `exp` y `nbf` |
| `NEXORA_AUTH_JWKS_CACHE_TTL_SECONDS` | `600` | TTL de caché JWKS (`5..3600` segundos) |
| `NEXORA_AUTH_JWKS_MIN_REFRESH_SECONDS` | `30` | Intervalo mínimo entre refrescos de JWKS ante `kid` desconocido (`0..300` segundos) |
| `NEXORA_AUTH_JWKS_TIMEOUT_MS` | `5000` | Timeout HTTP de consulta JWKS (`500..30000` ms) |
| `NEXORA_AUTH_ALLOW_ANONYMOUS` | `false` | Rechaza sesiones anónimas de Auth (`is_anonymous=true`) salvo habilitación explícita |
| `NEXORA_DB_ENABLED` | *(auto/false)* | Habilita (`true`) o deshabilita explícitamente (`false`) el pool PostgreSQL runtime. Si se omite y no hay variables `NEXORA_RUNTIME_*`, el backend arranca sin BD (compatible con 1A/1B) |
| `NEXORA_RUNTIME_JDBC_URL` | *(vacío)* | URL pgJDBC sin credenciales incrustadas, p.ej. `jdbc:postgresql://127.0.0.1:55422/postgres` |
| `NEXORA_RUNTIME_USER` | *(vacío)* | Rol de ejecución mínimo (`app_runtime`). Rechaza `postgres`, `supabase_admin`, `service_role` y `app_migrator` |
| `NEXORA_RUNTIME_PASSWORD` | *(vacío)* | Contraseña de `app_runtime` inyectada solo por entorno (nunca en URL, argumentos ni logs) |
| `NEXORA_RUNTIME_SCHEMA` | `nexora` | Esquema privado de trabajo |
| `NEXORA_RUNTIME_SSL_MODE` | `prefer` (`verify-full` en `staging`/`production`) | Modo TLS de pgJDBC; `staging`/`production` exigen `verify-ca` o `verify-full` |
| `NEXORA_DB_MAX_POOL_SIZE` | `10` | Tamaño máximo del pool HikariCP (`1..50`) |
| `NEXORA_DB_MIN_IDLE` | `2` (acotado al máximo) | Conexiones inactivas mínimas |
| `NEXORA_DB_CONNECTION_TIMEOUT_MS` | `5000` | Timeout de adquisición de conexión (`500..30000` ms) |
| `NEXORA_DB_STATEMENT_TIMEOUT_MS` | `10000` | `statement_timeout` por sesión (`500..120000` ms) |
| `NEXORA_DB_LOCK_TIMEOUT_MS` | `3000` | `lock_timeout` por sesión (`100..30000` ms) |
| `NEXORA_DB_IDLE_TX_TIMEOUT_MS` | `15000` | `idle_in_transaction_session_timeout` (`1000..120000` ms) |
| `NEXORA_DB_PREPARE_THRESHOLD` | *(por defecto del driver)* | Opcional (`0..100`); útil con valor `0` para poolers en modo transacción |
| `NEXORA_DB_IO_PARALLELISM` | `maxPoolSize` | Paralelismo acotado del dispatcher de E/S bloqueante (`1..64`) |

### Endpoints implementados (Fase 1 + Fases 2C.1 y 2C.2)

| Método | Ruta | Autenticación | Resultado |
| --- | --- | --- | --- |
| GET | `/health/live` | Pública | Liveness pública mínima (`200`, `{"status":"ok"}`) |
| GET | `/health/ready` | Token operativo opcional/obligatorio | Readiness operativa (`200` listo, `503` degradado, `401` si falta o falla `X-Nexora-Readiness-Token` cuando está configurado). Cuando PostgreSQL está habilitado, incluye el estado `checks.database` |
| GET | `/api/v1/auth/me` | `Authorization: Bearer <jwt>` | Identidad verificada (`userId` UUID de `sub`, `issuer`, `audience`, `sessionId`, `aal`, `expiresAt`) con `Cache-Control: no-store`. Devuelve `401` ante token ausente/inválido/expirado y `503` con fallo cerrado si el verificador no está configurado o JWKS no responde |
| GET | `/api/v1/me/profile` | `Authorization: Bearer <jwt>` | Perfil propio (`200`) o `404 profile_not_found` si todavía no existe (`Cache-Control: no-store`) |
| PUT | `/api/v1/me/profile` | `Authorization: Bearer <jwt>` | Creación idempotente o actualización de `displayName` y `avatarPath` vinculados exclusivamente al `sub` del JWT (`200`, `400`, `409`, `503`) |
| GET | `/api/v1/me/companies` | `Authorization: Bearer <jwt>` | Listado paginado (`limit` 1..100, `offset` 0..10000) de empresas activas donde el usuario es miembro activo junto a su rol |
| GET | `/api/v1/companies/{companyId}` | `Authorization: Bearer <jwt>` | Detalle de empresa activa (`200`), `400` si `companyId` no es UUID o `404` uniforme si no existe, está suspendida o es ajena |
| GET | `/api/v1/companies/{companyId}/membership` | `Authorization: Bearer <jwt>` | Membresía activa propia en esa empresa (`200`) o `404` uniforme |
| GET | `/api/v1/companies/{companyId}/memberships` | `Authorization: Bearer <jwt>` | Listado paginado de membresías visibles según RLS (`company_admin` ve todas las de su empresa; `trainer`/`member` solo su propia fila activa; `404` uniforme para empresas ajenas/suspendidas o sin membresía activa) |
| POST | `/api/v1/companies/{companyId}/invitations` | `Authorization: Bearer <jwt>` (`company_admin`) | Crea invitación idempotente para un usuario elegible con perfil verificado (`201`/`200`, `403`, `404`, `409`) con auditoría |
| GET | `/api/v1/companies/{companyId}/invitations` | `Authorization: Bearer <jwt>` (`company_admin`) | Listado paginado de invitaciones de la empresa |
| GET | `/api/v1/me/invitations` | `Authorization: Bearer <jwt>` | Listado paginado de invitaciones dirigidas al usuario autenticado |
| GET | `/api/v1/me/invitations/{invitationId}` | `Authorization: Bearer <jwt>` (destinatario) | Consulta de invitación propia (`200`) o `404` si es ajena |
| POST | `/api/v1/me/invitations/{invitationId}/accept` | `Authorization: Bearer <jwt>` (destinatario) | Aceptación atómica e idempotente mediante `nexora.accept_company_invitation(...)` (`200`, `404`, `409`) con auditoría |
| POST | `/api/v1/me/invitations/{invitationId}/decline` | `Authorization: Bearer <jwt>` (destinatario) | Rechazo explícito e idempotente (`200`, `404`, `409`) con auditoría |
| PATCH | `/api/v1/companies/{companyId}/memberships/{userId}/role` | `Authorization: Bearer <jwt>` (`company_admin`) | Cambio de rol auditado; bloquea dejar la empresa sin administrador activo (`409 last_active_admin_required`) |
| POST | `/api/v1/companies/{companyId}/memberships/{userId}/suspend` | `Authorization: Bearer <jwt>` (`company_admin`) | Suspensión auditada; protege al último administrador activo (`409`) |
| POST | `/api/v1/companies/{companyId}/memberships/{userId}/reactivate` | `Authorization: Bearer <jwt>` (`company_admin`) | Reactivación auditada de membresía suspendida (no salta aceptación de invitaciones) |
| POST | `/api/v1/companies/{companyId}/admins/replace` | `Authorization: Bearer <jwt>` (`company_admin`) | Sustitución atómica y auditada de un administrador activo por un sucesor activo |

Todas las respuestas incluyen la cabecera `X-Request-Id`. Los errores HTTP devuelven un sobre JSON uniforme sin trazas, contraseñas, tokens ni detalles internos:

```json
{
  "error": {
    "code": "not_found",
    "message": "Resource not found",
    "requestId": "..."
  }
}
```

La especificación OpenAPI está en [`docs/openapi/nexora-saas-v1.yaml`](../docs/openapi/nexora-saas-v1.yaml).

### Migraciones Flyway (proceso separado con `app_migrator`)

El servidor Ktor **nunca** ejecuta Flyway ni recibe credenciales `FLYWAY_*`. Las migraciones SQL viven en `backend/resources/db/migration/` (esquema `nexora`, tabla `nexora.flyway_schema_history`, con `cleanDisabled=true` y `baselineOnMigrate=false`) y se ejecutan explícitamente con la tarea `migrate`:

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `FLYWAY_URL` | *(obligatoria)* | URL pgJDBC sin contraseñas, p.ej. `jdbc:postgresql://127.0.0.1:55422/postgres` |
| `FLYWAY_USER` | *(obligatoria)* | Rol dedicado `app_migrator` (rechaza `app_runtime`, `postgres`, `supabase_admin`, `service_role`) |
| `FLYWAY_PASSWORD` | *(obligatoria)* | Contraseña de `app_migrator` inyectada en memoria desde DPAPI o gestor local |
| `FLYWAY_SCHEMA` | `nexora` | Esquema privado de migraciones (rechaza `auth`, `pg_catalog`, `information_schema`) |
| `FLYWAY_SSL_MODE` | `prefer` | Modo TLS de pgJDBC |

Ejemplo desde la raíz del repositorio en PowerShell 7.2+ reutilizando el archivo DPAPI local de cada desarrollador (`%LOCALAPPDATA%\Nexora\prephase-roles.dpapi`, generado previamente con `./infra/local/Initialize-LocalRoles.ps1`):

```powershell
$ciphertexts = @(Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'Nexora\prephase-roles.dpapi'))
$env:FLYWAY_URL = 'jdbc:postgresql://127.0.0.1:55422/postgres'
$env:FLYWAY_USER = 'app_migrator'
$env:FLYWAY_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $ciphertexts[1]) -AsPlainText
try {
    .\gradlew.bat -p backend migrate --args="migrate"
    .\gradlew.bat -p backend migrate --args="validate"
    .\gradlew.bat -p backend migrate --args="info"
} finally {
    Remove-Item Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
}
```

### Ejecución de pruebas unitarias y de integración local

1. **Pruebas unitarias y HTTP (sin Docker ni BD):**

```powershell
.\gradlew.bat -p backend test
```

2. **Integración real con Supabase Auth local (`127.0.0.1:55421`):**

```powershell
.\gradlew.bat -p backend test "-Dnexora.test.localAuth=true"
```

3. **Integración real con PostgreSQL 17 local (`127.0.0.1:55422`, roles `app_runtime` y `app_migrator`):**

Cada miembro del equipo debe haber ejecutado previamente `./infra/local/Initialize-LocalRoles.ps1` en su propia máquina (ver [`docs/postgres-local.md`](../docs/postgres-local.md)) y tener activos `supabase_db_nexora_prephase` y `supabase_auth_nexora_prephase`. Las contraseñas se inyectan únicamente por variables de entorno en memoria (nunca por `-D` ni en archivos del repositorio):

```powershell
$ciphertexts = @(Get-Content -LiteralPath (Join-Path $env:LOCALAPPDATA 'Nexora\prephase-roles.dpapi'))
$env:NEXORA_RUNTIME_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $ciphertexts[0]) -AsPlainText
$env:FLYWAY_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $ciphertexts[1]) -AsPlainText
try {
    .\gradlew.bat -p backend test --tests com.nexora.backend.PostgresInfrastructureIntegrationTest "-Dnexora.test.localPostgres=true"
} finally {
    Remove-Item Env:NEXORA_RUNTIME_PASSWORD, Env:FLYWAY_PASSWORD -ErrorAction SilentlyContinue
}
```

**Migraciones aplicadas en el esquema `nexora`:**
- `V1__init_identity_context.sql` (Fase 1C): función `nexora.current_user_id()` (`SECURITY INVOKER`, sin tablas de negocio).
- `V2__create_multitenant_core_tables.sql` (Fase 2A): tablas iniciales multiempresa `nexora.profiles`, `nexora.companies` y `nexora.company_memberships` con FKs `ON DELETE RESTRICT`, restricciones, índices, trigger diferido de continuidad de administrador activo y RLS habilitado con denegación por defecto.
- `V3__multitenant_rls_policies_and_grants.sql` (Fase 2B): funciones auxiliares booleanas de alcance mínimo (`nexora.is_active_company_member(uuid)`, `nexora.is_active_company_admin(uuid)`), corrección `SECURITY DEFINER` del trigger `nexora.enforce_company_has_active_admin()` para funcionar bajo RLS real, privilegios mínimos por tabla/columna para `app_runtime`, políticas RLS explícitas (`USING`/`WITH CHECK`) y revocación explícita para `PUBLIC`, `anon` y `authenticated`.
- `V4__company_invitations_and_audit.sql` (Fase 2C.2): tablas `nexora.company_invitations` y `nexora.audit_events` con RLS, funciones acotadas `nexora.profile_exists(uuid)`, `nexora.record_audit_event(...)` y `nexora.accept_company_invitation(...)`, sin `INSERT`/`UPDATE`/`DELETE` directos sobre auditoría para `app_runtime`.
- `V5__harden_audit_and_invitation_guards.sql` (Fase 2C.2 · endurecimiento): revoca `EXECUTE` sobre `nexora.record_audit_event(...)` a `app_runtime` para impedir falsificación directa de auditoría, mueve la auditoría de invitaciones/membresías a triggers `SECURITY DEFINER`, añade guarda `BEFORE UPDATE` de inmutabilidad/transiciones en `company_invitations` y función atómica `nexora.replace_company_admin(...)`.
- `V6__defer_membership_audit_trigger.sql` (Fase 2C.2 · endurecimiento): convierte el trigger de auditoría de `company_memberships` en `CONSTRAINT TRIGGER ... DEFERRABLE INITIALLY DEFERRED` (`trg_z_company_memberships_audit`) para evitar deadlocks (`40P01`) con el bloqueo diferido `FOR UPDATE` del trigger de último administrador activo bajo concurrencia.
- `V7__platform_company_creation_and_idempotency.sql` (Fase 2C.3B): crea `nexora_platform.idempotency_keys` (con RLS y cero acceso directo), la función fija `nexora_platform.create_company_with_initial_admin(...)` (alta atómica e idempotente de empresa + primer `company_admin` activo + auditoría durable, respetando `trg_companies_require_active_admin`) y `nexora_platform.record_platform_audit_event(...)` (auditoría en transacción separada para denegaciones, fallos y rate limiting), concediendo `EXECUTE` exclusivamente al rol dedicado `app_platform`.
- `V8__harden_platform_functions_and_audit_controls.sql` (Fase 2C.3C): corrige el recorte de `p_correlation_id` con espacio en frontera de 64 caracteres, restringe `p_target_type` en `record_platform_audit_event` a `('platform_companies', 'company')` y acota `p_metadata` a objeto JSON `<= 4096` bytes.

### Administración global y creación segura de empresas (Fases 2C.3A y 2C.3B)

El endpoint `POST /api/v1/platform/companies` está deshabilitado por defecto si no se configura `NEXORA_GLOBAL_ADMIN_SUB` y permanece completamente separado de los roles empresariales, de `app_runtime` y de `app_migrator`:

- **Límite de confianza y modelo de amenazas**:
  - *Usuario HTTP no autorizado*: bloqueado por `PlatformRateLimiter` (agrupado por origen de red `remoteAddress`, resistente a rotación de cabeceras `Authorization`/`X-Forwarded-For`) y `DefaultGlobalAdminAuthorizer` (JWT `ES256`/JWKS, igualdad en tiempo constante con `NEXORA_GLOBAL_ADMIN_SUB`, sesión viva en Supabase Auth `GET /user`, MFA `aal2` y frescura en `amr[].timestamp`).
  - *Atacante con conexión SQL como `app_runtime`*: bloqueado por separación de roles PostgreSQL (`app_runtime` carece de `USAGE` en `nexora_platform`, de `EXECUTE` sobre las funciones de plataforma y de `INSERT` en `nexora.companies` → `42501`).
  - *Riesgo residual — compromiso directo de credenciales de `app_platform`*: PostgreSQL no puede verificar de forma independiente el JWT/MFA/sesión de Supabase Auth, por lo que un atacante que robe directamente la contraseña de `app_platform` podría invocar `create_company_with_initial_admin(...)` o `record_platform_audit_event(...)` sin pasar por Ktor. Aun así, su radio de impacto queda estrictamente acotado: no puede leer, modificar ni borrar empresas, perfiles, membresías o registros de auditoría existentes (`42501`), no puede falsificar eventos `platform.company_created`/`succeeded` vía `record_platform_audit_event` (`42501`), sigue sujeto al perfil existente y al trigger de administrador activo, y deja traza inmutable en `nexora.audit_events`. Mitigación: custodiar la contraseña en almacén aislado (`prephase-platform.dpapi` en local / gestor de secretos independiente en despliegue), acceso solo por red privada + TLS `verify-full` y rotación inmediata ante sospecha de compromiso.

| Variable | Por defecto | Descripción |
| --- | --- | --- |
| `NEXORA_GLOBAL_ADMIN_SUB` | *(deshabilitada)* | UUID canónico único del administrador global de plataforma (rechaza listas o valores malformados) |
| `NEXORA_GLOBAL_ADMIN_MFA_MAX_AGE_SECONDS` | `300` | Ventana máxima de frescura del desafío MFA (`30`–`900` s), evaluada sobre `amr[].timestamp` y nunca sobre `iat` |
| `NEXORA_GLOBAL_ADMIN_AUTH_USER_URL` | `<NEXORA_AUTH_ISSUER>/user` | Endpoint de Supabase Auth para verificar sesión viva, estado de cuenta y factores MFA verificados (HTTPS obligatorio fuera de loopback) |
| `NEXORA_GLOBAL_ADMIN_AUTH_TIMEOUT_MS` | `3000` | Timeout de conexión y petición HTTP hacia Supabase Auth (`500`–`15000` ms, `Redirect.NEVER`) |
| `NEXORA_AUTH_API_KEY` | *(opcional)* | Clave pública/anon para gateway de Supabase alojado (rechaza explícitamente cualquier clave `service_role`) |
| `NEXORA_PLATFORM_JDBC_URL` | *(deshabilitado)* | URL pgJDBC del ejecutor SQL dedicado de plataforma (sin contraseñas) |
| `NEXORA_PLATFORM_USER` | *(deshabilitado)* | Rol dedicado `app_platform` (rechaza `app_runtime`, `app_migrator`, `postgres`, `supabase_admin`, `service_role`) |
| `NEXORA_PLATFORM_PASSWORD` | *(deshabilitado)* | Contraseña exclusiva de `app_platform` (en local generada en `%LOCALAPPDATA%\Nexora\prephase-platform.dpapi`) |
| `NEXORA_PLATFORM_SCHEMA` | `nexora_platform` | Esquema privado del ejecutor de plataforma |
| `NEXORA_PLATFORM_SSL_MODE` | `prefer` (`verify-full` en staging/prod) | Modo TLS de pgJDBC para el pool de plataforma |
| `NEXORA_PLATFORM_DB_MAX_POOL_SIZE` | `2` | Tamaño máximo acotado del pool de plataforma (`1`–`5`) |
| `NEXORA_PLATFORM_RATE_LIMIT_MAX_REQUESTS` | `10` | Límite máximo de solicitudes por origen en la ventana (`1`–`1000`), con deduplicación de auditoría de violaciones |
| `NEXORA_PLATFORM_RATE_LIMIT_WINDOW_SECONDS` | `60` | Duración de la ventana deslizante de rate limiting (`1`–`3600` s) |
| `NEXORA_TRUSTED_PROXIES` | *(vacía — ninguno)* | Lista opcional de IPs literales de reverse proxies de confianza (rechaza `*`, nombres de host y `0.0.0.0`). Si está vacía o el peer TCP no está en la lista, se ignoran por completo `X-Forwarded-For` y `Forwarded`; si el peer es confiable, se toma el salto más a la derecha no confiable. |

**Verificación real en Supabase Auth local (`supabase_auth_nexora_prephase`, GoTrue v2.197.0 sobre `127.0.0.1:55421`):** el entorno local habilita MFA TOTP (`[auth.mfa.totp]` en `infra/local/supabase/config.toml` y `GOTRUE_MFA_TOTP_ENROLL_ENABLED=true` / `GOTRUE_MFA_TOTP_VERIFY_ENABLED=true` en `infra/local/Bind-LocalPorts.cjs`, conservando el enlace exclusivo a loopback). Se ha verificado de extremo a extremo contra GoTrue real y PostgreSQL 17 real: denegación de `aal1`, inscripción/desafío/verificación TOTP con autorización positiva `aal2`, alta atómica de empresa + primer `company_admin`, visibilidad inmediata bajo RLS real, idempotencia y concurrencia por `slug`/`Idempotency-Key`, rollback completo ante administrador inelegible, auditoría de éxitos/denegaciones/fallos, invariancia de `amr[totp].timestamp` al renovar, expiración MFA, revocación inmediata tras `POST /logout` y aislamiento `42501` frente a `app_runtime`.

**Pendiente de incrementos posteriores:** revisión y pruebas de cierre de plataforma (`2C.3C`), auditoría integral de cierre de Fase 2 (`2D`) y configuración de despliegue remoto/CI.
