#!/usr/bin/env bash
# Archive the NoSlop KMP iOS app, export it for App Store Connect, and upload it to TestFlight.
#
# Prerequisites (one-time):
#   1. Paid Apple Developer Program membership for the team in DEVELOPMENT_TEAM.
#   2. An App Store Connect app record whose bundle ID is com.noslop.app.ios.
#   3. An App Store Connect API key (Users and Access -> Integrations -> App Store Connect API,
#      role "App Manager"). Save the .p8 as ~/.appstoreconnect/private_keys/AuthKey_<KEY_ID>.p8
#      and export ASC_KEY_ID=<KEY_ID> ASC_ISSUER_ID=<issuer uuid>.
#   4. Answer the export-compliance question in App Store Connect once (the app ships
#      ChaCha20-Poly1305 / X25519 for DMs, which is not an exempt use).
#
# Usage:  DEVELOPMENT_TEAM=XXXXXXXXXX ASC_KEY_ID=... ASC_ISSUER_ID=... ./testflight-upload.sh
#         BUILD_NUMBER=42 ./testflight-upload.sh      # override the auto (timestamp) build number
# Note: the build number is written into iosApp/Info.plist (tracked); commit or revert it after an upload.
set -euo pipefail
cd "$(dirname "$0")"

: "${DEVELOPMENT_TEAM:?set DEVELOPMENT_TEAM to your paid team ID}"
: "${ASC_KEY_ID:?set ASC_KEY_ID to the App Store Connect API key ID}"
: "${ASC_ISSUER_ID:?set ASC_ISSUER_ID to the App Store Connect issuer ID}"
KEY_FILE="$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"
[ -f "$KEY_FILE" ] || { echo "API key not found at $KEY_FILE"; exit 1; }

BUILD_NUMBER="${BUILD_NUMBER:-$(date +%Y%m%d%H%M)}"
DD="build/dd"
OUT="build/testflight"
ARCHIVE="$OUT/iosApp.xcarchive"
EXPORT_PLIST="$OUT/ExportOptions.plist"
mkdir -p "$OUT"

# CocoaPods must be integrated into the project (xcodegen regenerates the .xcodeproj without it).
if ! grep -q baseConfigurationReference iosApp.xcodeproj/project.pbxproj; then
  echo "==> Pods not integrated into the Xcode project; running pod install"
  pod install
fi

# Cloud-synced folders add Finder xattrs that make codesign fail on the embedded Tor.framework.
[ -d "$DD/Build" ] && xattr -rc "$DD/Build" 2>/dev/null || true
xattr -rc Pods/Tor 2>/dev/null || true

echo "==> Build number $BUILD_NUMBER (CFBundleVersion must increase on every upload)"
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD_NUMBER" iosApp/Info.plist

echo "==> Archiving (Release)"
rm -rf "$ARCHIVE"
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath "$DD" -archivePath "$ARCHIVE" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$KEY_FILE" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" EAGER_LINKING=NO \
  archive | grep -E 'error:|warning: .*provision|ARCHIVE (SUCCEEDED|FAILED)' || true
[ -d "$ARCHIVE" ] || { echo "Archive failed; re-run without the grep filter to see the full log."; exit 2; }

echo "==> Exporting .ipa for App Store Connect"
sed "s/__TEAM_ID__/$DEVELOPMENT_TEAM/" ExportOptions.plist > "$EXPORT_PLIST"
rm -rf "$OUT/export"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$OUT/export" -exportOptionsPlist "$EXPORT_PLIST" \
  -allowProvisioningUpdates \
  -authenticationKeyPath "$KEY_FILE" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID" \
  | grep -E 'error:|EXPORT (SUCCEEDED|FAILED)' || true
IPA=$(ls "$OUT"/export/*.ipa 2>/dev/null | head -1)
[ -n "$IPA" ] || { echo "Export produced no .ipa"; exit 3; }

echo "==> Validating"
xcrun altool --validate-app -f "$IPA" -t ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
echo "==> Uploading to App Store Connect (TestFlight processing takes 5 to 15 minutes)"
xcrun altool --upload-app -f "$IPA" -t ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
echo "==> Done. Add yourself as an internal tester in App Store Connect -> TestFlight, then install via the TestFlight app."
