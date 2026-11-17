# Nexora: guía paso a paso en Android Studio

App sencilla en **Kotlin + Jetpack Compose** con:
- Inicio de sesión y registro (Firebase Authentication)
- Subida de vídeos (Firebase Storage para el archivo y Firestore para los datos)
- Lista de vídeos y reproductor (ExoPlayer / Media3)

## Cómo está organizado (MVVM sencillo)

```
com.example.nexora
├── MainActivity.kt          → arranca la app y define la navegación entre pantallas
├── Video.kt                 → modelo de datos de un vídeo
├── AuthViewModel.kt         → lógica de login, registro y cierre de sesión
├── VideoViewModel.kt        → lógica para cargar y subir vídeos
├── PantallaLogin.kt         → pantallas de Login y Registro
├── PantallaInicio.kt        → lista de vídeos
├── PantallaSubir.kt         → formulario para subir un vídeo
├── PantallaReproductor.kt   → reproduce un vídeo
└── PantallaPerfil.kt        → email del usuario y botón de cerrar sesión
```

- **Model**: `Video`
- **View**: las pantallas (`Pantalla...`), que solo muestran datos y avisan al ViewModel
- **ViewModel**: `AuthViewModel` y `VideoViewModel`, que hablan con Firebase

## Datos guardados

**Usuario**: lo gestiona Firebase Authentication (email, contraseña y un id único).

**Vídeo**: colección `videos` de Firestore:

| Campo | Tipo | Ejemplo |
|---|---|---|
| titulo | String | "Introducción al diseño UX" |
| descripcion | String | "Primera lección del curso" |
| url | String | enlace al archivo en Storage |
| autorEmail | String | "ana@correo.com" |
| fecha | Long | 1763380000000 (milisegundos) |

## Navegación

```
login ──► registro
  │          │
  ▼          ▼
inicio ──► reproductor/{videoId}
  │ ├────► subir
  │ └────► perfil ──(cerrar sesión)──► login
```

---

> ✅ Los pasos 1, 3, 4 y 5 ya están hechos en este proyecto. Si clonas el repositorio,
> solo te falta poner tu `google-services.json` (Paso 2) si no está ya en `app/`.

## Paso 1: crear el proyecto

1. **File → New → New Project → Empty Activity**
2. Name: `Nexora`
3. Package name: `com.example.nexora` (importante: tiene que coincidir con el de los archivos)
4. Language: Kotlin. Minimum SDK: **API 26**. Build configuration language: Kotlin DSL.
5. Pulsa **Finish** y espera a que termine de sincronizar (la primera vez tarda bastante).

## Paso 2: crear el proyecto de Firebase

1. Entra en https://console.firebase.google.com y crea un proyecto llamado "Nexora".
2. Pulsa el icono de **Android** para añadir una app:
   - Nombre del paquete: `com.example.nexora`
   - Descarga el archivo **google-services.json**
3. En Android Studio, cambia la vista del panel izquierdo de "Android" a **"Project"** y
   arrastra `google-services.json` dentro de la carpeta **`app/`**.
4. En la consola de Firebase, activa:
   - **Authentication → Sign-in method → Correo electrónico/contraseña**
   - **Firestore Database → Crear base de datos** (en modo de prueba)
   - **Storage → Comenzar**

> ⚠️ En los proyectos nuevos, Firebase Storage pide activar el **plan Blaze** (hay que poner
> una tarjeta, pero para un proyecto de clase no se pasa de la parte gratuita).
> Si no puedes, díselo a tu profesor/a: se puede cambiar por otro almacenamiento.

## Paso 3: añadir las librerías

**`build.gradle.kts` (el de la raíz, "Project: Nexora")**: dentro de `plugins { }` añade:

```kotlin
id("com.google.gms.google-services") version "4.4.2" apply false
```

**`app/build.gradle.kts` (el de "Module :app")**:

Dentro de `plugins { }` añade:

```kotlin
id("com.google.gms.google-services")
```

Dentro de `dependencies { }` añade:

```kotlin
// Navegación entre pantallas y ViewModel
implementation("androidx.navigation:navigation-compose:2.8.5")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.compose.material:material-icons-core")

// Firebase
implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
implementation("com.google.firebase:firebase-auth")
implementation("com.google.firebase:firebase-firestore")
implementation("com.google.firebase:firebase-storage")

// Reproductor de vídeo
implementation("androidx.media3:media3-exoplayer:1.5.1")
implementation("androidx.media3:media3-ui:1.5.1")
```

Pulsa **Sync Now** (aparece arriba en amarillo).

## Paso 4: permiso de Internet

En `app/src/main/AndroidManifest.xml`, justo encima de `<application`, añade:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

## Paso 5: copiar el código

Copia los 9 archivos `.kt` de la carpeta `codigo/` dentro de
`app/src/main/java/com/example/nexora/`, **sustituyendo** el `MainActivity.kt` que crea Android Studio.
(La carpeta `ui/theme` que crea Android Studio puede quedarse, no molesta.)

## Paso 6: reglas de seguridad de Firebase

Para que solo los usuarios con sesión iniciada puedan leer y subir:

**Firestore → Reglas**
```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /videos/{id} {
      allow read, write: if request.auth != null;
    }
  }
}
```

**Storage → Reglas**
```
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    match /videos/{archivo} {
      allow read, write: if request.auth != null;
    }
  }
}
```

## Paso 7: probarla

1. Crea un emulador (**Device Manager → +**) o conecta tu móvil con la depuración USB activada.
2. Pulsa ▶ **Run**.
3. Regístrate, pulsa **+**, elige un vídeo, ponle título y súbelo.
   - En el emulador, para tener un vídeo en la galería, **arrastra un .mp4 desde tu PC**
     encima de la ventana del emulador.
4. Vuelve atrás, pulsa el vídeo de la lista y debería reproducirse.

## Ideas para seguir

- Añadir empresas y cursos (como en el prototipo web): una colección `empresas` y un campo `empresaId` en cada vídeo.
- Mostrar una miniatura de cada vídeo en la lista.
- Que solo puedan subir vídeos los usuarios con rol de profesor o administrador.
