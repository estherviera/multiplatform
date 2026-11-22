# PostgreSQL de pruebas en Windows

En esta máquina, la imagen `17.11.0.006` falla al cargar `libzstd.so.1` incluso tras volver a descargarla. Para reproducir la alternativa, usa **Supabase CLI 2.121.0** en todos los comandos de esta guía. Su catálogo selecciona `public.ecr.aws/supabase/postgres:17.11.0.004`, cuyo binario devuelve PostgreSQL 17.11. Conserva la red restringida y usa un solo proceso de arranque a la vez.

Cada desarrollador puede usar su propia instalación de Docker Desktop y ejecutar `./infra/local/Initialize-LocalRoles.ps1` desde PowerShell 7.2 o posterior una vez verificados PostgreSQL, Auth y las publicaciones en loopback. El script reutiliza el SQL y las pruebas de esta guía. Crea dos contraseñas aleatorias y las guarda con DPAPI en `%LOCALAPPDATA%\Nexora\prephase-roles.dpapi`, fuera del repositorio. Cada desarrollador debe generarlas con su cuenta de Windows y conservar el archivo para reintentos en esa máquina. El archivo no se comparte con otros desarrolladores.

El proyecto desechable `nexora_prephase` usa Supabase CLI 2.121.0 mediante npx, Node 20+ y Docker Desktop con contenedores Linux. Se fija PostgreSQL 17 en [config.toml](../infra/local/supabase/config.toml). El servicio Auth inicializa sus objetos de sistema. Migraciones de aplicación y semillas están desactivadas. Este entorno no necesita cuenta remota, token de acceso de Supabase ni proyecto enlazado. Reservar espacio suficiente para la imagen, sus capas descomprimidas y el crecimiento del disco virtual antes del primer arranque.

### Arranque desde la raíz en PowerShell 7.2 o posterior

**Primera inicialización en cada equipo.** Descarga previamente las imágenes de PostgreSQL `public.ecr.aws/supabase/postgres:17.11.0.004`, Auth `public.ecr.aws/supabase/gotrue:v2.197.0` y Kong `public.ecr.aws/supabase/kong:2.8.1` y ejecuta `npx.cmd --yes supabase@2.121.0 --version` para preparar la caché del CLI. Antes del bloque de arranque, aísla el equipo de la LAN (por ejemplo, desconecta Ethernet y Wi-Fi). En Docker Desktop observado, la opción de la red no bastó y el CLI publicó temporalmente en todas las interfaces. Mantén el equipo aislado hasta completar el bloque posterior y comprobar los listeners.

Comprobar que `docker context inspect desktop-linux` apunta al pipe local y que los puertos 55420–55422 y el identificador del proyecto están disponibles. En la primera ejecución crear la red privada con el primer comando. Para reutilizarla, comprobar que conserva la opción de loopback. El CLI puede mostrar credenciales locales al arrancar. Suprimir la salida en registros compartidos y comprobar siempre el código de salida.

```powershell
docker --context desktop-linux network create --opt com.docker.network.bridge.host_binding_ipv4=127.0.0.1 nexora_prephase_loopback
$networkOptions = docker --context desktop-linux network inspect nexora_prephase_loopback --format '{{json .Options}}'
if ($LASTEXITCODE -or ($networkOptions | ConvertFrom-Json).'com.docker.network.bridge.host_binding_ipv4' -ne '127.0.0.1') { throw 'Se requiere la red local restringida.' }
$env:DOCKER_CONTEXT = 'desktop-linux'
$env:DOCKER_HOST = 'npipe:////./pipe/dockerDesktopLinuxEngine'
npx.cmd --yes supabase@2.121.0 start --workdir infra/local --network-id nexora_prephase_loopback -x postgrest,imgproxy,storage-api,realtime,studio,mailpit,postgres-meta,edge-runtime,logflare,vector,supavisor *> $null
if ($LASTEXITCODE) { throw 'Supabase local no está preparado.' }
docker --context desktop-linux port supabase_db_nexora_prephase
```

Desde la raíz, tras el primer arranque, convierte las publicaciones en enlaces explícitos a loopback y asegura MFA TOTP habilitado en el contenedor local de GoTrue (`GOTRUE_MFA_TOTP_ENROLL_ENABLED=true`, `GOTRUE_MFA_TOTP_VERIFY_ENABLED=true`, reflejado también en `[auth.mfa.totp]` de `config.toml`). Usa GoTrue directamente y deja Kong detenido. La URL de Auth para este entorno es `http://127.0.0.1:55421`, con rutas nativas como `/health`, `/settings`, `/user` y `/factors`. El gateway y su prefijo `/auth/v1` quedan fuera de esta variante local.

```powershell
docker --context desktop-linux stop supabase_kong_nexora_prephase supabase_db_nexora_prephase supabase_auth_nexora_prephase
if ($LASTEXITCODE) { throw 'No se pudieron detener los servicios locales.' }
node ./infra/local/Bind-LocalPorts.cjs
if ($LASTEXITCODE) { throw 'No se pudieron fijar los puertos a loopback.' }
docker --context desktop-linux start supabase_db_nexora_prephase supabase_auth_nexora_prephase
docker --context desktop-linux ps --filter label=com.supabase.cli.project=nexora_prephase
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort 55421,55422)
if ($listeners.Count -ne 2 -or ($listeners | Where-Object LocalAddress -ne '127.0.0.1')) {
    throw 'Publicaciones inesperadas. Mantén el equipo aislado.'
}
Invoke-WebRequest http://127.0.0.1:55421/health | Select-Object StatusCode
& ./infra/local/Initialize-LocalRoles.ps1
```

