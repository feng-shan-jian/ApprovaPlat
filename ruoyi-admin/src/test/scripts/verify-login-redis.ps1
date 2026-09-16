param(
    [string]$MemuraiExecutable = 'C:/Program Files/Memurai/memurai.exe'
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $MemuraiExecutable -PathType Leaf)) {
    throw "Memurai executable does not exist: $MemuraiExecutable"
}
$maven = (Get-Command mvn -ErrorAction Stop).Source
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testDirectory = Join-Path $tempRoot ('ruoyi-login-redis-it-' + [Guid]::NewGuid().ToString('N'))
$previousPort = $env:LOGIN_REDIS_PORT
$previousLogPath = $env:RUOYI_LOG_PATH
$server = $null
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
try {
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
}
finally {
    $listener.Stop()
}

New-Item -ItemType Directory -Path $testDirectory | Out-Null
try {
    $configPath = Join-Path $testDirectory 'memurai.conf'
    @('bind 127.0.0.1', "port $port", 'save ""', 'appendonly no', 'protected-mode yes') |
        Set-Content -LiteralPath $configPath -Encoding utf8NoBOM
    $server = Start-Process -FilePath $MemuraiExecutable -ArgumentList ('"{0}"' -f $configPath) `
        -WorkingDirectory $testDirectory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $testDirectory 'server.out') `
        -RedirectStandardError (Join-Path $testDirectory 'server.err')
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($server.HasExited) { throw 'Isolated Memurai exited before listening.' }
        $endpoint = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
            Where-Object OwningProcess -EQ $server.Id
        if ($endpoint) { $ready = $true; break }
        Start-Sleep -Milliseconds 200
    }
    if (-not $ready) { throw 'Isolated Memurai did not open its own port.' }
    $env:LOGIN_REDIS_PORT = [string]$port
    $env:RUOYI_LOG_PATH = Join-Path $testDirectory 'application-logs'
    Write-Output "Isolated Memurai PID $($server.Id), port $port; production RedisConfig and BCrypt tests."
    Push-Location $repoRoot
    try {
        & $maven -pl ruoyi-admin -am -Plogin-redis-it verify -B -ntp
        if ($LASTEXITCODE -ne 0) { throw "Login Redis verification failed (Maven exit $LASTEXITCODE)." }
    }
    finally { Pop-Location }
}
finally {
    if ($server -and -not $server.HasExited) {
        Stop-Process -InputObject $server
        $server.WaitForExit()
    }
    $env:LOGIN_REDIS_PORT = $previousPort
    $env:RUOYI_LOG_PATH = $previousLogPath
    $resolvedDirectory = [IO.Path]::GetFullPath($testDirectory)
    if (-not $resolvedDirectory.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not ([IO.Path]::GetFileName($resolvedDirectory)).StartsWith('ruoyi-login-redis-it-')) {
        throw "Refusing cleanup outside the owned test directory: $resolvedDirectory"
    }
    Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
}
