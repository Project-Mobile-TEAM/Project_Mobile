[CmdletBinding()]
param([string]$DeviceSerial)

# Mục 11/14: chạy JVM và Room/Keystore thật; không thay ExecutionPolicy, không stage/commit.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$oldSerial = $env:ANDROID_SERIAL
$exitCode = 1
Push-Location $repo
try {
    # Chọn đúng một thiết bị sẵn có; không mở emulator hoặc chạy trùng một bản test khác.
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    $adb = Join-Path $sdk 'platform-tools/adb.exe'
    $devices = @(& $adb devices)
    if ($LASTEXITCODE -ne 0) { throw 'Không truy cập được ADB; mở thiết bị/emulator rồi thử lại.' }
    $ready = @($devices | Where-Object { $_ -match '^\S+\s+device\s*$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($DeviceSerial) {
        if ($ready -notcontains $DeviceSerial) { throw 'Thiết bị đã chọn chưa sẵn sàng hoặc chưa cấp quyền ADB.' }
    } elseif ($ready.Count -eq 1) { $DeviceSerial = $ready[0] }
    else { throw 'Cần đúng một thiết bị sẵn sàng hoặc truyền -DeviceSerial.' }
    $env:ANDROID_SERIAL = $DeviceSerial

    # Danh sách cố định để không dùng XML cũ hoặc bỏ qua ca khó mà vẫn báo đạt.
    $unit = @{
        'com.utechat.data.repository.RealAuthRepositoryTest' = 11
        'com.utechat.data.repository.FakeRepositoriesTest' = 6
        'com.utechat.data.repository.FakeContactsSessionTest' = 8
        'com.utechat.data.remote.model.ContactDtosTest' = 10
        'com.utechat.data.remote.api.ContactApiTest' = 16
        'com.utechat.data.repository.ContactErrorMapperTest' = 22
        'com.utechat.data.remote.api.ContactTransportTest' = 10
    }
    $device = @{
        'com.utechat.ContactsRoomTest' = 10
        'com.utechat.data.session.SessionManagerTest' = 14
        'com.utechat.data.local.RoomFoundationTest' = 3
    }
    $unitPath = 'app/build/test-results/testDebugUnitTest'
    $devicePath = 'app/build/outputs/androidTest-results/connected'
    $backup = Join-Path $repo ('.task-cache/phase4-11-14/windows-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $backup | Out-Null
    foreach ($path in @($unitPath, $devicePath)) {
        if (Test-Path -LiteralPath $path) {
            Copy-Item -LiteralPath $path -Destination (Join-Path $backup (Split-Path $path -Leaf)) -Recurse
        }
    }
    Write-Output ('Backup kết quả cũ: ' + $backup)

    function Assert-Reports($path, $expected, $start) {
        $files = @(Get-ChildItem -LiteralPath $path -Recurse -Filter 'TEST-*.xml' -File)
        if ($files.Count -eq 0) { throw "Không có XML mới tại $path" }
        $counts = @{}
        $names = @{}
        foreach ($file in $files) {
            if ($file.LastWriteTimeUtc -lt $start) { throw "XML cũ: $($file.Name)" }
            [xml]$xml = Get-Content -LiteralPath $file.FullName -Raw
            foreach ($suite in $xml.SelectNodes('//testsuite')) {
                if ([int]$suite.tests -ne $suite.SelectNodes('.//testcase').Count) { throw 'Số testcase không khớp summary XML.' }
                if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
                    throw "Suite có failure/error/skipped: $($suite.name)"
                }
            }
            if ($xml.SelectNodes('//failure|//error|//skipped').Count -ne 0) { throw 'Có testcase lỗi hoặc bị bỏ qua.' }
            foreach ($case in $xml.SelectNodes('//testcase')) {
                $class = [string]$case.classname
                if (-not $expected.ContainsKey($class)) { throw "Suite ngoài bộ đã chọn: $class" }
                $key = $class + '/' + [string]$case.name
                if ($names.ContainsKey($key)) { throw "Testcase trùng: $key" }
                $names[$key] = $true
                $counts[$class] = 1 + [int]$counts[$class]
            }
        }
        foreach ($class in $expected.Keys) {
            if ([int]$counts[$class] -ne $expected[$class]) { throw "Sai hoặc thiếu số testcase: $class" }
            Write-Output ("PASS {0}: {1}, không bỏ qua" -f $class, $counts[$class])
        }
    }

    # --rerun-tasks buộc tạo kết quả mới; giữ thư viện/JDK của project và không dùng repo thử của agent.
    $unitArgs = @('--no-daemon', '--console=plain', '--rerun-tasks', ':app:testDebugUnitTest')
    foreach ($class in $unit.Keys) { $unitArgs += @('--tests', $class) }
    $start = [DateTime]::UtcNow
    & .\gradlew.bat @unitArgs
    if ($LASTEXITCODE -ne 0) { throw 'Bộ JVM thất bại; chưa nghiệm thu 11/14.' }
    Assert-Reports $unitPath $unit $start

    $start = [DateTime]::UtcNow
    $classes = $device.Keys -join ','
    & .\gradlew.bat --no-daemon --console=plain --rerun-tasks :app:connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$classes" "-Pandroid.injected.device.serial=$DeviceSerial"
    if ($LASTEXITCODE -ne 0) { throw 'Room/Keystore trên thiết bị thất bại; chưa nghiệm thu 11/14.' }
    Assert-Reports $devicePath $device $start
    Write-Output 'PASS 83 JVM + 27 Android instrumentation; failures/errors/skipped=0. Chờ đối chiếu XML/source trước commit.'
    $exitCode = 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
} finally {
    $env:ANDROID_SERIAL = $oldSerial
    Pop-Location
}
exit $exitCode
