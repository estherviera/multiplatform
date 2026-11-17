# Nexora: app Android de vídeos

App nativa en **Kotlin + Jetpack Compose** con:
- Registro, inicio de sesión y cierre de sesión
- Subida de vídeos desde la galería del móvil
- Lista de vídeos y reproductor (ExoPlayer / Media3)
- Borrar tus propios vídeos

Todo se guarda **en el propio móvil**: usuarios y vídeos en una base de datos **Room**,
la sesión en **DataStore** y los archivos de vídeo en el **almacenamiento interno** de la app.
No hace falta crear ninguna cuenta en internet para probarla.

## Cómo abrirla

1. Android Studio → **Clone Repository** → `https://github.com/estherviera/multiplatform.git`
2. Espera a que termine el *Gradle sync* (barra de abajo a la derecha).
3. Pulsa ▶ **Run** con un emulador.
4. Para tener un vídeo en el emulador, **arrastra un .mp4 desde tu PC** encima de la ventana del emulador.

## Estructura (MVVM)

```
com.example.nexora
├── MainActivity.kt            → única Activity y navegación entre pantallas
├── data/                      → MODELO (datos)
│   ├── Usuario.kt             → @Entity tabla "usuarios"
│   ├── Video.kt               → @Entity tabla "videos"
│   ├── UsuarioDao.kt          → consultas de usuarios
│   ├── VideoDao.kt            → consultas de vídeos (devuelve Flow)
│   ├── NexoraDatabase.kt      → base de datos Room (patrón Singleton)
│   ├── SesionManager.kt       → guarda la sesión con DataStore
│   └── NexoraRepository.kt    → repositorio: único acceso a los datos
└── ui/                        → VISTA y VIEWMODEL
    ├── AuthViewModel.kt       → login, registro, cerrar sesión (StateFlow)
    ├── VideoViewModel.kt      → lista, subir y borrar vídeos
    ├── PantallaLogin.kt       → pantallas de Login y Registro
    ├── PantallaInicio.kt      → lista de vídeos
    ├── PantallaSubir.kt       → formulario para subir un vídeo
    ├── PantallaReproductor.kt → reproduce un vídeo
    └── PantallaPerfil.kt      → datos del usuario y cerrar sesión
```

Flujo de datos: **Pantalla → ViewModel → Repositorio → Room / DataStore / archivos**,
y los datos vuelven a la pantalla con `StateFlow` y `Flow`, que se observan con `collectAsState()`.

## Base de datos

**Tabla `usuarios`**

| Campo | Tipo | Notas |
|---|---|---|
| id | Int | clave primaria autogenerada |
| nombre | String | |
| email | String | único |
| passwordHash | String | contraseña cifrada con SHA-256 |

**Tabla `videos`**

| Campo | Tipo | Notas |
|---|---|---|
| id | Int | clave primaria autogenerada |
| titulo | String | |
| descripcion | String | |
| rutaArchivo | String | ruta del vídeo en el almacenamiento interno |
| autorEmail | String | quién lo subió |
| autorNombre | String | |
| fecha | Long | milisegundos |

## Navegación

```
login ──► registro
  │          │
  ▼          ▼
inicio ──► reproductor/{videoId}
  │ ├────► subir
  │ └────► perfil ──(cerrar sesión)──► login
```

## Qué temas de la asignatura se usan y dónde

| Tema de clase | Dónde está en el código |
|---|---|
| Arquitectura base y componentes (Activity) | `MainActivity.kt`: una sola Activity con Compose |
| Callbacks del ciclo de vida | `PantallaReproductor.kt`: pausa el vídeo en `ON_STOP` y libera el reproductor al salir |
| Corrutinas (`viewModelScope.launch`, `withContext(Dispatchers.IO)`, `suspend`) | ViewModels y `NexoraRepository.kt` (copiar el vídeo se hace en `Dispatchers.IO`) |
| Patrones de diseño: Singleton | `NexoraDatabase.kt` (una sola instancia de la base de datos) |
| Patrones de diseño: Observer | `Flow`/`StateFlow` observados por las pantallas con `collectAsState()` |
| Patrones de diseño: Facade | `NexoraRepository.kt` (oculta Room, DataStore y archivos a los ViewModels) |
| Diseño de interfaces con Jetpack Compose y Material 3 | Carpeta `ui/` (`Scaffold`, `LazyColumn`, `Card`, `TopAppBar`...) |
| Interoperabilidad XML–Compose | `PantallaReproductor.kt`: `AndroidView` con `PlayerView` |
| Bases de datos: Room (Entity, DAO, Database, Repository) | Carpeta `data/` |
| Bases de datos: DataStore | `SesionManager.kt` |
| Bases de datos: ficheros internos | `NexoraRepository.subirVideo()` guarda el vídeo en `filesDir/videos` |
| GitHub Actions | `.github/workflows/android.yml`: compila y pasa los tests en cada push |

## Trabajar en grupo con GitHub

- **Al empezar**: Git → **Pull** (Ctrl+T) para bajar lo de las demás.
- **Al terminar**: **Commit** (Ctrl+K) y **Push** (Ctrl+Shift+K).
- Repartíos los archivos para no tocar el mismo a la vez.
- En la pestaña **Actions** de GitHub se ve si la app sigue compilando tras cada push (✅ o ❌).

## Ideas para seguir

- Añadir empresas y cursos (como en el prototipo web): una tabla `empresas` y un campo `empresaId` en cada vídeo.
- Mostrar una miniatura de cada vídeo en la lista.
- Sincronizar con un servidor en la nube: el repositorio es el sitio donde se añadiría.
