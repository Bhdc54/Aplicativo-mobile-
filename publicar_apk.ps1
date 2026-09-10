# ============================================================================
# PUBLICA UMA VERSÃO DO PERITAVISION (tablet) em peritavision.facilmova.com.br/app
#
#   .\publicar_apk.ps1                      avança o build (versionCode) e publica
#   .\publicar_apk.ps1 -Versao 1.1          idem, mudando o nome da versão para 1.1
#   .\publicar_apk.ps1 -SemTag              não cria a tag no git
#
# O que faz, nesta ordem: avança app/versao.properties, compila o release
# ASSINADO (gradle recusa sem a chave em local.properties), calcula o SHA-256,
# pede sua matrícula e senha de administrador, envia o APK ao backend
# (POST /v1/app/apk - as credenciais do bucket ficam no servidor), grava o
# versao.properties num commit e marca a tag v<versão>. Nada de chave do MinIO
# neste PC.
# ============================================================================
param(
  [string]$Versao,
  [string]$Backend = "https://peritavision.facilmova.com.br",
  [switch]$SemTag
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

# ── 1. versão ────────────────────────────────────────────────────────────────
$arqVersao = Join-Path $PSScriptRoot 'app\versao.properties'
$linhas = Get-Content $arqVersao
$code = [int](($linhas | Where-Object { $_ -match '^versionCode=' }) -replace '^versionCode=', '')
$nome = (($linhas | Where-Object { $_ -match '^versionName=' }) -replace '^versionName=', '').Trim()
$novoCode = $code + 1
$novoNome = if ($Versao) { $Versao.Trim() } else { $nome }
if ($novoNome -notmatch '^[\w.\-]{1,32}$') { throw "nome de versão inválido: $novoNome" }
$sujo = git status --porcelain -- . ':!app/versao.properties'
if ($sujo) {
  Write-Host "Há alterações não commitadas no repositório:" -ForegroundColor Yellow
  $sujo | ForEach-Object { Write-Host "   $_" }
  $r = Read-Host "Publicar mesmo assim? O commit da tag não vai corresponder ao APK. (s/N)"
  if ($r -ne 's') { exit 1 }
}
$linhas = $linhas | ForEach-Object {
  if ($_ -match '^versionCode=') { "versionCode=$novoCode" }
  elseif ($_ -match '^versionName=') { "versionName=$novoNome" }
  else { $_ }
}
Set-Content -Path $arqVersao -Value $linhas -Encoding UTF8
Write-Host "Versão $novoNome (build $novoCode)" -ForegroundColor Cyan

# ── 2. compila o release assinado ────────────────────────────────────────────
Write-Host "Compilando o release..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleRelease --console=plain 2>&1 | Tee-Object -FilePath (Join-Path $env:TEMP 'pv-release.log') | Select-Object -Last 15
if ($LASTEXITCODE -ne 0) {
  # desfaz a versão para não pular número à toa
  git checkout -- app/versao.properties
  throw "o gradle falhou (log em $env:TEMP\pv-release.log). A versão foi desfeita."
}
$apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { git checkout -- app/versao.properties; throw "APK não encontrado em $apk (saiu sem assinatura como app-release-unsigned.apk?)" }
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
$tamanho = [math]::Round((Get-Item $apk).Length / 1MB, 1)
$commit = (git rev-parse HEAD).Trim()
Write-Host "APK pronto: $tamanho MB, SHA-256 $sha" -ForegroundColor Cyan

# ── 3. login de administrador ────────────────────────────────────────────────
$matricula = Read-Host "Matrícula do administrador"
$senhaSegura = Read-Host "Senha" -AsSecureString
$senha = [System.Net.NetworkCredential]::new('', $senhaSegura).Password
try {
  $login = Invoke-RestMethod -Method Post -Uri "$Backend/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ matricula = $matricula; senha = $senha } | ConvertTo-Json)
} catch { git checkout -- app/versao.properties; throw "login recusado: $($_.Exception.Message)" }
$token = $login.token
$senha = $null

# ── 4. envia ao servidor ─────────────────────────────────────────────────────
Write-Host "Enviando ao servidor..." -ForegroundColor Cyan
$resposta = & curl.exe -sS -f -X POST "$Backend/v1/app/apk" `
  -H "Authorization: Bearer $token" `
  -F "apk=@$apk;type=application/vnd.android.package-archive" `
  -F "versionCode=$novoCode" -F "versionName=$novoNome" -F "commit=$commit"
if ($LASTEXITCODE -ne 0) { git checkout -- app/versao.properties; throw "o servidor recusou o envio: $resposta" }
Write-Host "Servidor: $resposta" -ForegroundColor Green

# ── 5. registro no git: commit da versão e tag ───────────────────────────────
git add app/versao.properties
git commit -q -m "Publica a versão $novoNome (build $novoCode) do aplicativo do tablet" -m "SHA-256 do APK: $sha"
if (-not $SemTag) {
  $tag = "v$novoNome"
  if (git tag -l $tag) { $tag = "v$novoNome-b$novoCode" }
  git tag -a $tag -m "PeritaVision $novoNome (build $novoCode) - SHA-256 $sha"
  Write-Host "Tag $tag criada. Envie com: git push origin main --tags" -ForegroundColor Cyan
}
Write-Host ""
Write-Host "Publicado. Os tablets baixam em: $Backend/app" -ForegroundColor Green
