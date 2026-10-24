# MiTV setup

Recovery and setup tools for [Novaturiente/mitv-setup](https://github.com/Novaturiente/mitv-setup): a native Android TV launcher and reversible debloating.

**Required profile:** Xiaomi `MiTV-AXFR2`, board `watchmen` (Amlogic), Android 14 / SDK 34, firmware `V816.0.26.5.26.UFZAATV`, 1.5 GB RAM, 1280×720. Setup requires this exact model, board, Android version, and firmware; other firmware versions, including future updates, are refused. This is a setup recipe after a reset, not a firmware image or a backup of your apps.

## Before setup

1. Finish the TV's initial setup, including network setup. Do not disable the setup wizard before finishing.
2. On your computer, install Bash and Android Platform Tools (`adb`). Normal setup uses the bundled signed APKs; no Android SDK build tools, JDK, or Gradle are needed.
3. Enable developer options and USB debugging on the TV. Enable network debugging too if connecting over the network. Connect ADB and accept the authorization prompt on the TV.
4. Check `adb devices`. Use `ADB_SERIAL` to select a device when more than one is connected.
5. Install any third-party apps you want before setup disables Play Store. Use the official sources below and sign in again as needed.

For a network connection, replace the placeholder with your TV's debugging address:

```sh
adb connect '<TV_DEBUG_ADDRESS>'
adb devices
```

Do not expose ADB to the internet. Turn debugging off when finished; the installed launcher does not need an ongoing ADB connection.

## Third-party apps

Third-party APKs are **not redistributed** here. Setup reports missing apps; it does not download or install third-party APKs automatically.

- [SmartTube releases](https://github.com/yuliskov/SmartTube/releases): preferred instead of stock YouTube.
- [Jellyfin downloads](https://jellyfin.org/downloads): choose the Android TV client.
- [JioHotstar on Google Play](https://play.google.com/store/apps/details?id=in.startv.hotstar): use the official listing and check TV/region availability.
- [Aerial Views releases](https://github.com/theothernt/AerialViews/releases): optional screensaver.
- [Aurora Store](https://auroraoss.com/): official project site.
- [Netflix on Google Play](https://play.google.com/store/apps/details?id=com.netflix.ninja): official Android TV listing.
- [Prime Video on Google Play](https://play.google.com/store/apps/details?id=com.amazon.amazonvideo.livingroom): official Android TV listing.

Setup also warns when Aurora Store, Netflix, or Prime Video is missing.

App installation, app data, subscriptions, and account logins are not backed up or restored by these scripts.

## Run setup

Run from the repository root:

```sh
bash setup.sh
```

Optional device selection, using the identifier shown by `adb devices`:

```sh
ADB_SERIAL='<DEVICE_IDENTIFIER>' bash setup.sh
```

`install.sh` is a compatibility wrapper for `setup.sh`, not a source build command.

Setup checks the signed launcher APK at `apks/launcher.apk` against `apks/SHA256SUMS`. It then saves the run's state locally under `backups/TIMESTAMP/` and generates that backup's `restore.sh` recovery script. Keep the backup directory on your computer.

Setup configures:

- The custom launcher (`com.nova.tvhome`) as Home.
- The launcher's “Home remote keys” accessibility service to map the PatchWall button to Live TV.
- Reversible package disabling according to `disable-list.txt`.
- Aerial Views as screensaver **only if installed**.

The intended policy keeps **Play Store, stock YouTube, and Google Assistant disabled**. SmartTube is the YouTube preference. Cast, screen mirroring, firmware update, USB media, HDMI/ARC, and remote system services are kept. Setup disables packages rather than hiding or deleting system packages.

### Setup refuses because a saved network belongs to setupwraith

`com.google.android.tungsten.setupwraith` is the Google TV setup wizard. Disabling it can delete Wi-Fi networks it owns. If setup reports such a network, use the TV's Settings to **forget that saved network and rejoin it**, then rerun setup. Do not bypass this check.

## Restore a setup run

With debugging enabled and ADB authorized, run from the repository root:

```sh
bash restore.sh backups/TIMESTAMP
```

Replace `TIMESTAMP` with the actual backup directory name. `ADB_SERIAL` can select the target here too. Root `restore.sh` is the shared recovery implementation; the generated `backups/TIMESTAMP/restore.sh` invokes it, not the other way around. Keep the repository with your backups.

Recovery restores recorded per-user package enabled states, including default states, selected settings, and Home. It revokes permissions newly granted by setup and removes our apps if they were absent before that run. It does **not** roll back APK versions or app data, restore logins, or replace a factory image.

A factory reset removes installed apps, settings, and logins. These scripts do not recover those losses. Packages already hidden by older tools may remain hidden: this firmware can reject ADB unhide with `MANAGE_USERS`. Re-enabling is not the same as unhiding; a factory reset may be needed for such legacy changes.

## Known trade-offs

- **PatchWall remap:** the accessibility service consumes keycode 192 (`KEY_WWW`) and opens Live TV. On the tested firmware, enabling it prevents held Volume from ramping: use individual presses. Disable only “Home remote keys” in the TV's accessibility settings to regain repeat behavior; rerunning setup enables it again.
- Do not open the factory service app (`com.mitv.bspfactoryservice`).

## Build and customize

Source builds require an Android SDK, JDK 17 or newer, and the SDK tools `aapt2`, `d8`, `zipalign`, and `apksigner`, plus `zip`. Set `ANDROID_HOME` or `ANDROID_SDK_ROOT` to your SDK directory when it is outside the default location (`ANDROID_HOME` takes precedence). Set `ANDROID_BUILD_TOOLS` to a build-tools directory or an installed version when needed. The SDK platform used to compile is separate from the TV's Android version and the app's target SDK.

```sh
bash launcher/build.sh
```

The launcher output is `launcher/build/tvhome.apk`. Building alone does not update `apks/launcher.apk` or its checksum. When publishing your own APK, replace the bundled file and regenerate `apks/SHA256SUMS` before setup.

Edit `launcher/res/values/config.xml` to change `dock_apps` (package names, left to right), `columns`, `hidden_apps`, or the Live TV mapping. Keep `columns` equal to the number of dock apps (4–7). Rebuild and replace the bundled launcher APK and checksum before rerunning setup. Unselected app icons are white and become colored when focused. Grid icons are smaller than dock icons and always show their app names. Aerial Views is hidden from Home without disabling its screensaver.

### Continue Watching

The source launcher requests Android's `READ_TV_LISTINGS` permission to read shared Watch Next entries. It shows up to five unfinished titles from installed dock apps, excluding SmartTube and Prime Video, immediately below the dock. SmartTube is last in the dock. Titles are sorted by recent engagement and open the supplying app's resume link. Cards load supplied poster artwork (or thumbnails) in the background, with the title and playback position overlaid. The app icon remains visible if artwork is absent or unavailable. Artwork requires the normal `INTERNET` permission; HTTPS images are supported, with bounded downloads, sampled bitmap decoding, and an in-memory cache. Other URI schemes use the app-icon fallback. Private account integrations are not included.

The list refreshes when Home resumes. The row is hidden when permission is denied or no eligible entries are available. Streaming apps decide whether to publish entries and whether their links actually resume playback; this is not access to their complete private viewing history. These source changes do not update the bundled APK automatically.

Run the selection and artwork-limit tests from the repository root:

```sh
javac --release 17 -cp "$HOME/Android/Sdk/platforms/android-35/android.jar" -d /tmp/tvhome-tests launcher/src/com/nova/tvhome/WatchNext.java launcher/test/com/nova/tvhome/WatchNextTest.java
java -cp "/tmp/tvhome-tests:$HOME/Android/Sdk/platforms/android-35/android.jar" com.nova.tvhome.WatchNextTest
javac --release 17 -cp "$HOME/Android/Sdk/platforms/android-35/android.jar" -d /tmp/tvhome-tests launcher/src/com/nova/tvhome/Artwork.java launcher/test/com/nova/tvhome/ArtworkTest.java
java -ea -cp "/tmp/tvhome-tests:$HOME/Android/Sdk/platforms/android-35/android.jar" com.nova.tvhome.ArtworkTest
```

For a wallpaper, add `wallpaper.jpg` or `wallpaper.png` under `launcher/res/drawable-nodpi/` (create the directory), at most 1920×1080. Rebuild and update the bundled APK and checksum. Remove the image and rebuild to return to black.

## Signing, privacy, and licensing

Signing keystores are excluded from Git. **Keep them separately and back them up securely.** Losing the original key prevents installing your new build as an update over the existing app. A factory reset removes that installed-app signing constraint, but also erases apps and user data.

`.gitignore` excludes private backups, screenshots, signing keys, and build output. Backups can contain identifying device/network state; do not publish them. Only the first-party signed launcher APK belongs in `apks/`.

The bundled Outfit font has its own SIL Open Font License. That font license does not license the repository's code; no repository-wide license is implied here.
