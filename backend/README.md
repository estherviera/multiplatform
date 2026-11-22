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

## Base Ktor SaaS (Fase 1 · Incrementos 1A y 1B)

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

### Endpoints de los incrementos 1A y 1B

| Método | Ruta | Autenticación | Resultado |
| --- | --- | --- | --- |
| GET | `/health/live` | Pública | Liveness pública mínima (`200`, `{"status":"ok"}`) |
| GET | `/health/ready` | Token operativo opcional/obligatorio | Readiness operativa (`200` listo, `503` degradado, `401` si falta o falla `X-Nexora-Readiness-Token` cuando está configurado) |
| GET | `/api/v1/auth/me` | `Authorization: Bearer <jwt>` | Identidad verificada (`userId` UUID de `sub`, `issuer`, `audience`, `sessionId`, `aal`, `expiresAt`) con `Cache-Control: no-store`. Devuelve `401` ante token ausente/inválido/expirado y `503` con fallo cerrado si el verificador no está configurado o JWKS no responde |

Todas las respuestas incluyen la cabecera `X-Request-Id`. Los errores HTTP devuelven un sobre JSON uniforme sin trazas, tokens ni detalles internos:

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

### Ejecución de pruebas de integración con Supabase Auth local

Las pruebas unitarias (con claves asimétricas efímeras y reloj controlable) se ejecutan siempre con:

```powershell
.\gradlew.bat -p backend test
```

Con `supabase_db_nexora_prephase` y `supabase_auth_nexora_prephase` activos en `127.0.0.1:55421`, la prueba real contra Supabase Auth local se ejecuta con:

```powershell
.\gradlew.bat -p backend test "-Dnexora.test.localAuth=true"
```

**Pendiente de incrementos posteriores:** acceso JDBC con HikariCP (`1C`), migraciones Flyway (`1C`), tablas multiempresa y RLS (`F2`), y verificación en servidor de revocación de sesión/MFA para operaciones sensibles de plataforma.
