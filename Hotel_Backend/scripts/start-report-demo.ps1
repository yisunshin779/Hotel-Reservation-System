param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\18\bin',
    [switch]$DatabaseOnly
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$psql = Join-Path $PostgresBin 'psql.exe'
if (!(Test-Path -LiteralPath $psql)) { throw "PostgreSQL tools not found at $PostgresBin" }
if (!$env:DB_PASSWORD) { throw 'Set DB_PASSWORD to your local postgres password before running this script' }
$oldPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $env:DB_PASSWORD
    $connection = @('-h','127.0.0.1','-p','5432','-U','postgres','-w','-v','ON_ERROR_STOP=1')
    $exists = & $psql @connection '-d' 'postgres' '-Atc' "SELECT 1 FROM pg_database WHERE datname='hotel_report_demo'"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot connect to local PostgreSQL on port 5432' }
    if ($exists -ne '1') {
        & (Join-Path $PostgresBin 'createdb.exe') '-h' '127.0.0.1' '-p' '5432' '-U' 'postgres' '-w' 'hotel_report_demo'
        if ($LASTEXITCODE -ne 0) { throw 'Could not create hotel_report_demo' }
        foreach ($file in @('01_schema.sql','02_operations.sql','03_seed.sql','05_report_demo.sql')) {
            & $psql @connection '-d' 'hotel_report_demo' '-f' (Join-Path $repo "Hotel_Database/sql/$file")
            if ($LASTEXITCODE -ne 0) { throw "Initialization failed at $file; inspect the dedicated demo database before retrying" }
        }
    }
    # Existing databases are never reset or re-seeded automatically.
    $ready = & $psql @connection '-d' 'hotel_report_demo' '-Atc' "SELECT count(*) FROM hotel.invoices WHERE invoice_number LIKE 'REPORT-DEMO-%'"
    if ($LASTEXITCODE -ne 0 -or [int]$ready -ne 8) { throw 'Demo database exists but is not fully initialized; inspect it before continuing' }
    & $psql @connection '-d' 'hotel_report_demo' '-f' (Join-Path $repo 'Hotel_Database/sql/06_report_demo_details.sql')
    if ($LASTEXITCODE -ne 0) { throw 'UC28 demo extension failed' }
} finally { $env:PGPASSWORD = $oldPassword }
Write-Host 'Report demo database ready: 127.0.0.1:5432/hotel_report_demo'
if ($DatabaseOnly) { return }
if (!$env:REPORT_DEMO_PASSWORD) {
    $env:REPORT_DEMO_PASSWORD = [Guid]::NewGuid().ToString('N').Substring(0,16)
    Write-Host ('Generated report demo password: ' + $env:REPORT_DEMO_PASSWORD)
}
Write-Host 'Report demo accounts: admin-report@example.test / manager-report@example.test'
$jar = Join-Path $repo 'Hotel_Backend/target/hotel-backend-0.0.1-SNAPSHOT.jar'
if (!(Test-Path -LiteralPath $jar)) { throw 'Build the backend first: mvn -DskipTests package' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $java '-jar' $jar '--spring.profiles.active=report-demo'
if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }