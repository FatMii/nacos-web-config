param([ValidateSet('nacos2','nacos3','both')][string]$Service = 'both')
$ErrorActionPreference = 'Stop'

function DockerStep([string]$Label, [string[]]$Arguments) {
  Write-Host "==> $Label"
  & docker @Arguments
  if ($LASTEXITCODE -ne 0) { throw "$Label failed (exit $LASTEXITCODE)" }
}

Push-Location $PSScriptRoot
try {
  DockerStep 'Docker engine' @('--context','desktop-linux','version')
  $envFile = Join-Path $PSScriptRoot '.env'
  if (-not (Test-Path -LiteralPath $envFile)) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
      $bytes = New-Object byte[] 48
      $rng.GetBytes($bytes)
      $token = [Convert]::ToBase64String($bytes)
      $rng.GetBytes($bytes)
      $identity = [Convert]::ToBase64String($bytes)
      [System.IO.File]::WriteAllText($envFile, "NACOS_TEST_TOKEN=$token`nNACOS_TEST_IDENTITY=$identity`n", (New-Object System.Text.UTF8Encoding($false)))
    } finally { $rng.Dispose() }
    Write-Host 'Created local .env (gitignored).'
  }
  $base = @('--context','desktop-linux','compose','--project-name','nacos-web-config-local','--env-file',$envFile,'-f',(Join-Path $PSScriptRoot 'compose.yaml'))
  DockerStep 'Compose validation' ($base + @('config','--quiet'))
  $services = @($Service)
  if ($Service -eq 'both') { $services = @('nacos2','nacos3') }
  DockerStep 'Pull pinned tags' ($base + @('pull') + $services)
  DockerStep 'Start containers' ($base + @('up','-d') + $services)
  DockerStep 'Container status' ($base + @('ps','-a'))
  Write-Host 'Containers created. Wait until Nacos consoles respond before running tests.'
} finally { Pop-Location }
