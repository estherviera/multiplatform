#requires -Version 7.2
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$context = 'desktop-linux'
$db = 'supabase_db_nexora_prephase'
$endpoint = docker context inspect $context --format '{{.Endpoints.docker.Host}}'
if ($endpoint -ne 'npipe:////./pipe/dockerDesktopLinuxEngine') {
    throw 'Se requiere el motor Linux local de Docker Desktop.'
}
$project = docker --context $context inspect $db --format '{{index .Config.Labels "com.supabase.cli.project"}}'
if ($project -ne 'nexora_prephase') {
    throw 'El contenedor no está identificado como Nexora.'
}
$privateDir = Join-Path $env:LOCALAPPDATA 'Nexora'
$credentialFile = Join-Path $privateDir 'prephase-roles.dpapi'
$platformCredentialFile = Join-Path $privateDir 'prephase-platform.dpapi'

Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot 'roles.sql') |
    docker --context $context exec -i $db psql -X -v ON_ERROR_STOP=1 -U supabase_admin -d postgres

if (!(Test-Path -LiteralPath $credentialFile)) {
    $initialized = docker --context $context exec $db psql -XAt -U supabase_admin -d postgres -c "SELECT count(*) FROM pg_authid WHERE rolname IN ('app_runtime', 'app_migrator') AND rolpassword IS NOT NULL"
    if ($initialized -ne '0') {
        throw 'Ya existen contraseñas. Recupera el archivo DPAPI original antes de continuar.'
    }
    New-Item -ItemType Directory -Force $privateDir | Out-Null
    $ciphertexts = foreach ($role in 'app_runtime', 'app_migrator') {
        $password = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString
    }
    New-Item -ItemType File -Path $credentialFile -Value ($ciphertexts -join "`n") | Out-Null
}

if (!(Test-Path -LiteralPath $platformCredentialFile)) {
    $platformInitialized = docker --context $context exec $db psql -XAt -U supabase_admin -d postgres -c "SELECT count(*) FROM pg_authid WHERE rolname = 'app_platform' AND rolpassword IS NOT NULL"
    if ($platformInitialized -ne '0') {
        throw 'Ya existe contraseña para app_platform. Recupera el archivo DPAPI original antes de continuar.'
    }
    New-Item -ItemType Directory -Force $privateDir | Out-Null
    $platformPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $platformCipher = ConvertTo-SecureString $platformPassword -AsPlainText -Force | ConvertFrom-SecureString
    New-Item -ItemType File -Path $platformCredentialFile -Value $platformCipher | Out-Null
}

$ciphertexts = @(Get-Content -LiteralPath $credentialFile)
if ($ciphertexts.Count -ne 2) { throw 'Archivo DPAPI inválido.' }
$platformCiphertext = (Get-Content -LiteralPath $platformCredentialFile -Raw).Trim()
if (!$platformCiphertext) { throw 'Archivo DPAPI de plataforma inválido.' }
$previousRuntime = $env:NEXORA_RUNTIME_PASSWORD
$previousMigration = $env:NEXORA_MIGRATION_PASSWORD
$previousPlatform = $env:NEXORA_PLATFORM_PASSWORD
try {
    $env:NEXORA_RUNTIME_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $ciphertexts[0]) -AsPlainText
    $env:NEXORA_MIGRATION_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $ciphertexts[1]) -AsPlainText
    $env:NEXORA_PLATFORM_PASSWORD = ConvertFrom-SecureString (ConvertTo-SecureString $platformCiphertext) -AsPlainText
    & (Join-Path $PSScriptRoot 'Test-LocalRoles.ps1') -SetInitialPasswords
    & (Join-Path $PSScriptRoot 'Test-LocalRoles.ps1')
} finally {
    $env:NEXORA_RUNTIME_PASSWORD = $previousRuntime
    $env:NEXORA_MIGRATION_PASSWORD = $previousMigration
    $env:NEXORA_PLATFORM_PASSWORD = $previousPlatform
}
