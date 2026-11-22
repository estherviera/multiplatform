# Revisión técnica previa a la Fase 1

**22 de noviembre de 2026.** El servicio de aplicación disponible sigue siendo la API local Kotlin/SQLite. Esta entrega prepara herramientas, documenta reglas y actualiza dependencias del prototipo. La implementación del backend SaaS comienza en una entrega posterior.

| Problema | Resultado de esta revisión |
|---|---|
| Documentación excluida | La regla local `docs/` ocultaba los once Markdown técnicos existentes. Git admite ahora los Markdown de la raíz documental y de decisions. Continúan excluidos los archivos de entorno, claves locales y artefactos del CLI. |
| Elección de arquitectura | [ADR-001 aceptado](decisions/ADR-001-kotlin-jdbc-identidad-rls.md). Android Kotlin/Compose, web React/TypeScript, backend Kotlin/Ktor, PostgreSQL y Auth en Supabase, pgJDBC/HikariCP, Flyway y autorización de negocio más RLS. |
| Privilegio global | [Procedimiento de plataforma](security-model.md#administración-de-plataforma). Un único UUID verificado, MFA reciente, sesión vigente, ejecutor SQL específico, revocación y recuperación bajo custodia operativa. Diseño pendiente de implementación. |
| Entorno PostgreSQL | [Instrucciones reproducibles](postgres-local.md) y scripts de comprobación con credenciales separadas y sin tablas de negocio. |
| Continuidad de la empresa | [Invariantes de dominio](domain-model.md). Crear empresa, designar administrador verificado y registrar auditoría en una sola transacción. Serializar sustituciones y bajas para conservar al menos un administrador activo. Casos negativos y concurrentes incorporados a [T04/T06](test-strategy.md). |
| Dependencias | Cambios limitados al sistema de build y a bibliotecas ya presentes. La nueva pila se ha evaluado para selección en F1. |

## Compatibilidad y avisos

| Componente | Revisión y alcance |
|---|---|
| Gradle | Actualización de 9.6.0 a 9.9.2, con wrapper regenerado y SHA-256 de la distribución oficial. La serie corregida aborda, entre otros, avisos sobre el registro del daemon y la generación de accesores de catálogos. [Notas de Gradle](https://docs.gradle.org/9.9.2/release-notes.html) |
| Kotlin | El plugin JVM 2.2.10 está en el rango de CVE-2026-53914, relativo al tratamiento de metadatos de caché de compilación. Se seleccionó 2.4.21 estable para backend y plugin Compose. La versión efectiva del compilador Android depende también de AGP. [Aviso y corrección del proveedor](https://github.com/JetBrains/kotlin/releases/tag/v2.4.20-Beta1) |
| Jackson | Databind 2.20.1 pertenece al rango de GHSA-m8xf-2cr7-77ph. Se adoptó 2.21.8 de la línea 2.x corregida. El adaptador heredado usa readTree. No requiere cambiar la forma de sus peticiones o respuestas. [Aviso de FasterXML](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph) |
| SQLite | Driver heredado Xerial 3.50.3.0 actualizado a 3.54.1.0, que incorpora un motor posterior a las correcciones de SQLite, incluida la publicada en 3.53.2 para CVE-2026-11824. La exposición depende de las operaciones permitidas sobre la BD. El repositorio heredado se conserva. [Avisos SQLite](https://sqlite.org/cves.html) · [Driver Xerial](https://github.com/xerial/sqlite-jdbc/releases/tag/3.54.1.0) |
| Android | AGP 9.5.1, compile/target 37, min 26, KSP 2.3.14, BOM Compose 2026.02.01, Room 2.8.5, Media3 1.6.1 y DataStore 1.1.4 son la base revisada. Las recomendaciones de versiones nuevas de lint requieren evaluación por separado. [Compatibilidad de AGP](https://developer.android.com/build/releases/agp-9-5-0-release-notes) |
| Ktor y JWT | Candidato Ktor 3.6.1. Su POM de autenticación JWT declara stdlib 2.3.21, java-jwt 4.6.0 y jwks-rsa 0.24.1. Evaluar java-jwt 4.6.2 y la alineación efectiva de Jackson al integrar. El bytecode JVM 17 del backend exige verificar también la JVM desplegada y su soporte criptográfico. [POM de Ktor](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-auth-jwt-jvm/3.6.1/ktor-server-auth-jwt-jvm-3.6.1.pom) · [Auth0 java-jwt](https://github.com/auth0/java-jwt) |
| pgJDBC y HikariCP | Candidatos 42.7.14 y 7.1.0. El driver corrige, entre otros, CVE-2026-107314 y CVE-2026-107315. Validar TLS, modo de pool, tiempos límite y rollback al probar el conjunto con Ktor. [Seguridad pgJDBC](https://jdbc.postgresql.org/security/) · [HikariCP](https://github.com/brettwooldridge/HikariCP) |
| Flyway | Candidato Community 13.13.0. Resolver y probar juntos flyway-core y flyway-database-postgresql de igual versión en un proceso separado con Java compatible. En esta entrega se prescinde de incorporar el migrador al backend heredado. [API Java](https://documentation.red-gate.com/flyway/reference/usage/api-java) · [Módulo PostgreSQL](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database) |

Se consultó OSV por 15 coordenadas Maven y versiones concretas, incluidas bibliotecas Android y candidatos del backend. El resultado señaló el plugin Kotlin antiguo. La consulta puntual no cubre todo el grafo transitivo, los binarios de la imagen Docker ni futuras alertas. Las combinaciones futuras de Ktor, Auth, JDBC y Flyway requieren una prueba conjunta antes de fijarse.

## Requisitos para comenzar y aceptar Fase 1

### Comprobaciones ejecutadas en esta entrega

| Comprobación | Resultado y límite |
|---|---|
| Git y documentación | `git ls-files --others --exclude-standard -- docs infra` enumera 13 Markdown y 5 archivos de infraestructura. `git check-ignore -v` confirma las exclusiones de .env, .env.local, local.properties, un PEM de prueba y el estado temporal del CLI. Verificados enlaces locales, sintaxis del script PowerShell y diff. La búsqueda de patrones de secretos en esos 18 archivos no produjo coincidencias. Los archivos están disponibles para añadir a Git. |
| Backend | `.\gradlew.bat -p backend build` con Gradle 9.9.2 y Kotlin 2.4.21 completó la compilación, el empaquetado y las 2 pruebas HTTP/SQLite. XML con 0 fallos, errores u omisiones. Advertencia del driver SQLite sobre acceso nativo de la JVM. |
| Android | `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest` terminó con éxito. APK debug, 1 prueba unitaria y 1 instrumentada en un AVD Pixel_4 temporal con Android 17. Los informes XML confirman 0 fallos y 0 errores. El emulador se cerró al finalizar. |
| Lint y resolución real | 26 advertencias, 0 errores. Persiste la omisión de dos reglas de Lifecycle. El grafo del build Android resolvió tanto el plugin Kotlin solicitado por AGP como el de Compose a 2.4.21 y KSP a 2.3.14. El backend resolvió jackson-core y databind 2.21.8, annotations 2.21, sqlite-jdbc 3.54.1.0 y stdlib 2.4.21. |
| Supabase local | Se ejecutó CLI 2.122.1 con Node 24.22.1, se contactó con Docker Desktop Linux 29.2.1 y se creó y verificó la red de loopback. El arranque de Supabase falló al crear un contenedor porque el almacén de Docker estaba en solo lectura y el disco del host se había quedado sin espacio. Una vez liberados más de 76 GB, el motor devolvió HTTP 500 y el reinicio terminó con código 1. Se denegó permiso para la comprobación posterior. No hay evidencia de un stack sano. |
| PostgreSQL y SaaS | No se ejecutaron el aprovisionamiento de roles, las pruebas de credenciales ni pruebas sobre las tres tablas de negocio. La red de pruebas se creó y hubo descargas de imágenes. No se borraron volúmenes, no se ejecutaron migraciones de aplicación y no se accedió a Supabase remoto. JWT, MFA, capacidad de plataforma y RLS siguen pendientes de verificación de extremo a extremo. |

### Archivos de esta entrega

- Control de versiones e índice: `.gitignore` y `README.md`.
- Decisiones y seguimiento: `docs/decisions/ADR-001-kotlin-jdbc-identidad-rls.md`, `docs/domain-model.md`, `docs/implementation-plan.md`, `docs/security-model.md`, `docs/threat-model.md` y `docs/test-strategy.md`. Se crearon `docs/postgres-local.md` y este informe. Los otros Markdown que ahora muestra Git ya existían.
- Entorno nuevo: `infra/local/supabase/config.toml`, `infra/local/supabase/.gitignore`, `infra/local/roles.sql`, `infra/local/verify-roles.sql` y `infra/local/Test-LocalRoles.ps1`.
- Dependencias: `backend/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew` y `gradlew.bat`. Los cambios previos de IDE son ajenos a la entrega.

### Pendientes

Para comenzar, usar la arquitectura aprobada, el backend separado y un entorno de desarrollo con el CLI y las credenciales de cada rol bajo custodia. Confirmar emisor, audiencia, algoritmo, JWKS y estrategia de rotación del proyecto de prueba. Seleccionar el modo de conexión PostgreSQL que se probará frente al de despliegue.

La aceptación de F1 requiere ejecutar validación JWT, MFA y revocación según el alcance del endpoint, errores HTTP y transacciones con reutilización física del pool. El alta global permanece cerrada hasta disponer de auditoría durable, verificación de identidad del designado, política de frescura de MFA y pruebas de atomicidad. La primera migración de negocio y la demostración de aislamiento A/B corresponden a F2. Ningún resultado de herramientas locales sustituye esas pruebas.
