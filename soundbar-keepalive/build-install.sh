#!/usr/bin/env bash
# Build/test by default; pass --install to install and start on the single connected TV.
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}
JAR=$SDK/platforms/android-35/android.jar
[ -f "$JAR" ] || { echo "missing $JAR" >&2; exit 1; }
# ANDROID_BUILD_TOOLS accepts a directory or a version installed in either SDK.
BT=${ANDROID_BUILD_TOOLS:-}
if [ -n "$BT" ]; then
    if [ ! -d "$BT" ]; then
        if [ -d "$SDK/build-tools/$BT" ]; then BT=$SDK/build-tools/$BT
        elif [ -d "/opt/android-sdk/build-tools/$BT" ]; then BT=/opt/android-sdk/build-tools/$BT
        else echo "missing build-tools: $BT" >&2; exit 1
        fi
    fi
else
    for root in "$SDK" /opt/android-sdk; do
        [ -d "$root/build-tools" ] || continue
        BT=$(find "$root/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -Vr | { dir=; IFS= read -r dir || :; printf '%s' "$dir"; })
        [ -z "$BT" ] || break
    done
fi
tool() {
    if [ -n "$BT" ] && [ -x "$BT/$1" ]; then printf '%s\n' "$BT/$1"
    else command -v "$1"
    fi
}
for name in aapt2 d8 zipalign apksigner javac java keytool zip; do
    tool "$name" >/dev/null || { echo "missing tool: $name" >&2; exit 1; }
done
ADB=(adb)
if [ -n "${ADB_SERIAL:-}" ]; then ADB+=(-s "$ADB_SERIAL"); fi
PKG=com.nova.soundbarkeepalive
SVC=$PKG/.KeepAliveService
if [ "${1:-}" != "" ] && [ "${1:-}" != --install ]; then echo "Usage: bash build-install.sh [--install]"; exit 1; fi
mkdir -p build/test build/gen build/classes build/dex
javac --release 17 -d build/test src/com/nova/soundbarkeepalive/Signal.java test/com/nova/soundbarkeepalive/SignalTest.java
java -ea -cp build/test com.nova.soundbarkeepalive.SignalTest
"$(tool aapt2)" compile --dir res -o build/res.zip
"$(tool aapt2)" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen \
    --min-sdk-version 26 --target-sdk-version 34 --version-code 2 --version-name 0.2 build/res.zip
sources=()
while IFS= read -r -d '' file; do sources+=("$file"); done < <(find src build/gen -name '*.java' -print0)
javac -Xlint:-options --release 17 -classpath "$JAR" -d build/classes "${sources[@]}"
classes=()
while IFS= read -r -d '' file; do classes+=("$file"); done < <(find build/classes -name '*.class' -print0)
"$(tool d8)" --release --min-api 26 --lib "$JAR" --output build/dex "${classes[@]}"
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$(tool zipalign)" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f debug.keystore ]; then
    keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
        -alias debug -keyalg RSA -keysize 2048 -validity 10000 -dname CN=soundbar-keepalive
fi
"$(tool apksigner)" sign --ks debug.keystore --ks-pass pass:android --out build/keepalive.apk build/aligned.apk
"$(tool apksigner)" verify build/keepalive.apk
printf 'Built and verified: '; du -h build/keepalive.apk
if [ "${1:-}" = --install ]; then
    "${ADB[@]}" push build/keepalive.apk /data/local/tmp/soundbar-keepalive.apk
    RESULT=$("${ADB[@]}" shell pm install -r /data/local/tmp/soundbar-keepalive.apk)
    printf '%s\n' "$RESULT"
    [[ "$RESULT" == *Success* ]] || exit 1
    "${ADB[@]}" shell rm /data/local/tmp/soundbar-keepalive.apk
    "${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
    "${ADB[@]}" shell am start-foreground-service -n "$SVC"
fi
