"""Run setup/restore against a strict, stateful fake ADB; never contact a TV."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
STOCK = "com.google.android.apps.tv.launcherx"
WRAITH = "com.google.android.tungsten.setupwraith"
HOME = "com.nova.tvhome"
KEEPALIVE = "com.nova.soundbarkeepalive"
DREAM = "com.google.android.apps.tv.dreamx"
AERIAL = "com.neilturner.aerialviews"
REMOTE = HOME + "/com.nova.tvhome.KeyService"
EXISTING = "org.example.reader/.ReaderService"
STOCK_COMPONENT = STOCK + "/" + STOCK + ".home.VanillaModeHomeActivity"

FAKE_ADB = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

path = Path(os.environ["MOCK_STATE"])
s = json.loads(path.read_text())
a = sys.argv[1:]
with open(os.environ["MOCK_LOG"], "a") as log:
    log.write(json.dumps(a) + "\n")
if a[:1] == ["-s"]:
    assert a[1] == "fake-tv", a
    a = a[2:]

def save():
    path.write_text(json.dumps(s))

def emit(value=""):
    print(value)
    sys.exit(0)

if a == ["devices"]:
    emit("List of devices attached\nfake-tv\tdevice")
if a == ["get-state"]:
    emit("device")
if a[:1] == ["push"]:
    assert Path(a[1]).is_file(), a
    emit("1 file pushed")
assert a[:1] == ["shell"], a
a = a[1:]
pkgs = s["packages"]
if a[:1] == ["getprop"]:
    if len(a) == 1:
        emit("\n".join("[" + k + "]: [" + v + "]" for k, v in s["props"].items()))
    emit(s["props"].get(a[1], ""))
if a == ["dumpsys", "wifi"]:
    emit(s.get("wifi", ""))
if a[:3] == ["pm", "list", "packages"]:
    result = []
    for name, p in sorted(pkgs.items()):
        if not p["installed"] and "-u" not in a:
            continue
        if p.get("hidden", False):
            continue
        if "-d" in a and p["enabled"] not in (2, 3, 4):
            continue
        result.append("package:" + ("/system/app/" + name + ".apk=" if "-f" in a else "") + name)
    emit("\n".join(result))
if a[:2] == ["dumpsys", "package"]:
    if a[2] == s.get("fail_first_package_dump"):
        calls = [json.loads(line) for line in Path(os.environ["MOCK_LOG"]).read_text().splitlines()]
        if sum(call[-3:] == a for call in calls) == 1:
            print("Transient package dump failure", file=sys.stderr)
            sys.exit(1)
    p = pkgs.get(a[2])
    if p is None:
        emit("Unable to find package: " + a[2])
    lines = ["User 0: installed=" + str(p["installed"]).lower() +
             " hidden=" + str(p.get("hidden", False)).lower() + " enabled=" + str(p["enabled"])]
    # A different user's state must not override the user-0 record.
    lines.insert(0, "User 10: installed=true hidden=false enabled=2")
    lines += [permission + ": granted=true" for permission in p.get("grants", [])]
    if s.get("large_dumps") and a[2] in ("com.nova.tvhome", "com.nova.soundbarkeepalive"):
        lines += ["x" * 1024] * 256
    emit("\n".join(lines))
if a[:2] == ["pm", "install"]:
    if s.get("install_failure"):
        emit("Failure [INSTALL_FAILED_INVALID_APK]")
    name = "com.nova.tvhome" if "launcher" in a[-1] else "com.nova.soundbarkeepalive"
    pkgs.setdefault(name, {"installed": True, "enabled": 0})["installed"] = True
    save()
    emit("Success")
commands = {"default-state": 0, "enable": 1, "disable": 2, "disable-user": 3, "disable-until-used": 4}
if len(a) > 1 and a[0] == "pm" and a[1] in commands:
    assert a[2:4] == ["--user", "0"], a
    assert a[-1] in pkgs and pkgs[a[-1]]["installed"], a
    pkgs[a[-1]]["enabled"] = commands[a[1]]
    save()
    emit("Package " + a[-1] + " new state: " + a[1])
if a[:2] in (["pm", "grant"], ["pm", "revoke"]):
    grants = pkgs[a[2]].setdefault("grants", [])
    if a[1] == "grant" and a[3] not in grants:
        grants.append(a[3])
    if a[1] == "revoke" and a[3] in grants:
        grants.remove(a[3])
    save()
    emit()
if a[:2] == ["pm", "uninstall"]:
    pkgs[a[-1]]["installed"] = False
    save()
    emit("Success")
if a[:1] == ["settings"]:
    command, namespace = a[1:3]
    settings = s["settings"].setdefault(namespace, {})
    if command == "list":
        emit("\n".join(k + "=" + v for k, v in settings.items()))
    key = a[3]
    if command == "get":
        emit(settings.get(key, "null"))
    if command == "put":
        settings[key] = a[4]
    elif command == "delete":
        settings.pop(key, None)
    else:
        raise AssertionError(a)
    save()
    emit()
if a[:3] == ["cmd", "package", "query-activities"]:
    emit(s["home"])
if a[:3] == ["cmd", "package", "resolve-activity"]:
    emit(s["home"])
if a[:3] == ["cmd", "package", "set-home-activity"]:
    s["home"] = a[3]
    save()
    emit("Success")
if a == ["input", "keyevent", "KEYCODE_HOME"]:
    s["focus"] = "com.example.failed/.Activity" if s.get("focus_failure") else s["home"]
    save()
    emit()
if a == ["dumpsys", "activity", "activities"]:
    lines = ["mFocusedApp=ActivityRecord{abc u0 " + s["focus"] + " t1}"]
    lines += ["Hist #0: ActivityRecord{old u0 " + component + " t0}" for component in s.get("history", [])]
    emit("\n".join(lines))
if a[:2] in (["am", "start-foreground-service"], ["am", "stopservice"]):
    s["running"] = a[1] == "start-foreground-service"
    save()
    emit("Starting service" if s["running"] else "Stopping service")
if a[:3] == ["dumpsys", "activity", "services"]:
    dump = "isForeground=true" if s.get("running") else "No services"
    if s.get("large_dumps"):
        dump += "\n" + ("x" * 1024 + "\n") * 256
    emit(dump)
if a == ["dumpsys", "accessibility"]:
    components = s["settings"]["secure"].get("enabled_accessibility_services", "").split(":")
    bound = any(c in components for c in ("com.nova.tvhome/.KeyService", "com.nova.tvhome/com.nova.tvhome.KeyService"))
    emit("Service[label=Home remote keys]" if bound and s["settings"]["secure"].get("accessibility_enabled") == "1" else "No bound services")
if a[:1] == ["rm"]:
    emit()
if a[:2] == ["dumpsys", "hdmi_control"]:
    emit("mSystemAudioActivated=true")
raise AssertionError("Unsupported fake ADB command: " + repr(a))
'''


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="tv-setup-test-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        (self.repo / "tools").mkdir()
        for name in ("setup.sh", "restore.sh", "tools/adb.sh", "disable-list.txt"):
            shutil.copyfile(ROOT / name, self.repo / name)
        apks = self.repo / "apks"
        apks.mkdir()
        checksums = []
        for name in ("launcher.apk", "soundbar-keepalive.apk"):
            data = ("test fixture " + name).encode()
            (apks / name).write_bytes(data)
            checksums.append(hashlib.sha256(data).hexdigest() + "  " + name)
        (apks / "SHA256SUMS").write_text("\n".join(checksums) + "\n")
        bin_dir = self.repo / "bin"
        bin_dir.mkdir()
        adb = bin_dir / "adb"
        adb.write_text(FAKE_ADB)
        adb.chmod(0o755)
        sleep = bin_dir / "sleep"
        sleep.write_text("#!/bin/sh\nexit 0\n")
        sleep.chmod(0o755)
        self.state_path = self.repo / "state.json"
        self.log_path = self.repo / "commands.jsonl"
        packages = {name: {"installed": True, "enabled": 0} for name in
                    (STOCK, WRAITH, DREAM, "com.miui.tv.analytics", "com.xiaomi.mitv.mediaexplorer")}
        self.initial = {
            "props": {"ro.product.model": "MiTV-AXFR2", "ro.product.device": "watchmen",
                      "ro.build.version.sdk": "34", "ro.build.version.incremental": "V816.0.26.5.26.UFZAATV",
                      "ro.serialno": "fake-hardware-serial"},
            "packages": packages,
            "settings": {"system": {}, "global": {}, "secure": {
                "enabled_accessibility_services": EXISTING, "accessibility_enabled": "1",
                "screensaver_components": "original/.Dream"}},
            "home": STOCK_COMPONENT, "focus": STOCK_COMPONENT, "running": False,
        }
        self.save(self.initial)
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ["PATH"],
                        MOCK_STATE=str(self.state_path), MOCK_LOG=str(self.log_path))
        self.env.pop("ADB_SERIAL", None)

    def save(self, state):
        self.state_path.write_text(json.dumps(state))

    def state(self):
        return json.loads(self.state_path.read_text())

    def commands(self):
        return [json.loads(line) for line in self.log_path.read_text().splitlines()]

    def shell_commands(self):
        return [a[3:] for a in self.commands() if a[:3] == ["-s", "fake-tv", "shell"]]

    def run_script(self, script="setup.sh", *args, success=True):
        result = subprocess.run(["bash", script, *map(str, args)], cwd=self.repo, env=self.env,
                                text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
        if success:
            self.assertEqual(result.returncode, 0, result.stdout)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout

    def assert_no_mutation(self):
        self.assertEqual(self.state(), self.initial)
        for command in self.shell_commands():
            self.assertIn(command[0], ("getprop", "dumpsys"), command)
        self.assertFalse((self.repo / "backups").exists())

    def test_wrong_profile_rejected_without_mutation(self):
        self.initial["props"]["ro.product.model"] = "OtherTV"
        self.save(self.initial)
        self.assertIn("Unsupported TV/firmware", self.run_script(success=False))
        self.assert_no_mutation()

    def test_setupwraith_wifi_owner_rejected_without_mutation(self):
        self.initial["wifi"] = "Network creator cname=com.google.android.tungsten.setupwraith"
        self.save(self.initial)
        self.assertIn("A saved Wi-Fi network belongs to setupwraith", self.run_script(success=False))
        self.assert_no_mutation()

    def test_install_failure_with_zero_exit_does_not_disable_home(self):
        self.initial["install_failure"] = True
        self.save(self.initial)
        self.assertIn("Install failed: Failure [INSTALL_FAILED_INVALID_APK]", self.run_script(success=False))
        state = self.state()
        self.assertEqual(state["home"], STOCK_COMPONENT)
        for package in (STOCK, WRAITH):
            self.assertEqual(state["packages"][package]["enabled"], 0)
        self.assertFalse(any(c[:2] == ["pm", "disable-user"] for c in self.shell_commands()))

    def test_missing_aerial_preserves_screensaver_and_dream(self):
        self.assertIn("Aerial Views missing", self.run_script())
        state = self.state()
        self.assertEqual(state["packages"][DREAM]["enabled"], 0)
        for key in ("screensaver_components", "screensaver_default_component"):
            self.assertEqual(state["settings"]["secure"].get(key), self.initial["settings"]["secure"].get(key))
        self.assertFalse(any(c[:3] == ["settings", "put", "secure"] and c[3].startswith("screensaver_")
                             for c in self.shell_commands()))

    def test_success_sets_home_and_services_preserving_existing_remote(self):
        output = self.run_script()
        state = self.state()
        self.assertEqual(state["home"], HOME + "/.MainActivity")
        self.assertEqual(state["focus"], HOME + "/.MainActivity")
        self.assertTrue(state["running"])
        self.assertEqual(state["settings"]["secure"]["enabled_accessibility_services"], EXISTING + ":" + REMOTE)
        self.assertEqual(state["settings"]["secure"]["accessibility_enabled"], "1")
        for package in (HOME, KEEPALIVE):
            self.assertEqual(state["packages"][package]["enabled"], 1)
        for package in (STOCK, WRAITH, "com.miui.tv.analytics"):
            self.assertEqual(state["packages"][package]["enabled"], 3)
        self.assertIn("Skipped missing/hidden user-0 package: com.xiaomi.statistic", output)
        self.assertIn("Verified Home, bound remote service, and soundbar foreground service", output)

    def test_second_setup_does_not_duplicate_remote_service(self):
        self.run_script()
        self.run_script()
        self.assertEqual(self.state()["settings"]["secure"]["enabled_accessibility_services"].split(":"),
                         [EXISTING, REMOTE])

    def test_short_remote_component_is_not_duplicated(self):
        self.initial["settings"]["secure"]["enabled_accessibility_services"] = EXISTING + ":" + HOME + "/.KeyService"
        self.save(self.initial)
        self.run_script()
        self.assertEqual(self.state()["settings"]["secure"]["enabled_accessibility_services"],
                         self.initial["settings"]["secure"]["enabled_accessibility_services"])

    def test_focus_failure_reenables_stock_home(self):
        self.initial["focus_failure"] = True
        self.save(self.initial)
        self.assertIn("The custom launcher did not become the focused Home", self.run_script(success=False))
        state = self.state()
        self.assertEqual(state["home"], STOCK_COMPONENT)
        for package in (STOCK, WRAITH):
            self.assertEqual(state["packages"][package]["enabled"], 1)
        self.assertEqual(state["settings"], self.initial["settings"])

    def test_core_package_injection_rejected_before_changes(self):
        with (self.repo / "disable-list.txt").open("a") as target:
            target.write("\ncom.android.systemui\n")
        self.assertIn("Unapproved package in disable-list.txt: com.android.systemui", self.run_script(success=False))
        self.assert_no_mutation()

    def test_restore_rejects_home_only_in_history_before_custom_app_removal(self):
        self.run_script()
        backup, = (self.repo / "backups").iterdir()
        state = self.state()
        state["focus_failure"] = True
        state["history"] = [STOCK_COMPONENT]
        self.save(state)
        before = len(self.shell_commands())
        output = self.run_script("restore.sh", backup, success=False)
        self.assertIn("Original Home did not open; custom apps left installed", output)
        state = self.state()
        self.assertEqual(state["focus"], "com.example.failed/.Activity")
        for package in (HOME, KEEPALIVE):
            self.assertTrue(state["packages"][package]["installed"])
            self.assertEqual(state["packages"][package]["enabled"], 1)
        restore_commands = self.shell_commands()[before:]
        self.assertFalse(any(c[:2] == ["pm", "uninstall"] for c in restore_commands))
        self.assertFalse(any(c[:1] == ["pm"] and c[-1] in (HOME, KEEPALIVE)
                             for c in restore_commands))

    def test_initial_installed_package_snapshot_failure_prevents_mutation(self):
        package = "com.miui.tv.analytics"
        self.initial["fail_first_package_dump"] = package
        self.save(self.initial)
        output = self.run_script(success=False)
        self.assertIn("Cannot snapshot installed package " + package + "; nothing changed.", output)
        self.assertEqual(self.state(), self.initial)
        for command in self.shell_commands():
            read_only = (
                command[0] in ("getprop", "dumpsys")
                or command[:3] == ["pm", "list", "packages"]
                or command[:2] in (["settings", "get"], ["settings", "list"])
                or command[:3] in (["cmd", "package", "query-activities"],
                                  ["cmd", "package", "resolve-activity"])
            )
            self.assertTrue(read_only, command)
        self.assertFalse(any("push" in command for command in self.commands()))
        # The injected failure is transient, not a permanently missing package.
        self.run_script()
        self.assertEqual(self.state()["packages"][package]["enabled"], 3)

    def test_large_dumps_preserve_existing_permission_and_service_markers(self):
        self.initial["packages"][HOME] = {
            "installed": True, "enabled": 0,
            "grants": ["android.permission.ACCESS_FINE_LOCATION"],
        }
        self.initial["packages"][KEEPALIVE] = {
            "installed": True, "enabled": 0,
            "grants": ["android.permission.POST_NOTIFICATIONS"],
        }
        self.initial["running"] = True
        self.initial["large_dumps"] = True
        self.save(self.initial)
        self.run_script()
        backup, = (self.repo / "backups").iterdir()
        for marker in ("location-was-granted", "notifications-were-granted", "keepalive-was-running"):
            self.assertTrue((backup / marker).is_file(), marker)
        before = len(self.shell_commands())
        self.run_script("restore.sh", backup)
        state = self.state()
        self.assertTrue(state["running"])
        self.assertEqual(state["packages"], self.initial["packages"])
        self.assertEqual(state["settings"], self.initial["settings"])
        restore_commands = self.shell_commands()[before:]
        self.assertFalse(any(c[:2] in (["pm", "revoke"], ["pm", "uninstall"])
                             for c in restore_commands))
        self.assertIn(["am", "start-foreground-service", "-n", KEEPALIVE + "/.KeepAliveService"],
                      restore_commands)

    def test_restore_default_states_settings_and_original_home(self):
        self.initial["packages"][AERIAL] = {"installed": True, "enabled": 0}
        self.save(self.initial)
        self.run_script()
        self.assertEqual(self.state()["packages"][DREAM]["enabled"], 3)
        backups = list((self.repo / "backups").iterdir())
        self.assertEqual(len(backups), 1)
        self.run_script("restore.sh", backups[0])
        state = self.state()
        self.assertEqual(state["home"], STOCK_COMPONENT)
        self.assertEqual(state["focus"], STOCK_COMPONENT)
        self.assertEqual(state["settings"], self.initial["settings"])
        self.assertFalse(state["running"])
        for package, original in self.initial["packages"].items():
            self.assertEqual(state["packages"][package], original, package)
        for package in (HOME, KEEPALIVE):
            self.assertFalse(state["packages"][package]["installed"])
        self.assertIn(["pm", "default-state", "--user", "0", STOCK], self.shell_commands())
        self.assertIn(["settings", "delete", "secure", "screensaver_default_component"], self.shell_commands())


if __name__ == "__main__":
    unittest.main()
