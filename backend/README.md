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

### Endpoints de los incrementos 1A, 1B y 1C

| Método | Ruta | Autenticación | Resultado |
| --- | --- | --- | --- |
| GET | `/health/live` | Pública | Liveness pública mínima (`200`, `{"status":"ok"}`) |
| GET | `/health/ready` | Token operativo opcional/obligatorio | Readiness operativa (`200` listo, `503` degradado, `401` si falta o falla `X-Nexora-Readiness-Token` cuando está configurado). Cuando PostgreSQL está habilitado, incluye el estado `checks.database` |
| GET | `/api/v1/auth/me` | `Authorization: Bearer <jwt>` | Identidad verificada (`userId` UUID de `sub`, `issuer`, `audience`, `sessionId`, `aal`, `expiresAt`) con `Cache-Control: no-store`. Devuelve `401` ante token ausente/inválido/expirado y `503` con fallo cerrado si el verificador no está configurado o JWKS no responde |

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

**Pendiente de incrementos posteriores:** gestión segura de empresas y membresías en API, alta global de plataforma con MFA `aal2` y auditoría durable (`2C`), pruebas de administración/auditoría `T19` y configuración de despliegue remoto/CI.
