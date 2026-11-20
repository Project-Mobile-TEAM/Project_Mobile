[CmdletBinding()]
param([string]$DeviceSerial, [switch]$Repository, [switch]$ContactsUi,
    [ValidateSet('Focus','Suite')][string]$UiCheck)

# Mặc định 11/14; -Repository thêm 12/13; -ContactsUi thêm UI 15–19 cùng toàn bộ hồi quy này.
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$oldSerial = $env:ANDROID_SERIAL
$exitCode = 1
Push-Location $repo
try {
    if ($UiCheck -and ($Repository -or $ContactsUi)) { throw '-UiCheck chạy riêng, không ghép với -Repository hoặc -ContactsUi.' }
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
        'com.utechat.data.repository.RealAuthRepositoryTest' = 12
        'com.utechat.data.repository.FakeRepositoriesTest' = 6
        'com.utechat.data.repository.FakeContactsSessionTest' = 8
        'com.utechat.data.remote.model.ContactDtosTest' = 10
        'com.utechat.data.remote.api.ContactApiTest' = 16
        'com.utechat.data.repository.ContactErrorMapperTest' = 22
        'com.utechat.data.remote.api.ContactTransportTest' = 10
    }
    $device = @{
        'com.utechat.ContactsRoomTest' = 10
        'com.utechat.data.session.SessionManagerTest' = 15
        # Main Looper thật: giữ thứ tự cập nhật phiên khi thay API nội bộ AndroidX.
        'com.utechat.data.MainThreadExecutorTest' = 2
        'com.utechat.data.local.RoomFoundationTest' = 3
    }
    if ($Repository -or $ContactsUi) {
        $unit['com.utechat.data.repository.RealContactsRepositoryTest'] = 43
        $device['com.utechat.data.repository.ContactsRepositoryRoomTest'] = 12
    }
    if ($ContactsUi) {
        $unit['com.utechat.ui.contacts.ContactsViewModelTest'] = 31
        # Bao gồm định dạng SĐT và năm luồng thao tác trên ba màn con mới.
        $unit['com.utechat.ui.contacts.ContactsTextTest'] = 5
        $device['com.utechat.ui.contacts.ContactsListUiTest'] = 17
    }
    # Chẩn đoán theo hai bước: một ca đại diện trước, cả lớp UI sau; chưa chạy bộ đầy đủ.
    $focusMethod = 'listsFriendsAndFiltersNameWithoutChangingCache'
    if ($UiCheck) {
        $unit = @{}
        $device = @{ 'com.utechat.ui.contacts.ContactsListUiTest' = $(if ($UiCheck -eq 'Focus') { 1 } else { 17 }) }
    }
    $scope = if ($UiCheck) { "UI $UiCheck của 15–19, chưa nghiệm thu toàn đợt" } elseif ($ContactsUi) { '15–19 cùng hồi quy 11–14 và hợp đồng client' } elseif ($Repository) { '12/13 cùng hồi quy 11/14 và hợp đồng client' } else { '11/14' }
    $unitTotal = [int]($unit.Values | Measure-Object -Sum).Sum
    $deviceTotal = ($device.Values | Measure-Object -Sum).Sum
    $unitPath = 'app/build/test-results/testDebugUnitTest'
    $devicePath = 'app/build/outputs/androidTest-results/connected'
    $cacheFolder = if ($ContactsUi -or $UiCheck) { 'phase4-15-16' } elseif ($Repository) { 'phase4-12-13' } else { 'phase4-11-14' }
    $backup = Join-Path $repo ('.task-cache/' + $cacheFolder + '/windows-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force $backup | Out-Null

    # ZIP giữ đường dẫn tương đối bên trong archive, tránh Copy-Item tạo đường dẫn đích >= MAX_PATH ở PS 5.1.
    function Backup-Reports($path, $archivePath) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $root = (Resolve-Path -LiteralPath $path).ProviderPath.TrimEnd([IO.Path]::DirectorySeparatorChar)
        [IO.Compression.ZipFile]::CreateFromDirectory($root, $archivePath, [IO.Compression.CompressionLevel]::NoCompression, $false)
        $zip = [IO.Compression.ZipFile]::OpenRead($archivePath)
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            $files = @(Get-ChildItem -LiteralPath $root -Recurse -File -Force)
            # .NET Framework của PS 5.1 có thể ghi tên entry bằng '\', chuẩn hóa chỉ để đối chiếu.
            $entries = @{}
            foreach ($entry in $zip.Entries) {
                $name = $entry.FullName.Replace('\', '/')
                if ($name.EndsWith('/')) { continue }
                if ($entries.ContainsKey($name)) { throw "Backup ZIP trùng entry: $name" }
                $entries[$name] = $entry
            }
            if ($entries.Count -ne $files.Count) { throw 'Backup ZIP thiếu hoặc thừa tệp.' }
            foreach ($file in $files) {
                $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
                $entry = $entries[$relative]
                if ($null -eq $entry -or $entry.Length -ne $file.Length) { throw "Backup ZIP không khớp: $relative" }
                # Đối chiếu nội dung từng entry với nguồn trước khi cho Gradle ghi báo cáo mới.
                $source = [IO.File]::OpenRead($file.FullName)
                try { $sourceHash = [BitConverter]::ToString($sha.ComputeHash($source)) } finally { $source.Dispose() }
                $saved = $entry.Open()
                try { $savedHash = [BitConverter]::ToString($sha.ComputeHash($saved)) } finally { $saved.Dispose() }
                if ($sourceHash -ne $savedHash) { throw "SHA-256 backup không khớp: $relative" }
            }
            Write-Output ("Backup ZIP đã đối chiếu {0} tệp: {1}" -f $files.Count, $archivePath)
        } finally { $sha.Dispose(); $zip.Dispose() }
    }
    foreach ($path in @($unitPath, $devicePath, 'app/build/outputs/connected_android_test_additional_output')) {
        if (Test-Path -LiteralPath $path) {
            Backup-Reports $path (Join-Path $backup ((Split-Path $path -Leaf) + '.zip'))
        }
    }
    Write-Output ('Backup kết quả cũ: ' + $backup)
    # Hash mọi source/resource của app (kể cả nháp đang có), không đọc .env; phát hiện sửa trong lúc build/test.
    function Get-SourceFingerprint {
        $files = @(Get-ChildItem -LiteralPath 'app/src' -Recurse -File | Where-Object { $_.Name -notlike '.env*' })
        $files += @(Get-Item app/build.gradle.kts,gradle/libs.versions.toml,gradle.properties,settings.gradle.kts,build.gradle.kts,scripts/test-phase4-contacts-foundation.ps1)
        @($files | Sort-Object FullName | ForEach-Object {
            [pscustomobject]@{ path = $_.FullName.Substring($repo.Length + 1); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
    }
    if ($UiCheck) {
        $sourceBefore = @(Get-SourceFingerprint)
        $sourceBefore | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $backup 'source-before.json') -Encoding UTF8
    }

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
    if (-not $UiCheck) {
        $unitArgs = @('--no-daemon', '--console=plain', '--rerun-tasks', ':app:testDebugUnitTest')
        foreach ($class in $unit.Keys) { $unitArgs += @('--tests', $class) }
        $start = [DateTime]::UtcNow
        & .\gradlew.bat @unitArgs
        if ($LASTEXITCODE -ne 0) { throw "Bộ JVM thất bại; chưa nghiệm thu $scope." }
        Assert-Reports $unitPath $unit $start
    }

    $start = [DateTime]::UtcNow
    $classes = if ($UiCheck -eq 'Focus') { 'com.utechat.ui.contacts.ContactsListUiTest#' + $focusMethod } else { $device.Keys -join ',' }
    $deviceArgs = @('--no-daemon', '--console=plain', '--rerun-tasks', ':app:connectedDebugAndroidTest', "-Pandroid.testInstrumentationRunnerArguments.class=$classes", "-Pandroid.injected.device.serial=$DeviceSerial")
    if ($UiCheck) {
        $deviceArgs += @('-Pandroid.testInstrumentationRunnerArguments.phase4UiDiagnostics=true', '-Pandroid.enableAdditionalTestOutput=true')
        & .\gradlew.bat @deviceArgs | Tee-Object -FilePath (Join-Path $backup 'gradle-ui.log')
    } else { & .\gradlew.bat @deviceArgs }
    $buildExitCode = $LASTEXITCODE
    if ($UiCheck) {
        $sourceAfter = @(Get-SourceFingerprint)
        $sourceAfter | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $backup 'source-after.json') -Encoding UTF8
        if (Compare-Object @($sourceBefore | ForEach-Object { $_.path + '=' + $_.sha256 }) @($sourceAfter | ForEach-Object { $_.path + '=' + $_.sha256 })) { throw 'Source đổi trong lúc build/test; không dùng kết quả này nghiệm thu.' }
        $apks = @(Get-Item app/build/outputs/apk/debug/app-debug.apk,app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk)
        $apks | ForEach-Object { [pscustomobject]@{path=$_.FullName;sha256=(Get-FileHash -LiteralPath $_.FullName).Hash;modifiedUtc=$_.LastWriteTimeUtc.ToString('o')} } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $backup 'apks.json') -Encoding UTF8
        if (@($apks | Where-Object { $_.LastWriteTimeUtc -lt $start }).Count) { throw 'APK chưa được build mới trong lượt này; không dùng APK cũ để kết luận.' }
        Write-Output ('Chẩn đoán tại app/build/outputs/connected_android_test_additional_output; manifest/APK/log tại ' + $backup)
    }
    if ($buildExitCode -ne 0) { throw "Bộ Android instrumentation thất bại; chưa nghiệm thu $scope." }
    Assert-Reports $devicePath $device $start
    if ($UiCheck -eq 'Focus') {
        $cases = @(Get-ChildItem -LiteralPath $devicePath -Recurse -Filter 'TEST-*.xml' -File | ForEach-Object { [xml]$xml = Get-Content -LiteralPath $_.FullName -Raw; $xml.SelectNodes('//testcase') })
        if ($cases.Count -ne 1 -or [string]$cases[0].name -ne $focusMethod) { throw 'Không phải đúng một method UI đại diện đã chọn.' }
    }
    if ($UiCheck) { Write-Output "PASS $deviceTotal UI ($UiCheck); chưa chạy bộ đầy đủ, chưa đủ điều kiện commit." }
    else { Write-Output "PASS $unitTotal JVM + $deviceTotal Android instrumentation ($scope); failures/errors/skipped=0. Chờ đối chiếu XML/source trước commit." }
    $exitCode = 0
} catch {
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
} finally {
    $env:ANDROID_SERIAL = $oldSerial
    Pop-Location
}
exit $exitCode
