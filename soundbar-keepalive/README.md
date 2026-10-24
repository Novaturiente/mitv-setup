# Soundbar Keep Alive

Experimental native Java service for Xiaomi MiTV-AXFR2 / Android 14 and a JBL CINEMA SB231 over HDMI ARC. No app screen, dependencies, network access, audio-focus request, or ongoing ADB connection is required.

The foreground service loops one second of stereo 48 kHz PCM: **200 Hz, peak 32/32768** (about −60 dBFS). Android loops a static 192 KB audio buffer; Java does not continually generate samples. The signal is not guaranteed to be inaudible or to prevent standby. It does not send a CEC wake command.

The code pauses audio when the TV is not interactive and resumes when it is interactive. **Screen-off pausing has not been verified on hardware.**

## Install the bundled APK

For normal recovery, use `bash setup.sh` from the repository root. It checks `apks/soundbar-keepalive.apk` against `apks/SHA256SUMS`, installs it, grants notification permission, and starts the service. See the [root setup guide](../README.md) for debugging authorization and backups.

On the known Android 14 profile, the boot receiver starts the media-playback foreground service after reboot. Boot startup was verified on the physical TV without an ADB start command. Debugging can be switched off after setup.

## Build from source

From the repository root:

```sh
bash soundbar-keepalive/build-install.sh
```

Requires an Android SDK and JDK 17 or newer, plus `zip`. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your SDK directory if needed (`ANDROID_HOME` takes precedence). Set `ANDROID_BUILD_TOOLS` to a build-tools directory or an installed version when needed. The build uses the Android 35 platform jar and targets **SDK 34** (minimum SDK 26); compiling with Android 35 does not change its Android 14 target.

The build runs the signal self-test, signs the APK, and verifies its signature. Output: `soundbar-keepalive/build/keepalive.apk`. It does not change the bundled APK or its checksum automatically.

To build, install, grant notification permission, and start on an authorized TV:

```sh
ADB_SERIAL='<DEVICE_IDENTIFIER>' bash soundbar-keepalive/build-install.sh --install
```

Use the identifier from `adb devices`; omit `ADB_SERIAL` when only one device is connected. Signing keystores are not committed. Keep the original key separately: another key cannot update an already installed copy. A factory reset removes that installed-app constraint but also removes apps and user data.

## Restart, stop, or remove

The service does not restart automatically after a process kill. Android force-stop suppresses boot delivery until the app is explicitly started again. With debugging enabled and ADB authorized, start it manually:

```sh
adb shell am start-foreground-service -n com.nova.soundbarkeepalive/.KeepAliveService
```

Use `adb -s '<DEVICE_IDENTIFIER>'` instead of `adb` in these manual commands when multiple devices are connected.

Use the notification's Stop action if the TV exposes it, or Settings > Apps > Soundbar Keep Alive > Force stop. With ADB:

```sh
adb shell am stopservice -n com.nova.soundbarkeepalive/.KeepAliveService
adb shell pm uninstall com.nova.soundbarkeepalive
```

Stopping the service is not permanent across reboot; uninstall it to remove boot startup. Force-stop has the separate Android behavior described above.

## Hardware results and limits

On the tested HDMI ARC setup, version 0.2's 200 Hz signal kept the soundbar awake for **at least 20 minutes paused**, with no unwanted sound reported. Normal playback and Dolby playback from JioHotstar both worked. After waking the TV, ADB confirmed the foreground service and HDMI ARC were active. These are observations from one setup, not a guarantee.

An earlier 30 Hz test initially appeared successful, but the soundbar later entered standby while the app and audio track remained active. Do not treat that earlier test as proof.

Start with the soundbar awake. Pause beyond its usual standby interval, resume, and check for hum or changed playback. Test normal and Dolby playback separately. Background PCM mixing may affect Dolby passthrough. Stop the service if the signal is audible or interferes with playback. Do not raise the amplitude without listening tests.
