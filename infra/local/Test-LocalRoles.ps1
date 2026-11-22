#requires -Version 7.2
# Las dos contraseñas se reciben del proceso que ejecuta las pruebas.
[CmdletBinding()]
param([switch]$SetInitialPasswords)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$context = 'desktop-linux'
$container = 'supabase_db_nexora_prephase'
$endpoint = docker context inspect $context --format '{{.Endpoints.docker.Host}}'
if ($LASTEXITCODE -ne 0 -or $endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw 'Se requiere el motor local de Docker Desktop para Windows.'
}
$runtimePassword = $env:NEXORA_RUNTIME_PASSWORD
$migrationPassword = $env:NEXORA_MIGRATION_PASSWORD
if (!$runtimePassword -or !$migrationPassword -or $runtimePassword -ceq $migrationPassword) {
    throw 'Inyecta dos contraseñas locales distintas.'
}
$passwords = [ordered]@{ app_runtime = $runtimePassword; app_migrator = $migrationPassword }
$verifySql = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'verify-roles.sql')
foreach ($role in $passwords.Keys) {
    $password = $passwords[$role]
    if ($password -notmatch '^[!-~]{32,128}$') {
        throw 'Para estas pruebas usa 32 a 128 caracteres ASCII imprimibles sin espacios.'
    }
}
foreach ($role in $passwords.Keys) {
    $password = $passwords[$role]
    if ($SetInitialPasswords) {
        # Solo inicializar un login nuevo. Las repeticiones conservan su contraseña.
        $missing = docker --context $context exec $container psql -XAt -U supabase_admin -d postgres -c "SELECT rolpassword IS NULL FROM pg_authid WHERE rolname = '$role'"
        if ($LASTEXITCODE -ne 0 -or $missing -notin 't','f') { throw "No se pudo comprobar $role." }
        if ($missing -eq 't') {
            "$password`n$password`n" | docker --context $context exec -i $container psql -X -v ON_ERROR_STOP=1 -U supabase_admin -d postgres -c "\password $role"
            if ($LASTEXITCODE -ne 0) { throw "Error al inicializar $role." }
        }
    }
    # Una conexión TCP desde la red del contenedor comprueba autenticación real.
    # El secreto viaja por stdin, nunca forma parte de argv ni del SQL.
    $login = "read -r PGPASSWORD && export PGPASSWORD && exec psql -X -w -h $container -U $role -d postgres -v ON_ERROR_STOP=1"
    $invalidPassword = [guid]::NewGuid().ToString('N')
    $rejection = "$invalidPassword`nSELECT 1;" | docker --context $context exec -i $container sh -c $login 2>&1
    if ($LASTEXITCODE -eq 0 -or ($rejection -join ' ') -notmatch 'password authentication failed') {
        throw "No se confirmó el rechazo por contraseña incorrecta de $role."
    }
    "$password`n$verifySql" | docker --context $context exec -i $container sh -c $login
    if ($LASTEXITCODE -ne 0) { throw "Falló la conexión autenticada o la verificación de $role." }
}
