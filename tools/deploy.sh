#!/usr/bin/env bash
# Build, install and configure the camera wall on a Google TV device over ADB.
#
#   ./tools/deploy.sh                          # discover the device via mDNS
#   ./tools/deploy.sh 192.168.88.172:46571     # explicit host:port
#   ./tools/deploy.sh <dev> --autostart        # ...and start the wall on boot
#   ./tools/deploy.sh <dev> --restore          # undo autostart
#
# Wireless debugging uses a random port that changes on reboot, so with no argument
# we ask mDNS where adb is listening rather than assuming 5555.
set -euo pipefail

PKG=net.khaledez.camwall
GTV_LAUNCHER=com.google.android.apps.tv.launcherx
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb

# AGP is validated on JDK 17/21. If the default java is newer, look for a supported one
# rather than failing deep inside Gradle with an unhelpful class-file-version error.
pick_jdk() {
    [ -n "${JAVA_HOME:-}" ] && return 0
    local major
    major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    case "$major" in
        17|21) return 0 ;;
    esac
    for c in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-17-openjdk \
             /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-amd64; do
        if [ -x "$c/bin/javac" ]; then export JAVA_HOME="$c"; echo ">> using JDK at $c"; return 0; fi
    done
    echo "!! no JDK 17/21 found and default java is ${major:-unknown}; set JAVA_HOME" >&2
}

discover() {
    avahi-browse -rtp _adb-tls-connect._tcp 2>/dev/null \
        | awk -F';' '/^=/ {print $8":"$9; exit}'
}

DEV="${1:-}"
MODE="${2:-}"
if [ -z "$DEV" ] || [[ "$DEV" == --* ]]; then
    MODE="${DEV:-}"
    DEV="$(discover)"
    [ -n "$DEV" ] || { echo "!! no _adb-tls-connect._tcp on the network."; \
                       echo "   Enable Developer options -> Wireless debugging on the device."; exit 1; }
    echo ">> discovered $DEV"
fi
[[ "$DEV" == *:* ]] || DEV="$DEV:5555"

"$ADB" connect "$DEV" >/dev/null
"$ADB" -s "$DEV" wait-for-device

if [ "$MODE" = "--restore" ]; then
    echo ">> revoking boot autostart"
    "$ADB" -s "$DEV" shell appops set --user 0 "$PKG" SYSTEM_ALERT_WINDOW default || true
    "$ADB" -s "$DEV" shell appops write-settings || true
    # Only relevant if a previous attempt disabled the launcher; harmless otherwise.
    "$ADB" -s "$DEV" shell pm enable "$GTV_LAUNCHER" || true
    "$ADB" -s "$DEV" shell cmd package set-home-activity "$GTV_LAUNCHER/.home.HomeActivity" || true
    echo ">> done"
    exit 0
fi

echo ">> device: $("$ADB" -s "$DEV" shell getprop ro.product.device | tr -d '\r')" \
     "sdk=$("$ADB" -s "$DEV" shell getprop ro.build.version.sdk | tr -d '\r')"

pick_jdk
echo ">> building"
"$ROOT/gradlew" -p "$ROOT" assembleRelease -q

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
echo ">> installing"
"$ADB" -s "$DEV" install -r "$APK"

CFG="$ROOT/cameras.json"
if [ -f "$CFG" ]; then
    echo ">> pushing cameras.json"
    "$ADB" -s "$DEV" shell mkdir -p "/sdcard/Android/data/$PKG/files"
    "$ADB" -s "$DEV" push "$CFG" "/sdcard/Android/data/$PKG/files/cameras.json"
else
    echo "!! no cameras.json at repo root — falling back to built-in placeholder URLs"
fi

if [ "$MODE" = "--autostart" ]; then
    # BootReceiver's startActivity() is dropped by Android 10+ background-activity-start
    # blocking unless the app is exempt. SYSTEM_ALERT_WINDOW is the exemption; the app
    # never draws an overlay.
    echo ">> granting the background-activity-start exemption"
    "$ADB" -s "$DEV" shell appops set --user 0 "$PKG" SYSTEM_ALERT_WINDOW allow
    # appops writes are debounced; without this an immediate reboot loses the grant and
    # the boot start is blocked with no obvious reason why.
    "$ADB" -s "$DEV" shell appops write-settings
    echo -n "   appop now: "
    "$ADB" -s "$DEV" shell appops get --user 0 "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r'
    echo "   undo with: $0 $DEV --restore"
fi

echo ">> launching"
"$ADB" -s "$DEV" shell am force-stop "$PKG"
"$ADB" -s "$DEV" shell am start -n "$PKG/.MainActivity"
cat <<EOM
>> verify (screencap shows overlay planes as BLACK — use this instead):
   $ADB -s $DEV shell dumpsys SurfaceFlinger | sed -n '/HWC layers/,/^\$/p'
EOM
