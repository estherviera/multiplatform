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

## Base Ktor SaaS (Fase 1 · Incremento 1A)

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

### Endpoints del incremento 1A

| Método | Ruta | Resultado |
| --- | --- | --- |
| GET | `/health/live` | Liveness pública mínima (`200`, `{"status":"ok"}`) |
| GET | `/health/ready` | Readiness operativa (`200` listo, `503` degradado, `401` si falta o falla `X-Nexora-Readiness-Token` cuando está configurado) |

Todas las respuestas incluyen la cabecera `X-Request-Id`. Los errores HTTP devuelven un sobre JSON uniforme sin trazas ni detalles internos:

```json
{
  "error": {
    "code": "not_found",
    "message": "Resource not found",
    "requestId": "..."
  }
}
```

La especificación OpenAPI mínima está en [`docs/openapi/nexora-saas-v1.yaml`](../docs/openapi/nexora-saas-v1.yaml).

**Pendiente de incrementos posteriores:** validación JWT con Supabase Auth/JWKS, acceso JDBC con HikariCP, migraciones Flyway, RLS y rutas de negocio multiempresa.
