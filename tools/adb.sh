#!/usr/bin/env bash
# Shared ADB device selection and read/modify helpers; sourced by setup and restore.
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
select_tv() {
    command -v adb >/dev/null || fail 'adb is required on PATH.'
    ADB=(adb)
    if [ -n "${ADB_SERIAL:-}" ]; then
        SERIAL=$ADB_SERIAL
    else
        SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
        [ -n "$SERIAL" ] && [[ "$SERIAL" != *$'\n'* ]] || fail 'Connect exactly one authorised TV, or set ADB_SERIAL.'
    fi
    ADB+=(-s "$SERIAL")
    [ "$("${ADB[@]}" get-state 2>/dev/null)" = device ] || fail 'Selected ADB device is not authorised/online.'
}
tv() { "${ADB[@]}" shell "$@" </dev/null | tr -d '\r'; }
check_profile() {
    [ "$(tv getprop ro.product.model)" = MiTV-AXFR2 ] &&
    [ "$(tv getprop ro.product.device)" = watchmen ] &&
    [ "$(tv getprop ro.build.version.sdk)" = 34 ] &&
    [ "$(tv getprop ro.build.version.incremental)" = V816.0.26.5.26.UFZAATV ] ||
        fail 'Unsupported TV/firmware. This profile is only for MiTV-AXFR2 watchmen Android 14 V816.0.26.5.26.UFZAATV.'
}
# Match the complete entry, not a package-name prefix.
has_package() { grep -Fqx "package:$1" <<< "$INSTALLED"; }
# Parse the user-0 line; application defaults alone are not the current user state.
package_state() {
    local line dump
    dump=$(tv dumpsys package "$1") || return 1
    line=$(grep 'User 0:' <<< "$dump") || return 1
    [[ "$line" =~ installed=true ]] && [[ "$line" =~ hidden=false ]] || return 1
    [[ "$line" =~ enabled=([0-4])([[:space:]]|$) ]] || return 1
    printf '%s\n' "${BASH_REMATCH[1]}"
}
set_package_state() {
    local pkg=$1 state=$2 command output
    case "$state" in
        0) command=default-state;; 1) command=enable;; 2) command=disable;;
        3) command=disable-user;; 4) command=disable-until-used;;
        *) fail "Invalid package state: $state";;
    esac
    if ! output=$(tv pm "$command" --user 0 "$pkg" 2>&1); then
        printf '%s\n' "$output" >&2
        return 1
    fi
    [ "$(package_state "$pkg")" = "$state" ] || {
        printf 'Package state was refused or did not stick: %s\n%s\n' "$pkg" "$output" >&2
        return 1
    }
}
