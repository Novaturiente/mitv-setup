#!/usr/bin/env bash
# Restore only states changed by a setup run; APK versions and app data are not rolled back.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=tools/adb.sh
source "$ROOT/tools/adb.sh"
[ "$#" = 1 ] || fail 'Usage: bash restore.sh backups/<run-directory>'
BACKUP=$(cd "$1" && pwd)
for file in device-serial.txt package-states.tsv settings.tsv home.txt packages-installed.txt; do
    [ -f "$BACKUP/$file" ] || fail "Missing backup file: $file"
done
select_tv
check_profile
[ "$(tv getprop ro.serialno)" = "$(< "$BACKUP/device-serial.txt")" ] || fail 'This backup belongs to a different TV.'
INSTALLED=$(tv pm list packages --user 0)
FAILED=0
# Restore the two stock HOME handlers before switching away from the custom launcher.
for home_pkg in com.google.android.apps.tv.launcherx com.google.android.tungsten.setupwraith; do
    while IFS=$'\t' read -r pkg state; do
        [ "$pkg" = "$home_pkg" ] || continue
        set_package_state "$pkg" "$state" || FAILED=1
    done < "$BACKUP/package-states.tsv"
done
while IFS=$'\t' read -r ns key value; do
    if [ "$value" = null ]; then tv settings delete "$ns" "$key";
    else tv settings put "$ns" "$key" "$value"; fi
done < "$BACKUP/settings.tsv"
HOME_COMPONENT=$(< "$BACKUP/home.txt")
tv cmd package set-home-activity "$HOME_COMPONENT"
tv input keyevent KEYCODE_HOME
sleep 2
FOCUS=$(tv dumpsys activity activities)
# Fail before disabling/uninstalling the replacement launcher if the requested Home failed.
HOME_PKG=${HOME_COMPONENT%%/*}
FOCUSED=$(awk '/mFocusedApp=/{print}' <<< "$FOCUS")
[[ "$FOCUSED" == *"$HOME_PKG/"* ]] || fail 'Original Home did not open; custom apps left installed. Inspect TV before continuing.'
while IFS=$'\t' read -r pkg state; do
    # Older backups may contain the retired helper; never restore its state.
    case "$pkg" in com.google.android.apps.tv.launcherx|com.google.android.tungsten.setupwraith|com.nova.soundbarkeepalive) continue;; esac
    set_package_state "$pkg" "$state" || FAILED=1
done < "$BACKUP/package-states.tsv"
if ! grep -Fqx package:com.nova.tvhome "$BACKUP/packages-installed.txt"; then
    tv pm uninstall com.nova.tvhome
fi
if grep -Fqx package:com.nova.tvhome "$BACKUP/packages-installed.txt" && [ ! -f "$BACKUP/location-was-granted" ]; then
    tv pm revoke com.nova.tvhome android.permission.ACCESS_FINE_LOCATION || FAILED=1
fi
[ "$FAILED" = 0 ] || fail 'Some package states or permissions could not be restored; see messages above.'
printf 'Original package states, relevant settings and Home restored. APK versions and app data were not rolled back.\n'
