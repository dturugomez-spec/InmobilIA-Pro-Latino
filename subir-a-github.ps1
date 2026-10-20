# ════════════════════════════════════════════════════════════════
#  InmobilIA Pro · Script de subida a GitHub (Windows PowerShell)
#
#  USO:
#    1. Abre PowerShell EN ESTA CARPETA (Shift + click derecho ->
#       "Abrir ventana PowerShell aqui")
#    2. Si es la primera vez ejecuta:
#       Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
#    3. Lanza el script:
#       .\subir-a-github.ps1
# ════════════════════════════════════════════════════════════════

Write-Host ""
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  InmobilIA Pro · Subida a GitHub" -ForegroundColor Cyan
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""

# 1. Comprobar git
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Host "[X] Git no esta instalado." -ForegroundColor Red
    Write-Host "    Descargalo desde https://git-scm.com/download/win" -ForegroundColor Yellow
    exit 1
}
Write-Host "[OK] Git instalado: $(git --version)" -ForegroundColor Green

# 2. Pedir datos
$user = Read-Host "Tu usuario de GitHub (ej: jdaniel)"
$repo = Read-Host "Nombre del repo (default: inmobilia-pro)"
if ([string]::IsNullOrWhiteSpace($repo)) { $repo = "inmobilia-pro" }

$privado = Read-Host "Repo privado? [s/N]"
$visibility = if ($privado -eq "s" -or $privado -eq "S") { "private" } else { "public" }

Write-Host ""
Write-Host "Vas a crear: https://github.com/$user/$repo  ($visibility)" -ForegroundColor Yellow
$confirma = Read-Host "Continuar? [S/n]"
if ($confirma -eq "n" -or $confirma -eq "N") { exit 0 }

# 3. Inicializar git si hace falta
if (-not (Test-Path ".git")) {
    Write-Host ""
    Write-Host "[..] Inicializando repositorio git..." -ForegroundColor Cyan
    git init
    git branch -M main
} else {
    Write-Host "[OK] Repositorio git ya existente" -ForegroundColor Green
}

# 4. Configurar identidad git si no la tiene
$gitName  = git config user.name
$gitEmail = git config user.email
if ([string]::IsNullOrWhiteSpace($gitName)) {
    $gitName = Read-Host "Tu nombre para los commits"
    git config user.name "$gitName"
}
if ([string]::IsNullOrWhiteSpace($gitEmail)) {
    $gitEmail = Read-Host "Tu email para los commits"
    git config user.email "$gitEmail"
}

# 5. Add + commit
Write-Host ""
Write-Host "[..] Anadiendo archivos..." -ForegroundColor Cyan
git add .

# Comprobar si hay cambios que commit
$pending = git status --porcelain
if ([string]::IsNullOrWhiteSpace($pending)) {
    Write-Host "[OK] Sin cambios pendientes" -ForegroundColor Green
} else {
    Write-Host "[..] Creando commit..." -ForegroundColor Cyan
    git commit -m "InmobilIA Pro v6 · PWA + diferenciadores + deploy Render"
}

# 6. Crear repo remoto
Write-Host ""
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Yellow
Write-Host "  AHORA: crea el repo en GitHub" -ForegroundColor Yellow
Write-Host "═══════════════════════════════════════════════" -ForegroundColor Yellow
Write-Host ""
Write-Host "1. Abre en tu navegador:" -ForegroundColor White
Write-Host "   https://github.com/new" -ForegroundColor Cyan
Write-Host ""
Write-Host "2. Configura:" -ForegroundColor White
Write-Host "   - Repository name : $repo" -ForegroundColor Cyan
Write-Host "   - Visibility       : $visibility" -ForegroundColor Cyan
Write-Host "   - NO marques nada en Initialize this repository" -ForegroundColor Red
Write-Host "     (sin README, sin .gitignore, sin licencia)" -ForegroundColor Red
Write-Host ""
Write-Host "3. Click en Create repository" -ForegroundColor White
Write-Host ""
Read-Host "Pulsa Enter cuando hayas creado el repo en GitHub"

# 7. Configurar remote y push
$remoteUrl = "https://github.com/$user/$repo.git"
Write-Host ""
Write-Host "[..] Configurando remote: $remoteUrl" -ForegroundColor Cyan

$existing = git remote get-url origin 2>$null
if ($existing) {
    git remote set-url origin $remoteUrl
} else {
    git remote add origin $remoteUrl
}

Write-Host ""
Write-Host "[..] Subiendo codigo a GitHub (push)..." -ForegroundColor Cyan
Write-Host "   Si te pide login usa un Personal Access Token:" -ForegroundColor Yellow
Write-Host "   https://github.com/settings/tokens?type=beta" -ForegroundColor Yellow
Write-Host ""

git push -u origin main

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host "  EXITO!  Codigo en GitHub" -ForegroundColor Green
    Write-Host "═══════════════════════════════════════════════" -ForegroundColor Green
    Write-Host ""
    Write-Host "Tu repo: https://github.com/$user/$repo" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Siguiente paso: deploy en Render" -ForegroundColor Yellow
    Write-Host "  1. Entra en https://dashboard.render.com" -ForegroundColor White
    Write-Host "  2. New + -> Blueprint -> conecta tu repo" -ForegroundColor White
    Write-Host "  3. Anade secretos (SUPABASE_URL, SUPABASE_KEY, OPENAI_API_KEY)" -ForegroundColor White
    Write-Host "  4. Espera 5-8 min" -ForegroundColor White
    Write-Host ""
} else {
    Write-Host ""
    Write-Host "[X] Hubo un error al subir." -ForegroundColor Red
    Write-Host "   Revisa el mensaje arriba." -ForegroundColor Yellow
    Write-Host "   Lo mas comun: token o credenciales." -ForegroundColor Yellow
}
