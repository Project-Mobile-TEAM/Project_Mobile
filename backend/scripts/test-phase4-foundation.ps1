[CmdletBinding()]
param([string]$Maven = 'mvn', [switch]$ContactsApi, [switch]$RequestsApi, [switch]$FriendsApi, [switch]$BlocksApi, [switch]$AndroidContract)

# Fixture tự cấp PostgreSQL nhúng; script chỉ chọn bộ test và đối chiếu XML, không cần Docker.
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runId = 'embedded-' + [Guid]::NewGuid().ToString('N')
$exitCode = 1
Push-Location $repoRoot
try {
    # Giữ JDK/Maven đang được Terminal chọn; không tự hạ phiên bản khi chưa có lỗi tương thích.
    & $Maven -version
    if ($LASTEXITCODE -ne 0) { throw 'Không chạy được Maven; truyền -Maven với đường dẫn mvn.cmd thực tế.' }
    $expected = @{
        'com.utechat.backend.contacts.ContactsMigrationTest' = 6
        'com.utechat.backend.config.FlywayMigrationTest' = 3
        'com.utechat.backend.LocalDevServerSmokeTest' = 1
        'com.utechat.backend.contacts.ContactPostgresFoundationTest' = 12
        'com.utechat.backend.contacts.ContactJwtPostgresTest' = 8
        'com.utechat.backend.support.PostgresFixtureGuardTest' = 9
        'com.utechat.backend.contacts.ContactPolicyServiceTest' = 8
        'com.utechat.backend.contacts.SyncEventWriterTest' = 7
        'com.utechat.backend.contacts.ContactDtosTest' = 9
        'com.utechat.backend.contacts.LookupRateLimiterTest' = 8
        'com.utechat.backend.contacts.MissingPhoneParameterTest' = 4
    }
    # Các bộ nghiệp vụ sau kế thừa API/body 03–04 cùng 73 test nền.
    if ($ContactsApi -or $RequestsApi -or $FriendsApi -or $BlocksApi -or $AndroidContract) {
        $expected['com.utechat.backend.contacts.ContactLookupApiTest'] = 22
        $expected['com.utechat.backend.contacts.ContactSendApiTest'] = 20
        $expected['com.utechat.backend.contacts.ContactInputValidationTest'] = 2
    }
    # Mục 05–06 chạy cùng toàn bộ hồi quy 01–04; không chọn suite nháp của API 07–09.
    if ($RequestsApi -or $FriendsApi -or $BlocksApi -or $AndroidContract) {
        $expected['com.utechat.backend.contacts.ContactRequestListApiTest'] = 17
        $expected['com.utechat.backend.contacts.ContactAcceptApiTest'] = 25
    }
    # Mục 07–08 thêm reject/cancel và friends/unfriend; không chọn suite nháp của API block 09.
    if ($FriendsApi -or $BlocksApi -or $AndroidContract) {
        $expected['com.utechat.backend.contacts.ContactRequestActionsApiTest'] = 27
        $expected['com.utechat.backend.contacts.ContactFriendsApiTest'] = 29
    }
    # API chặn dùng HTTP thật, bao gồm các race từng dùng fixture SQL ở 06/08.
    if ($BlocksApi -or $AndroidContract) {
        $expected['com.utechat.backend.contacts.ContactBlockApiTest'] = 34
    }
    # Một ca chạy đủ 58 unit app bằng JUnit4, ca còn lại gọi 11 endpoint qua Tomcat/JWT/PostgreSQL.
    if ($AndroidContract) {
        $expected['com.utechat.backend.AndroidContactContractTest'] = 2
    }
    $tests = ($expected.Keys | ForEach-Object { ($_ -split '\.')[-1] }) -join ','
    # Giữ artifact lần trước trước khi Surefire ghi báo cáo của lần chạy mới.
    $reportsPath = Join-Path $repoRoot 'backend/target/surefire-reports'
    if (Test-Path -LiteralPath $reportsPath) {
        $backupPath = Join-Path $repoRoot ("backend/target/phase4-report-backups/" + $runId)
        New-Item -ItemType Directory -Force $backupPath | Out-Null
        Get-ChildItem -LiteralPath $reportsPath -File | Copy-Item -Destination $backupPath
        Write-Output ("Đã sao lưu báo cáo lần trước vào: " + $backupPath)
    }
    $testStart = [DateTime]::UtcNow
    & $Maven -B -ntp -f backend/pom.xml "-Dtest=$tests" test
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) { throw "Maven test thất bại, mã thoát $exitCode; xem backend/target/surefire-reports." }
    # Không dùng exit code 0 hoặc XML cũ để nhận nhầm test bị bỏ qua là đã nghiệm thu.
    $total = 0
    foreach ($suite in $expected.Keys) {
        $report = Get-Item -LiteralPath (Join-Path $repoRoot "backend/target/surefire-reports/TEST-$suite.xml")
        if ($report.LastWriteTimeUtc -lt $testStart) { throw "Báo cáo cũ không thuộc lần chạy hiện tại: $suite" }
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
        $result = $xml.testsuite
        if ([int]$result.tests -lt $expected[$suite] -or [int]$result.failures -ne 0 -or [int]$result.errors -ne 0 -or [int]$result.skipped -ne 0) {
            throw "Chưa đủ test hoặc có failure/error/skipped trong $suite"
        }
        $total += [int]$result.tests
        Write-Output ("PASS {0}: {1} test, không bỏ qua" -f $suite, $result.tests)
    }
    $scopeLabel = if ($AndroidContract) { '09–10 cùng hồi quy 01–08 (gồm 58 unit Android lồng trong một test)' } elseif ($BlocksApi) { '09 cùng hồi quy 01–08' } elseif ($FriendsApi) { '07–08 cùng hồi quy 01–06' } elseif ($RequestsApi) { '05–06 cùng hồi quy 01–04' } elseif ($ContactsApi) { '03–04 cùng hồi quy 01–02' } else { '01–02' }
    Write-Output ("PASS tổng {0} test trong bộ {1}; XML ở backend/target/surefire-reports." -f $total, $scopeLabel)
} catch {
    $exitCode = 1
    Write-Error -Message $_.Exception.Message -ErrorAction Continue
} finally {
    # Fixture dọn database và Zonky dọn engine; không sửa môi trường hoặc dịch vụ PostgreSQL của máy.
    Pop-Location
}
exit $exitCode
