# Attach an Android device to WSL2 via usbipd.
# Run from an elevated (Administrator) PowerShell prompt.

if (-not (Get-Command usbipd -ErrorAction SilentlyContinue)) {
    Write-Error "usbipd not found. Install it with: winget install --interactive --exact dorssel.usbipd-win"
    exit 1
}

Write-Host ""
Write-Host "Connected USB devices:"
Write-Host ""
usbipd list
Write-Host ""

# Try to auto-detect an Android device by Google's USB vendor ID (18d1) or "Android" in the name
$androidDevice = usbipd list | Select-String -Pattern "(18d1:|Android)" | Select-Object -First 1

if ($androidDevice) {
    $busid = ($androidDevice -split "\s+")[0]
    Write-Host "Auto-detected Android device at BUSID $busid"
    Write-Host ""
} else {
    $busid = Read-Host "Enter BUSID of your device (e.g. 1-3)"
}

Write-Host "Binding $busid (requires elevation, one-time per device)..."
usbipd bind --busid $busid --force

Write-Host "Attaching $busid to WSL..."
usbipd attach --wsl --busid $busid

Write-Host ""
Write-Host "Done. In WSL, run: adb devices"
