# Diagnóstico de Docker y Supabase local

## Recuperación con PostgreSQL 17.11.0.004

El CLI 2.122.1 ignoró la fijación temporal de imagen. El CLI 2.121.0 seleccionó la imagen oficial 17.11.0.004 y la sonda devolvió PostgreSQL 17.11. Se retiró solo el contenedor fallido tras comprobar nombre, etiqueta de proyecto e imagen. Dos arranques solapados provocaron un conflicto de Kong. El siguiente arranque, en serie, completó y PostgreSQL y Auth quedaron saludables.

La publicación inicial de Docker incluía comodines IPv4 e IPv6. Se detuvieron los servicios y se recrearon las publicaciones de PostgreSQL y Auth con 127.0.0.1 explícito. Kong quedó detenido. Windows y Docker confirmaron solo 127.0.0.1:55421 y 127.0.0.1:55422. GoTrue respondió HTTP 200 en /health y /settings.

Se aprovisionaron los roles JDBC y se guardaron dos contraseñas aleatorias distintas mediante DPAPI fuera del repositorio. Las pruebas TCP pasaron con ambos roles, incluido el rechazo de contraseñas incorrectas, identidad efectiva, ausencia de SUPERUSER y BYPASSRLS, membresías y privilegios de esquema. Se repitieron con las credenciales guardadas. Se conservó el volumen. No se ejecutaron migraciones de negocio ni se verificó RLS sobre tablas multiempresa, que corresponde a su propia entrega.

## Reintento con la imagen descargada

Se confirmó que los únicos contenedores que referenciaban la imagen `sha256:c141fd17ac6a76c4323e90b3aee8ff1a23d2e89e547c2b963cfadc309c079cc2` eran las dos sondas detenidas `nexora_prephase_diagnostico` (`f6e4b17fb0c3`) y `nexora_prephase_diagnostico_pull` (`7ca12f3de3e2`). Con autorización se eliminaron solo ambas sondas y la imagen local `public.ecr.aws/supabase/postgres:17.11.0.006`.

El registro devolvió el mismo digest. Docker descargó capas y reutilizó al menos una. La nueva sonda `nexora_prephase_postgres_version` ejecutó `postgres --version` sin red ni volumen montado y terminó con código 127. El cargador volvió a indicar `libzstd.so.1: file too short`. El fallo se reproduce independientemente del volumen de Nexora. Sigue sin determinarse si el archivo truncado procede de la imagen publicada o del almacenamiento local de capas.

Se detuvo el procedimiento en esa prueba. La sonda queda detenida. Supabase, la verificación de loopback, el aprovisionamiento con DPAPI y las pruebas de roles siguen pendientes.

Comprobación del 22 de noviembre de 2026. No se implementó Ktor, no se crearon tablas de negocio ni se accedió a Supabase remoto. No se hicieron commits ni se añadieron archivos al índice.

## Docker

Al comenzar Docker Desktop estaba detenido y el pipe Linux no existía. Se inició con `docker desktop start`, fuera del sandbox. El motor respondió como Docker Desktop 4.99.0, Engine 29.9.3, Linux amd64. El contexto `desktop-linux` apunta a `npipe:////./pipe/dockerDesktopLinuxEngine`. WSL2 figura en ejecución.

El volumen C: figura NTFS, Healthy/OK, con aproximadamente 70,7 GiB libres tras iniciar Docker. El disco virtual de datos ocupa aproximadamente 10,6 GiB en el host. El sistema de archivos Linux de datos informa 8,2 GiB usados y 947,4 GiB disponibles virtuales; esta última cifra no representa capacidad física disponible en Windows. No se ejecutó reparación ni comprobación integral del sistema de archivos.

Los logs `C:\Users\alexd\AppData\Local\Docker\log\vm\init.log` registran a las 01:04–01:05 UTC errores `read-only file system` escribiendo metadata.db de containerd, errores de E/S y fallos de las operaciones containers/create y system/df. Corroboran el fallo de almacenamiento descrito en el informe previo tras agotarse el disco; no demuestran por sí solos que todos sus efectos hayan desaparecido. Las consultas actuales de versión, contenedores y uso de almacenamiento responden sin HTTP 500.

