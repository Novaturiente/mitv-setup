#!/usr/bin/env bash
# Restore the known TV profile from the two APKs shipped in this repository.
set -euo pipefail
cd "$(dirname "$0")"
ROOT=$PWD
# shellcheck source=tools/adb.sh
source tools/adb.sh
[ "$#" = 0 ] || fail 'Usage: ADB_SERIAL=<device> bash setup.sh'
select_tv
check_profile

# All safety gates precede changes to the TV.
WIFI=$(tv dumpsys wifi)
if [[ "$WIFI" == *cname=com.google.android.tungsten.setupwraith* ]]; then
    fail 'A saved Wi-Fi network belongs to setupwraith. Forget and rejoin it in TV Settings, reconnect ADB, then rerun. Disabling setupwraith now would delete that network.'
fi
for apk in apks/launcher.apk apks/soundbar-keepalive.apk; do
    [ -f "$apk" ] || fail "Missing $apk. Download the complete repository."
done
if command -v sha256sum >/dev/null; then
    (cd apks && sha256sum -c SHA256SUMS) || fail 'APK checksum mismatch.'
elif command -v shasum >/dev/null; then
    (cd apks && shasum -a 256 -c SHA256SUMS) || fail 'APK checksum mismatch.'
else
    fail 'sha256sum or shasum is required.'
fi

# Validate the package list before any state change. Core packages are never candidates.
DEBLOAT=()
while IFS= read -r pkg; do
    [[ "$pkg" = \#* || -z "$pkg" ]] && continue
    case "$pkg" in
        com.miui.tv.analytics|com.xiaomi.statistic|com.google.android.feedback|com.android.tv.feedbackconsent|com.android.federatedcompute.services|com.android.ondevicepersonalization.services|com.android.adservices.api|com.mitv.tvhome.atv|com.mitv.tvhome.michannel|com.mitv.tvhome.mitvplus|com.mitv.tvhome.oemtab|com.xm.webcontent|android.autoinstalls.config.xioami.dangal|com.xiaomi.mimusic2|com.mitv.gallery|com.xiaomi.mitv.tvmanager|com.google.android.katniss|com.android.printspooler|com.android.vending|com.google.android.youtube.tv|com.google.android.youtube.tvmusic|com.google.android.play.games) ;;
        *) fail "Unapproved package in disable-list.txt: $pkg";;
    esac
    DEBLOAT+=("$pkg")
done < disable-list.txt

umask 077
BACKUP=$ROOT/backups/$(TZ=Asia/Kolkata date +%Y%m%d-%H%M%S)-$$
mkdir -p "$BACKUP"
tv getprop > "$BACKUP/getprop.txt"
tv getprop ro.serialno > "$BACKUP/device-serial.txt"
tv pm list packages -f -u > "$BACKUP/packages-all.txt"
tv pm list packages -d > "$BACKUP/packages-disabled.txt"
INSTALLED=$(tv pm list packages --user 0)
printf '%s\n' "$INSTALLED" > "$BACKUP/packages-installed.txt"
for ns in system secure global; do tv settings list "$ns" > "$BACKUP/settings-$ns.txt"; done
tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME > "$BACKUP/home-activities.txt"
tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
    awk '/^[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+$/ {print; exit}' > "$BACKUP/home.txt"
[ -s "$BACKUP/home.txt" ] || fail 'Cannot identify existing Home; nothing changed.'
: > "$BACKUP/package-states.tsv"
for pkg in "${DEBLOAT[@]}" com.google.android.apps.tv.dreamx com.google.android.tungsten.setupwraith com.google.android.apps.tv.launcherx com.nova.tvhome com.nova.soundbarkeepalive; do
    if has_package "$pkg"; then
        state=$(package_state "$pkg") || fail "Cannot snapshot installed package $pkg; nothing changed."
        printf '%s\t%s\n' "$pkg" "$state" >> "$BACKUP/package-states.tsv"
    fi
done
: > "$BACKUP/settings.tsv"
for key in enabled_accessibility_services accessibility_enabled screensaver_components screensaver_default_component; do
    printf 'secure\t%s\t%s\n' "$key" "$(tv settings get secure "$key")" >> "$BACKUP/settings.tsv"
done
LAUNCHER_DUMP=$(tv dumpsys package com.nova.tvhome) || fail 'Cannot snapshot launcher permissions; nothing changed.'
if [[ "$LAUNCHER_DUMP" == *'android.permission.ACCESS_FINE_LOCATION: granted=true'* ]]; then
    touch "$BACKUP/location-was-granted"
fi
KEEPALIVE_DUMP=$(tv dumpsys package com.nova.soundbarkeepalive) || fail 'Cannot snapshot soundbar permissions; nothing changed.'
if [[ "$KEEPALIVE_DUMP" == *'android.permission.POST_NOTIFICATIONS: granted=true'* ]]; then
    touch "$BACKUP/notifications-were-granted"
fi
SERVICE_DUMP=$(tv dumpsys activity services com.nova.soundbarkeepalive) || fail 'Cannot snapshot service state; nothing changed.'
if [[ "$SERVICE_DUMP" == *isForeground=true* ]]; then
    touch "$BACKUP/keepalive-was-running"
fi
# A per-run launcher for the shared restore implementation; no device data is interpolated into code.
# shellcheck disable=SC2016 # Expand these variables when restore runs, not when it is generated.
printf '#!/usr/bin/env bash\nset -euo pipefail\nHERE=$(cd "$(dirname "$0")" && pwd)\nexec bash "$HERE/../../restore.sh" "$HERE"\n' > "$BACKUP/restore.sh"
printf 'Backup saved: %s\n' "$BACKUP"

