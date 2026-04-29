$ErrorActionPreference = "Stop"

$adb = "C:\Users\simo\AppData\Local\Android\Sdk\platform-tools\adb.exe"

Write-Host "Avvio dell'app sul dispositivo..." -ForegroundColor Cyan
& $adb shell monkey -p com.ssimo.papere -c android.intent.category.LAUNCHER 1 | Out-Null

Start-Sleep -Seconds 1

$pid_out = & $adb shell pidof -s com.ssimo.papere
$pid_out = $pid_out.Trim()

if ($pid_out) {
    Write-Host "App avviata con PID: $pid_out. In ascolto dei log..." -ForegroundColor Green
    & $adb logcat -v color --pid=$pid_out
} else {
    Write-Host "Impossibile trovare il PID dell'app. L'app si è chiusa o non è partita." -ForegroundColor Red
}
