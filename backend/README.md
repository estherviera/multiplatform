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
- `bootstrap/Main.kt` configura y conecta los componentes.
- 	est/ contiene las pruebas de integración.