install_apk() {
    local apk=$1 tmp=$2 output
    "${ADB[@]}" push "$apk" "$tmp"
    output=$(tv pm install -r "$tmp") || fail "Install failed: $output"
    printf '%s\n' "$output"
    [[ "$output" == *Success* ]] || fail "Install failed: $output"
    tv rm "$tmp"
}
install_apk apks/launcher.apk /data/local/tmp/mitv-launcher.apk
install_apk apks/soundbar-keepalive.apk /data/local/tmp/mitv-keepalive.apk
set_package_state com.nova.tvhome 1 || fail 'Cannot enable new Home.'
set_package_state com.nova.soundbarkeepalive 1 || fail 'Cannot enable soundbar app.'
tv pm grant com.nova.tvhome android.permission.ACCESS_FINE_LOCATION
tv pm grant com.nova.soundbarkeepalive android.permission.POST_NOTIFICATIONS

# Never leave a failed launcher takeover with stock Home disabled.
TAKEOVER_STARTED=0
recover_home() {
    local status=$?
    trap - EXIT
    if [ "$status" -ne 0 ] && [ "$TAKEOVER_STARTED" = 1 ]; then
        printf 'Home takeover failed; restoring stock launcher.\n' >&2
        tv pm enable --user 0 com.google.android.apps.tv.launcherx || true
        tv pm enable --user 0 com.google.android.tungsten.setupwraith || true
        tv cmd package set-home-activity com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.VanillaModeHomeActivity || true
        tv input keyevent KEYCODE_HOME || true
    fi
    exit "$status"
}
trap recover_home EXIT
TAKEOVER_STARTED=1
set_package_state com.google.android.tungsten.setupwraith 3 || fail 'Cannot disable setupwraith safely.'
set_package_state com.google.android.apps.tv.launcherx 3 || fail 'Cannot disable stock Home.'
tv cmd package set-home-activity com.nova.tvhome/.MainActivity
tv input keyevent KEYCODE_HOME
sleep 3
FOCUS=$(tv dumpsys activity activities)
[[ "$FOCUS" =~ mFocusedApp=[^$'\n']*com.nova.tvhome/ ]] || fail 'The custom launcher did not become the focused Home.'
TAKEOVER_STARTED=0

CUR=$(tv settings get secure enabled_accessibility_services)
# Accept both full and short Android component notation without duplicating the service.
case ":$CUR:" in
    *:com.nova.tvhome/com.nova.tvhome.KeyService:*|*:com.nova.tvhome/.KeyService:*) ;;
    :null:|::) CUR=com.nova.tvhome/com.nova.tvhome.KeyService;;
    *) CUR=$CUR:com.nova.tvhome/com.nova.tvhome.KeyService;;
esac
tv settings put secure enabled_accessibility_services "$CUR"
tv settings put secure accessibility_enabled 1
printf 'PatchWall opens Live TV; held-volume ramping may no longer work (accepted profile trade-off).\n'

WARNINGS=0
for pkg in "${DEBLOAT[@]}"; do
    if ! has_package "$pkg"; then
        printf 'Skipped missing/hidden user-0 package: %s\n' "$pkg"
    elif ! set_package_state "$pkg" 3; then
        printf 'WARNING: could not disable %s; left as firmware permits.\n' "$pkg" >&2
        WARNINGS=$((WARNINGS + 1))
    fi
done
if has_package com.neilturner.aerialviews; then
    tv settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
    tv settings put secure screensaver_default_component com.neilturner.aerialviews/.ui.screensaver.DreamActivity
    if has_package com.google.android.apps.tv.dreamx && ! set_package_state com.google.android.apps.tv.dreamx 3; then
        WARNINGS=$((WARNINGS + 1))
    fi
else
    printf 'Aerial Views missing: existing screensaver unchanged. Install it using the README link and rerun.\n'
fi
for pkg in org.smarttube.stable com.netflix.ninja com.amazon.amazonvideo.livingroom in.startv.hotstar org.jellyfin.androidtv com.aurora.store; do
    has_package "$pkg" || printf 'Missing app: %s (official installation links in README).\n' "$pkg"
done
for pkg in com.xiaomi.mitv.updateservice com.xiaomi.mitv.mediaexplorer; do
    has_package "$pkg" || printf 'WARNING: %s is missing/hidden; setup cannot restore a firmware-hidden package.\n' "$pkg"
done

tv am start-foreground-service -n com.nova.soundbarkeepalive/.KeepAliveService
sleep 3
SERVICE=$(tv dumpsys activity services com.nova.soundbarkeepalive)
[[ "$SERVICE" == *isForeground=true* ]] || fail 'Soundbar foreground service did not start.'
ACCESS=$(tv dumpsys accessibility)
[[ "$ACCESS" == *'Service[label=Home remote keys'* ]] || fail 'Remote accessibility service is not bound; check TV Accessibility settings.'
tv pm list packages -d > "$BACKUP/packages-disabled-after.txt"
printf 'Verified Home, bound remote service, and soundbar foreground service. Debloat warnings: %s\n' "$WARNINGS"
printf 'Restore this run: bash restore.sh %q\n' "$BACKUP"
printf 'No reboot performed. Soundbar autostart is included; keep-alive effectiveness still depends on the JBL detecting its signal.\n'
printf 'When finished, turn off network/USB/Wireless debugging and revoke authorisations on the TV.\n'
[ "$WARNINGS" -eq 0 ] || exit 2
