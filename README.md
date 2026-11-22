# Nexora

Nexora es un proyecto de comunidades y formación. El repositorio reúne una app Android y una API local. La app usa sus propios datos en el dispositivo. La conexión entre la app y la API queda pendiente.

## Qué está implementado

- **App Android.** Kotlin y Jetpack Compose, con navegación por comunidades y cursos, pantallas de acceso y perfil, y datos locales con Room y DataStore.
- **Backend.** API en Kotlin/JVM con SQLite para comprobar el estado del servidor y crear, listar, consultar, renombrar y eliminar comunidades. Los datos permanecen guardados después de reiniciar el servidor.
- **Pruebas.** Pruebas unitarias de Android y pruebas de integración del backend. Las del backend cubren las operaciones por HTTP, la persistencia en SQLite y las respuestas ante datos inválidos o comunidades inexistentes.

## Estructura

```text
frontend/android/       App Android, módulo :app de Gradle
backend/
  domain/              Entidades y reglas
  application/         Casos de uso y puertos
  adapter/http/        Rutas HTTP
  adapter/persistence/ Repositorio SQLite
  bootstrap/           Punto de arranque
  test/                Pruebas de integración
gradle/                Configuración y wrapper de Gradle
```

## Cómo ejecutarlo

Abre la raíz del repositorio en Android Studio. Para ejecutar la app, sincroniza Gradle y selecciona el módulo `app` y un emulador o dispositivo.

Desde la raíz, ejecuta estas comprobaciones en PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat -p backend test
```

Para arrancar la API local:

```powershell
.\gradlew.bat -p backend run
```

Con el servidor arrancado, puedes abrir [el estado del servidor](http://127.0.0.1:3000/health) y [el listado de comunidades](http://127.0.0.1:3000/api/communities). El listado devuelve `{"items":[]}` en una base de datos nueva.

## Próxima etapa

La preparación del backend SaaS, las decisiones aceptadas y los resultados de verificación están en el [informe previo a la Fase 1](docs/preparacion-fase-1.md). El entorno PostgreSQL desechable tiene una [guía independiente](docs/postgres-local.md).

1. Implementar el registro, el inicio de sesión y las sesiones en la API.
2. Asociar comunidades con propietarios y miembros, y proteger las operaciones de edición y borrado.
3. Añadir publicaciones y comentarios, y conectar los clientes con la API.

Consulta la [guía de Android](GUIA.md) y la [documentación de la API](backend/README.md) para ver más detalles.
