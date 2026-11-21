#!/usr/bin/env bash
# Build the Anubis APK from source with the plain Android SDK command-line tools (no Gradle).
#
# Requirements: JDK 17+, Android SDK with platforms;android-36 and build-tools;36.0.0, python3.
#
# Environment:
#   ANDROID_SDK      Android SDK root (default: $ANDROID_HOME or $ANDROID_SDK_ROOT)
#   BUILD_TOOLS      build-tools version (default: 36.0.0)
#   PLATFORM         platform (default: android-36)
#   KEYSTORE         path to your signing keystore (optional; without it an unsigned APK is produced)
#   KEY_ALIAS        key alias inside KEYSTORE
#   KS_PASS_FILE     file holding the keystore password (never pass it on the command line)
#
# Output: dist/Anubis-<version>.apk (signed) or dist/Anubis-<version>-unsigned.apk
#
# Note: an APK you sign with your own key cannot install over the official release
# (Android requires the same signing key for updates). Uninstall the official build first.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
SDK=${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}
[ -n "$SDK" ] || { echo "Set ANDROID_SDK (or ANDROID_HOME) to your Android SDK root"; exit 1; }
BT="$SDK/build-tools/${BUILD_TOOLS:-36.0.0}"
AJ="$SDK/platforms/${PLATFORM:-android-36}/android.jar"
[ -f "$AJ" ] || { echo "Missing $AJ"; exit 1; }
export PATH="$BT:$PATH"

PKG=com.anubis.app
LABEL=Anubis
VC=101
VN=5.2
B="$ROOT/build"
rm -rf -- "$B"
mkdir -p "$B/assets" "$B/classes" "$B/gen" "$B/dex" "$ROOT/dist"

# Web app (the whole UI) is bundled as an asset and loaded by the WebView.
cp web/index.html web/UncialAntiqua.ttf "$B/assets/"

python3 - "$PKG" "$LABEL" "$B/AndroidManifest.xml" <<'PY'
from pathlib import Path
import sys
pkg,label,out=sys.argv[1:]
s=Path('android/AndroidManifest.tmpl.xml').read_text()
s=s.replace('@@PKG@@',pkg).replace('@@LABEL@@',label).replace('@@BACKUP@@','android:allowBackup="false"').replace('@@PERMS@@','')
assert '@@' not in s
Path(out).write_text(s)
PY

aapt2 compile --dir android/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$AJ" --manifest "$B/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 36 --version-code "$VC" --version-name "$VN" \
  -A "$B/assets" --java "$B/gen" "$B/res.zip"

mapfile -t SOURCES < <(find android/src android/variants/live "$B/gen" -name '*.java')
javac -nowarn -source 1.8 -target 1.8 -bootclasspath "$AJ" -d "$B/classes" "${SOURCES[@]}"
mapfile -t CLASSES < <(find "$B/classes" -name '*.class')
d8 --release --min-api 26 --lib "$AJ" --output "$B/dex" "${CLASSES[@]}"

python3 - "$B" <<'PY'
from pathlib import Path
import sys,zipfile
b=Path(sys.argv[1])
with zipfile.ZipFile(b/'base.apk') as src,zipfile.ZipFile(b/'unsigned.apk','w',zipfile.ZIP_DEFLATED) as dst:
    for i in src.infolist():
        dst.writestr(i,src.read(i.filename),compress_type=zipfile.ZIP_STORED if i.filename=='resources.arsc' else i.compress_type)
    dst.write(b/'dex/classes.dex','classes.dex')
PY
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

if [ -n "${KEYSTORE:-}" ]; then
  : "${KEY_ALIAS:?Set KEY_ALIAS}"; : "${KS_PASS_FILE:?Set KS_PASS_FILE}"
  OUT="dist/Anubis-$VN.apk"
  apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --ks-pass "file:$KS_PASS_FILE" \
    --min-sdk-version 26 --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false --out "$OUT" "$B/aligned.apk"
  apksigner verify --print-certs "$OUT"
else
  OUT="dist/Anubis-$VN-unsigned.apk"
  cp "$B/aligned.apk" "$OUT"
  echo "No KEYSTORE set: produced an unsigned APK. Sign it before installing."
fi
echo "Built $OUT"