## Intento de Supabase

Se verificó la red existente `nexora_prephase_loopback`: host_binding_ipv4=127.0.0.1 e IPv6 desactivado. Se ejecutó el comando start documentado con CLI 2.122.1, migraciones y semillas de aplicación desactivadas y salida suprimida para evitar divulgar credenciales.

El contenedor PostgreSQL `supabase_db_nexora_prephase` entró en reinicios repetidos: exit code 0, sin logs y health unhealthy. La imagen utilizada fue postgres:17.11.0.006. No se identificó con certeza la causa de este nuevo fallo. No atribuirlo sin más a corrupción del volumen o de la imagen.

El CLI terminó con código 1 y retiró automáticamente el contenedor fallido. No se emitieron comandos rm, prune, reset ni borrado de imágenes/volúmenes. La comprobación final conserva el volumen `supabase_db_nexora_prephase`, los dos volúmenes anteriores y la red. Los tres contenedores antiguos siguen detenidos. No hay listeners en 55420–55422. Auth no llegó a arrancar. No hay un stack Supabase operativo ni evidencia de accesibilidad funcional restringida a loopback; solo está verificada la configuración de red.

## Roles y secretos

No se ejecutó roles.sql ni Test-LocalRoles.ps1 porque PostgreSQL no estaba disponible. No están verificados los atributos, credenciales independientes, conexiones ni denegaciones de app_runtime/app_migrator. Se autorizó generar credenciales y custodiarlas mediante DPAPI para el usuario Windows, pero no se generaron ni almacenaron al fallar el arranque. No se alteraron contraseñas.

## Git

Antes de este informe había 13 Markdown de docs y 5 archivos de infra sin seguimiento: ADR-001, api-contracts, architecture, domain-model, implementation-plan, postgres-local, preparacion-fase-1, product-scope, security-model, security, test-strategy, testing-strategy y threat-model; Test-LocalRoles.ps1, roles.sql, verify-roles.sql y supabase/{config.toml,.gitignore}. Este informe también queda sin seguimiento.

Se conservaron las modificaciones previas de README, .gitignore, builds, wrapper e IDE. git diff --check no encontró errores; hubo avisos de conversión LF/CRLF. La búsqueda acotada de claves privadas, JWT, secretos Supabase y DSN con contraseña en los 18 archivos anteriores no produjo coincidencias; no equivale a una auditoría exhaustiva.

git check-ignore confirma exclusión de .env, .env.local, infra/local/.env, local.properties, docs/test.pem y los archivos privados internos de supabase. Sin embargo, infra/local/test.pem, infra/local/id_rsa, infra/local/credentials.json y test.key no quedan excluidos. Antes de custodiar secretos deben elegirse una ruta privada fuera del repositorio o reglas explícitas de exclusión. No se alteraron reglas existentes.

## Pendiente y alternativas conservadoras

No está preparado el entorno para comenzar el primer incremento con PostgreSQL/Auth verificables. Conservar el volumen y no repetir arranques a ciegas. Siguiente diagnóstico propuesto: revisar offline y sin escritura el contenido del volumen y los archivos de la imagen/entrada del contenedor; obtener evidencia del arranque sin el cleanup automático del CLI en un procedimiento revisado. Si aparecen daños, preservar una copia consistente antes de cualquier reparación. Como alternativa, preparar otro entorno local aislado con identificador y puertos distintos, conservando íntegro el actual y comprobando antes el espacio físico disponible. Cualquier borrado o restablecimiento requerirá autorización específica.

Los permisos adicionales empleados fueron inicio de Docker Desktop, acceso al pipe Linux, lectura de logs y metadatos de almacenamiento, consultas WSL/CIM, arranque CLI local y verificaciones finales. El sandbox impedía WSL/CIM y el acceso al pipe. No se ejecutaron builds Android/backend porque no se modificó código ni configuración de producto.
