#!/bin/bash
# Fix "device visible in lsusb but not in adb devices" on WSL2.
#
# Root cause: WSL2 doesn't run systemd/udev by default, so USB device
# nodes get created with root-only permissions (0600) and nothing ever
# relaxes them for adb. This enables systemd in WSL2 and installs a udev
# rule granting the plugdev group RW access to Android (Google, vendor
# ID 18d1) USB devices.
#
# Requires a `wsl --shutdown` (run from Windows PowerShell/CMD, not from
# inside WSL) before it takes effect - this restarts the WSL VM and kills
# any other running WSL processes/distros, so this script does NOT do it
# for you. Run it when that's safe, then reconnect your device.
set -e

if ! grep -qi microsoft /proc/version 2>/dev/null; then
    echo "This doesn't look like WSL2 - aborting to avoid touching an unrelated system."
    exit 1
fi

WSL_CONF=/etc/wsl.conf
UDEV_RULE=/etc/udev/rules.d/51-android.rules

echo "==> Ensuring systemd is enabled in $WSL_CONF..."
if [ -f "$WSL_CONF" ] && grep -q "^\[boot\]" "$WSL_CONF"; then
    if grep -q "^systemd=true" "$WSL_CONF"; then
        echo "    Already enabled."
    else
        echo "    $WSL_CONF already has a [boot] section but no systemd=true."
        echo "    Add 'systemd=true' under [boot] in $WSL_CONF manually, then re-run this script."
        exit 1
    fi
else
    sudo tee -a "$WSL_CONF" > /dev/null <<'WSLCONF'
[boot]
systemd=true
WSLCONF
    echo "    Added."
fi

echo "==> Installing Android udev rule at $UDEV_RULE..."
sudo tee "$UDEV_RULE" > /dev/null <<'UDEVRULES'
# Google (Pixel) devices - allow plugdev group RW access for adb
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0666", GROUP="plugdev"
UDEVRULES
echo "    Installed."

echo ""
echo "Done. Nothing changes until you restart the WSL VM:"
echo "  1. From Windows PowerShell/CMD (not inside WSL): wsl --shutdown"
echo "  2. Reopen your WSL terminal"
echo "  3. Reconnect/reattach your device (see attach-device.ps1 if using usbipd)"
echo "  4. adb devices -l"
