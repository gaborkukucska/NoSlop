#!/usr/bin/env bash
# Build the NoSlop KMP iOS app and install it on a connected iPhone over USB/Wi-Fi.
#
# One-time on the phone: Settings -> Privacy & Security -> Developer Mode -> on (phone restarts).
# First launch: Settings -> General -> VPN & Device Management -> trust the developer cert.
# Free-team provisioning expires after 7 days; just re-run this script to refresh.
#
# Usage:  ./install-on-iphone.sh            # picks the first connected iPhone
#         ./install-on-iphone.sh <DEVICE-ID> # a CoreDevice identifier from `xcrun devicectl list devices`
set -euo pipefail
cd "$(dirname "$0")"

TEAM="${DEVELOPMENT_TEAM:-HAT6Q5RYC6}"
BUNDLE_ID="com.noslop.app.ios"
DD="build/dd"
APP="$DD/Build/Products/Debug-iphoneos/iosApp.app"

# `devicectl list devices` columns: Name  Hostname  Identifier  State  Model. Names and models contain
# spaces, so pick the Identifier by its UUID shape rather than by column number.
uuid_re='[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}'
devices=$(xcrun devicectl list devices 2>/dev/null || true)
device="${1:-}"
if [ -z "$device" ]; then
  row=$(printf '%s\n' "$devices" | grep -i iphone | grep -E 'connected' | head -1 || true)
  [ -n "$row" ] || row=$(printf '%s\n' "$devices" | grep -i iphone | head -1 || true)
  device=$(printf '%s\n' "$row" | grep -oE "$uuid_re" | head -1 || true)
fi
[ -n "$device" ] || { echo "No connected iPhone found. Plug it in, unlock it, and run: xcrun devicectl list devices"; exit 1; }
echo "==> Target device: $device"

# "connected (no DDI)" means this Xcode has no developer disk image for the phone's iOS version; the
# build will succeed but the install fails with CoreDeviceError 12040. Newer Xcode, or TestFlight.
if printf '%s\n' "$devices" | grep -F "$device" | grep -q 'no DDI'; then
  echo "WARNING: Xcode has no Developer Disk Image for this iPhone's iOS version (state: 'no DDI')."
  echo "         Install will fail. Update Xcode to a version that supports this iOS, or use ./testflight-upload.sh."
fi

if xcrun devicectl device info details --device "$device" 2>/dev/null | grep -q 'developerModeStatus: disabled'; then
  echo "Developer Mode is OFF on this iPhone. Enable it: Settings -> Privacy & Security -> Developer Mode, then re-run."
  exit 2
fi

# CocoaPods must be integrated into the project (xcodegen regenerates the .xcodeproj without it).
if ! grep -q baseConfigurationReference iosApp.xcodeproj/project.pbxproj; then
  echo "==> Pods not integrated into the Xcode project; running pod install"
  pod install
fi

# Cloud-synced folders add Finder xattrs that make codesign fail on the embedded Tor.framework.
[ -d "$DD/Build/Products" ] && xattr -rc "$DD/Build/Products" 2>/dev/null || true
xattr -rc Pods/Tor 2>/dev/null || true

echo "==> Building (this registers the device with the team and refreshes the provisioning profile)"
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination "id=$device" -derivedDataPath "$DD" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM="$TEAM" EAGER_LINKING=NO \
  build | grep -E 'error:|warning: .*provision|BUILD (SUCCEEDED|FAILED)' || true

[ -d "$APP" ] || { echo "Build produced no app bundle at $APP"; exit 3; }

echo "==> Installing $APP"
xcrun devicectl device install app --device "$device" "$APP"
echo "==> Launching"
xcrun devicectl device process launch --device "$device" "$BUNDLE_ID" || echo "Launch failed; open NoSlop MVP from the home screen (trust the developer cert first if prompted)."