Para el uso diario ejecuta `docker --context desktop-linux start supabase_db_nexora_prephase supabase_auth_nexora_prephase` o `stop` con esos mismos nombres. Así conservas las publicaciones explícitas. El comando del CLI del primer bloque puede recrear contenedores y requiere repetir la verificación aislada.

Verificar que el puerto 55422 solo escucha en loopback. Con la base y Auth saludables, ejecutar el aprovisionamiento contra el contenedor local de nombre fijo. El SQL es transaccional y repetible. No introduce tablas ni altera contraseñas existentes. Restringe CREATE/TEMP heredados de PUBLIC en esta base desechable. Mantener el proyecto separado de cualquier otra aplicación que dependa de esos permisos.

```powershell
Get-Content -Raw -Encoding UTF8 infra/local/roles.sql | docker --context desktop-linux exec -i supabase_db_nexora_prephase psql -X -v ON_ERROR_STOP=1 -U supabase_admin -d postgres
if ($LASTEXITCODE) { throw 'Falló el aprovisionamiento local.' }
```

### Credenciales y verificación

Inyectar dos contraseñas aleatorias distintas de al menos 32 caracteres desde el gestor de secretos de desarrollo en la sesión de aprovisionamiento. Los procesos de Ktor y Flyway recibirán **solo** su propia credencial. No incluir contraseñas en los argumentos, URL JDBC, config.toml, historial o archivos versionados. El script acepta ASCII imprimible sin espacios para enviarlas por stdin. El parámetro inicial configura mediante psql los roles nuevos que aún carecen de contraseña. Los siguientes ensayos reutilizan las existentes.

| Variables de entorno previstas | Destino y uso |
|---|---|
| `NEXORA_RUNTIME_JDBC_URL`, `NEXORA_RUNTIME_USER`, `NEXORA_RUNTIME_PASSWORD` | Ktor. URL local `jdbc:postgresql://127.0.0.1:55422/postgres`, usuario fijo `app_runtime`. Conexión y USAGE en nexora, sin grants de negocio ni capacidad de crear objetos. |
| `FLYWAY_URL`, `FLYWAY_USER`, `FLYWAY_PASSWORD` | Migrador independiente. Misma base local, usuario `app_migrator`, propietario del esquema privado nexora. Configurar el esquema de Flyway como nexora. Tiene REFERENCES sobre auth.users.id para la futura FK. |
| `NEXORA_MIGRATION_PASSWORD` | Entrada del verificador local, inyectada desde el mismo secreto usado como FLYWAY_PASSWORD en el migrador. |
| `NEXORA_AUTH_ISSUER`, `NEXORA_AUTH_AUDIENCE`, `NEXORA_AUTH_JWKS_URL` | Configuración futura del verificador. Contrastar emisor, audiencia, JWKS y algoritmo con Auth de desarrollo antes de configurar Ktor. HTTP de loopback solo en pruebas locales. |
| `NEXORA_GLOBAL_ADMIN_SUB` | Solo el componente protegido de plataforma. Un UUID verificado, ausente mientras la capacidad esté deshabilitada. |
| `NEXORA_PLATFORM_JDBC_URL`, `NEXORA_PLATFORM_USER`, `NEXORA_PLATFORM_PASSWORD` | Ejecutor SQL dedicado de plataforma (`app_platform`), con contraseña propia en `%LOCALAPPDATA%\Nexora\prephase-platform.dpapi`. Solo posee `USAGE` en `nexora_platform` y `EXECUTE` sobre las funciones fijas de V7; cero acceso directo a tablas ni al esquema `nexora`. |

```powershell
& ./infra/local/Test-LocalRoles.ps1 -SetInitialPasswords
& ./infra/local/Test-LocalRoles.ps1
```

Se comprueban ambas conexiones con su login real por TCP y el rechazo de una contraseña incorrecta. El [SQL de verificación](../infra/local/verify-roles.sql) comprueba identidad, atributos efectivos, propiedad, membresías y privilegios de creación. El [aprovisionamiento](../infra/local/roles.sql) se ejecuta con autoridad administrativa local únicamente para crear o inspeccionar los roles. La futura credencial del ejecutor de alta de empresas queda fuera de este entorno inicial.

Para parar conservando el estado, ejecutar `npx.cmd --yes supabase@2.121.0 stop --workdir infra/local`. Un segundo arranque conserva volúmenes y requiere las mismas credenciales. Ante errores de disco, parar los intentos de arranque, revisar capacidad y estado de Docker y reintentar cuando el motor esté sano. No usar reset, push, enlace remoto ni limpieza de volúmenes para validar este entorno. [Supabase CLI](https://supabase.com/docs/guides/local-development/cli/getting-started) · [Red Docker limitada a loopback](https://supabase.com/docs/guides/local-development)
