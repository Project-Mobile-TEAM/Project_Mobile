[CmdletBinding(DefaultParameterSetName = 'Quick')]
param(
    [Parameter(ParameterSetName = 'Quick')][switch]$Quick,
    [Parameter(Mandatory = $true, ParameterSetName = 'Full')][switch]$Full,
    [string]$EnvFile
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$failures = 0
function Report([bool]$Pass, [string]$Message) {
    if ($Pass) { Write-Output "[PASS] $Message" }
    else { Write-Output "[FAIL] $Message"; $script:failures++ }
}
try {
    if (-not $EnvFile) {
        $EnvFile = Join-Path $repoRoot '.env'
        if (-not (Test-Path -LiteralPath $EnvFile)) {
            if ($Full) { throw 'Full requires a configured .env; example configuration is not live evidence.' }
            $EnvFile = Join-Path $repoRoot '.env.example'
        }
    }
    if (-not (Test-Path -LiteralPath $EnvFile)) { throw 'Environment file is missing.' }
    $config = @{}
    foreach ($line in Get-Content -LiteralPath $EnvFile -Encoding UTF8) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw 'Invalid environment assignment; use KEY=value.' }
        $key = $Matches[1]
        $value = $Matches[2]
        $value = [regex]::Replace($value, '\$\{([A-Z][A-Z0-9_]*)\}', {
            param($match)
            $reference = $match.Groups[1].Value
            if (-not $config.ContainsKey($reference)) { throw 'Unresolved environment reference.' }
            [string]$config[$reference]
        })
        $config[$key] = $value
    }
    foreach ($key in @('DB_HOST','DB_PORT_SESSION','DB_PORT_TRANSACTION','DB_NAME','DB_USER',
        'DB_PASSWORD','FLYWAY_URL','SPRING_DATASOURCE_URL','SUPABASE_URL','SUPABASE_ANON_KEY')) {
        if (-not $config.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($config[$key])) {
            throw "Missing required environment key: $key"
        }
    }
    Report ($config.DB_HOST -match '^[a-zA-Z0-9.-]+$') 'Database hostname syntax'
    Report ($config.DB_PORT_SESSION -eq '5432') 'Session pooler uses port 5432'
    Report ($config.DB_PORT_TRANSACTION -eq '6543') 'Transaction pooler uses port 6543'
    $sessionPrefix = 'jdbc:postgresql://' + $config.DB_HOST + ':5432/' + $config.DB_NAME + '?'
    $runtimePrefix = 'jdbc:postgresql://' + $config.DB_HOST + ':6543/' + $config.DB_NAME + '?'
    Report ($config.FLYWAY_URL.StartsWith($sessionPrefix) -and $config.FLYWAY_URL -match '[?&]sslmode=(require|verify-full)(&|$)') 'Flyway uses session pooler with TLS'
    Report ($config.SPRING_DATASOURCE_URL.StartsWith($runtimePrefix) -and
        $config.SPRING_DATASOURCE_URL -match '[?&]sslmode=(require|verify-full)(&|$)' -and
        $config.SPRING_DATASOURCE_URL -match '[?&]prepareThreshold=0(&|$)') 'Runtime TLS and prepareThreshold=0 configured'
    $apiUri = $null
    $validUri = [Uri]::TryCreate($config.SUPABASE_URL, [UriKind]::Absolute, [ref]$apiUri)
    $validUri = $validUri -and $apiUri.Scheme -eq 'https' -and
        $apiUri.Host.EndsWith('.supabase.co') -and -not $apiUri.UserInfo -and
        -not $apiUri.Query -and -not $apiUri.Fragment -and $apiUri.AbsolutePath -eq '/'
    Report $validUri 'Supabase HTTPS project URL syntax'

    foreach ($relative in @('backend/src/main/resources/db/migration/V1__initial_schema.sql',
        'backend/src/main/resources/db/demo/V2__seed_demo_data.sql', 'backend/scripts/verify-schema.sql')) {
        Report (Test-Path -LiteralPath (Join-Path $repoRoot $relative)) "Required file: $relative"
    }
    if ($Full) {
        foreach ($key in @('DB_HOST','DB_USER','DB_PASSWORD','SUPABASE_URL','SUPABASE_ANON_KEY')) {
            if ($config[$key] -match 'your-|replace-with-') { throw 'Full refuses placeholder credentials; configure .env locally.' }
        }
        if ($failures) { throw 'Configuration checks failed; live probes were not attempted.' }
        foreach ($port in @(5432,6543)) {
            $tcp = [Net.Sockets.TcpClient]::new()
            $timer = [Diagnostics.Stopwatch]::StartNew()
            try {
                $task = $tcp.ConnectAsync($config.DB_HOST, $port)
                if (-not $task.Wait(5000)) { throw 'TCP timeout' }
                Report $tcp.Connected ("TCP port {0} reachable ({1} ms)" -f $port, $timer.ElapsedMilliseconds)
            } catch { Report $false "TCP port $port unreachable" }
            finally { $tcp.Dispose(); $timer.Stop() }
        }
        # Never probe with a service-role key: it would bypass the boundary being tested.
        $anonKey = [string]$config.SUPABASE_ANON_KEY
        $headers = @{ apikey = $anonKey }
        if ($anonKey.StartsWith('eyJ')) {
            try {
                $part = $anonKey.Split('.')[1].Replace('-', '+').Replace('_', '/')
                $part = $part.PadRight($part.Length + (4 - $part.Length % 4) % 4, '=')
                $claims = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($part)) | ConvertFrom-Json
                if ($claims.role -ne 'anon') { throw 'Wrong role' }
            } catch { throw 'SUPABASE_ANON_KEY must be an anon JWT or publishable key, never a service-role key.' }
            $headers.Authorization = "Bearer $anonKey"
        } elseif ($anonKey.StartsWith('sb_publishable_')) {
            $headers.Authorization = "Bearer $anonKey"
        } else {
            throw 'SUPABASE_ANON_KEY format is unsupported.'
        }
        $tables = @('users','user_devices','otp_challenges','auth_sessions','auth_throttles',
            'contact_requests','contacts','conversations','conversation_members','attachments','calls','messages','sync_events')
        foreach ($table in $tables) {
            try {
                $uri = $config.SUPABASE_URL.TrimEnd('/') + "/rest/v1/" + $table + '?select=*&limit=1'
                $response = Invoke-WebRequest -Uri $uri -Headers $headers -UseBasicParsing -TimeoutSec 10
                if ($response.StatusCode -eq 200 -and $response.Content.Trim() -eq '[]') {
                    Report $true "Anon sees no rows in $table (confirm RLS with verify-schema.sql)"
                } else { Report $false "Unexpected public response for $table; response body withheld" }
            } catch {
                $httpStatus = 0
                if ($_.Exception.PSObject.Properties['Response'] -and $null -ne $_.Exception.Response) {
                    $httpStatus = [int]$_.Exception.Response.StatusCode
                }
                if ($httpStatus -eq 403 -or $httpStatus -eq 401) { Report $true "Anon access denied for $table (HTTP $httpStatus)" }
                else { Report $false "PostgREST probe for $table inconclusive (HTTP $httpStatus); check key, network and SQL" }
            }
        }
        Write-Output '[NOTE] TCP reachability is not a SQL login or Flyway migration test.'
        Write-Output '[NOTE] Empty HTTP results alone do not prove RLS on an empty database.'
    } else {
        Write-Output '[NOTE] Quick is local validation only; cloud checks were not run.'
    }
} catch {
    # Do not print exception bodies: HTTP/configuration exceptions may contain secrets.
    Write-Output '[FAIL] Check configuration, environment file format and prerequisites; sensitive details withheld.'
    $failures++
}
if ($failures) { Write-Output "[FAIL] $failures check(s) failed"; exit 1 }
Write-Output '[PASS] All requested checks passed'
exit 0
