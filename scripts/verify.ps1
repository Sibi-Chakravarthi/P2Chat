# Pre-push verification gate for P2Chat (T0.6)
# Enforces: Wire Parity, Vector Invariance, JVM Unit Tests, Lint, and Asset Size Budget.

$ErrorActionPreference = "Stop"

# Ensure we are running from the repository root
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Get-Item $ScriptDir).Parent.FullName
Set-Location $RepoRoot

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "P2Chat Pre-Push Verification Gate (T0.6)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Working Directory: $RepoRoot`n"

# Step 1: Wire Parity Check
Write-Host "[1/5] Checking Wire Parity (Kotlin <-> Python)..." -ForegroundColor Yellow
& uv run python scripts/check_wire_parity.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Wire parity check failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Wire parity verified.`n" -ForegroundColor Green

# Step 2: Test Vector Invariance Check
Write-Host "[2/5] Checking Test Vector Regeneration & Invariance..." -ForegroundColor Yellow
$VectorFile = Join-Path $RepoRoot "app/src/test/resources/vectors/crypto_vectors.json"
$TempBackup = [System.IO.Path]::GetTempFileName()

if (Test-Path $VectorFile) {
    Copy-Item $VectorFile $TempBackup
    $InitialHash = (Get-FileHash -Algorithm SHA256 $VectorFile).Hash
} else {
    $InitialHash = ""
}

& uv run python scripts/gen_test_vectors.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Vector generation failed." -ForegroundColor Red
    if (Test-Path $TempBackup) {
        Copy-Item $TempBackup $VectorFile -Force
        Remove-Item $TempBackup -Force
    }
    exit 1
}

$NewHash = (Get-FileHash -Algorithm SHA256 $VectorFile).Hash

if ($InitialHash -ne "" -and $InitialHash -ne $NewHash) {
    Write-Host "`n[FAILED] Committed test vectors do not match deterministic generator output!" -ForegroundColor Red
    Write-Host "Expected SHA256: $InitialHash" -ForegroundColor Red
    Write-Host "Actual SHA256:   $NewHash" -ForegroundColor Red
    # Restore the previous file state
    Copy-Item $TempBackup $VectorFile -Force
    Remove-Item $TempBackup -Force
    exit 1
}

if (Test-Path $TempBackup) {
    Remove-Item $TempBackup -Force
}
Write-Host "PASS: Crypto test vectors are invariant and verified.`n" -ForegroundColor Green

# Step 3: Pure JVM Unit Tests
Write-Host "[3/6] Running Kotlin JVM Unit Tests (testDebugUnitTest)..." -ForegroundColor Yellow
& .\gradlew.bat testDebugUnitTest
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Kotlin unit tests failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Kotlin unit tests passed.`n" -ForegroundColor Green

# Step 4: Cross-Language Verification (Kotlin -> Python Parity)
Write-Host "[4/6] Verifying Kotlin Vectors in Python (pycryptodome)..." -ForegroundColor Yellow
& uv run python scripts/verify_kotlin_vectors.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Kotlin -> Python vector verification failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Kotlin vectors independently verified in Python.`n" -ForegroundColor Green

# Step 5: Android Lint
Write-Host "[5/7] Running Android Lint (lintDebug)..." -ForegroundColor Yellow
& .\gradlew.bat lintDebug
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Android lint checks failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Android lint checks passed.`n" -ForegroundColor Green

# Step 6: Secure Logging Guard (SEC-16)
Write-Host "[6/7] Checking Secure Logging Guard (SEC-16)..." -ForegroundColor Yellow
& uv run python scripts/check_secure_log.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Secure logging guard check failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Secure logging guard verified.`n" -ForegroundColor Green

# Step 7: Asset Size Budget Check
Write-Host "[7/7] Checking On-Device AI Asset Budget (< 30 MB)..." -ForegroundColor Yellow
& uv run python scripts/check_asset_size.py
if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[FAILED] Asset size budget check failed." -ForegroundColor Red
    exit 1
}
Write-Host "PASS: Asset budget verified.`n" -ForegroundColor Green

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "ALL PRE-PUSH CHECKS PASSED!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
exit 0
