# ============================================================================
# TESTA SÓ O LOGIN NO ATENA, a partir DESTE PC.
#
# Para que serve: separar três coisas que hoje se confundem num "fetch failed"
#   1. o portal responde?              (rede / DNS)
#   2. o certificado valida?           (o problema de 17/09/2026)
#   3. a credencial é aceita?          (login e senha do Atena)
#
# A senha é digitada na hora, fica só na memória desta janela e NÃO é gravada
# em lugar nenhum. É enviada apenas ao próprio portal da SESP, por HTTPS -
# o mesmo caminho que o backend usa.
#
#   .\testar-login-atena.ps1
#   .\testar-login-atena.ps1 -Login algum.usuario
#   .\testar-login-atena.ps1 -BaseUrl https://portal.sesp.mt.gov.br/atena-api/api
# ============================================================================
param(
  [string]$BaseUrl = 'https://portal.sesp.mt.gov.br/atena-api/api',
  [string]$Login
)

$ErrorActionPreference = 'Stop'
# O PowerShell 5.1 ainda tenta TLS 1.0 por padrão em algumas máquinas; sem
# isto o teste falharia por um motivo que não é o que estamos investigando.
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$uri = "$BaseUrl/auth/signin"
$host_ = ([Uri]$BaseUrl).Host

Write-Host ""
Write-Host "=== 1. O SERVIDOR RESPONDE? ===" -ForegroundColor Cyan
Write-Host "    host: $host_"
try {
  $tcp = New-Object Net.Sockets.TcpClient
  $t0 = Get-Date
  $tcp.Connect($host_, 443)
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  Write-Host "    conectou na porta 443 em $ms ms" -ForegroundColor Green
} catch {
  Write-Host "    NAO CONECTOU: $($_.Exception.Message)" -ForegroundColor Red
  Write-Host "    -> o portal esta fora, ou esta rede nao alcanca ele. Pare aqui." -ForegroundColor Yellow
  exit 1
}

Write-Host ""
Write-Host "=== 2. O CERTIFICADO ===" -ForegroundColor Cyan
try {
  $ssl = New-Object Net.Security.SslStream($tcp.GetStream(), $false,
    [Net.Security.RemoteCertificateValidationCallback]{ param($a,$b,$c,$erros) $script:policy = $erros; $true })
  $ssl.AuthenticateAsClient($host_)
  $leaf = [Security.Cryptography.X509Certificates.X509Certificate2]$ssl.RemoteCertificate
  Write-Host "    site:     $($leaf.Subject)"
  Write-Host "    emissor:  $($leaf.Issuer)"
  Write-Host "    validade: ate $($leaf.NotAfter.ToString('dd/MM/yyyy'))"
  if ($script:policy -eq [Net.Security.SslPolicyErrors]::None) {
    Write-Host "    o Windows validou a cadeia (o navegador tambem valida)" -ForegroundColor Green
  } else {
    Write-Host "    o Windows RECLAMOU: $($script:policy)" -ForegroundColor Red
  }
  Write-Host "    Atencao: o Windows completa a cadeia sozinho. Se aqui der OK e"
  Write-Host "    o servidor continuar com UNABLE_TO_VERIFY_LEAF_SIGNATURE, o que"
  Write-Host "    falta la e o NODE_EXTRA_CA_CERTS apontando para atena-ca.pem."
  $ssl.Dispose()
} catch {
  Write-Host "    FALHA NO TLS: $($_.Exception.Message)" -ForegroundColor Red
} finally {
  $tcp.Close()
}

Write-Host ""
Write-Host "=== 3. O LOGIN ===" -ForegroundColor Cyan
if (-not $Login) { $Login = Read-Host "    Login do Atena" }
$senhaSegura = Read-Host "    Senha (nao aparece na tela)" -AsSecureString
$senha = [Net.NetworkCredential]::new('', $senhaSegura).Password

$corpo = @{ login = $Login; senha = $senha } | ConvertTo-Json -Compress
Write-Host "    POST $uri"
$t0 = Get-Date
try {
  $r = Invoke-RestMethod -Method Post -Uri $uri -ContentType 'application/json' -Body $corpo -TimeoutSec 30
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  Write-Host "    LOGIN ACEITO em $ms ms" -ForegroundColor Green
  # Nunca imprime o token inteiro: ele da acesso ao Atena enquanto valer.
  $campos = $r.PSObject.Properties.Name -join ', '
  Write-Host "    campos devolvidos: $campos"
  if ($r.token) {
    $tk = [string]$r.token
    Write-Host "    token: recebido ($($tk.Length) caracteres, final ...$($tk.Substring([Math]::Max(0,$tk.Length-6))))"
  } else {
    Write-Host "    ATENCAO: resposta sem campo 'token' - confira o formato acima." -ForegroundColor Yellow
  }
} catch {
  $ms = [int]((Get-Date) - $t0).TotalMilliseconds
  $resp = $_.Exception.Response
  if ($resp) {
    $codigo = [int]$resp.StatusCode
    $texto = ''
    try {
      $sr = New-Object IO.StreamReader($resp.GetResponseStream())
      $texto = $sr.ReadToEnd()
    } catch { }
    Write-Host "    RECUSADO: HTTP $codigo (em $ms ms)" -ForegroundColor Red
    if ($texto) { Write-Host "    resposta: $($texto.Substring(0, [Math]::Min(400, $texto.Length)))" }
    if ($codigo -eq 401 -or $codigo -eq 403) {
      Write-Host "    -> credencial recusada: o problema e login/senha, nao rede nem certificado." -ForegroundColor Yellow
    }
  } else {
    Write-Host "    FALHOU sem resposta HTTP (em $ms ms)" -ForegroundColor Red
    Write-Host "    motivo: $($_.Exception.Message)"
    $interna = $_.Exception.InnerException
    while ($interna) {
      Write-Host "    causa:  $($interna.Message)"
      $interna = $interna.InnerException
    }
    Write-Host "    -> sem resposta = rede ou certificado, nao credencial." -ForegroundColor Yellow
  }
} finally {
  $senha = $null
  [GC]::Collect()
}

Write-Host ""
Write-Host "=== COMO LER ===" -ForegroundColor Cyan
Write-Host "  1 e 2 OK + 3 OK      -> o Atena esta bom; o que falta e o certificado NO SERVIDOR."
Write-Host "  1 e 2 OK + 3 HTTP 401 -> a credencial cadastrada no PeritaVision precisa ser revista."
Write-Host "  1 falhou              -> portal fora do ar ou bloqueio de rede."
Write-Host ""
