# PET Plugin (ATAK-CIV)

The PET plugin runs inside [ATAK-CIV](https://github.com/TAK-Product-Center/atak-civ) on Android. It lets field users package supply-chain evidence as ATAK data packages, fingerprint each package with a SHA-256 hash, and send it over TAK.

> This folder keeps the plugin's original repository name, `lingualinkledger`, and the code uses the name **Lingua Link Ledger** (package `com.atakmap.android.lingualinkledger`). See [Component names](../README.md#component-names).

**Status:** prototype. The plugin hashes packages and stores the hash **on the device only**. It does not yet send hashes to the [blockchain backend](../witec-demo/README.md).

---

## Features

The plugin adds a **Lingua Link Ledger** button to the ATAK toolbar. It opens a pane with three controls:

| Control | What it does |
|---|---|
| **Create/Edit Data Package** | Opens a form for package name (required), description, author, and latitude/longitude (pre-filled from the current map center). Writes the metadata to a text file and builds an ATAK data package that contains it. |
| **Send Data Package** | Lets you pick a data package (`.zip`) and computes its SHA-256 hash. Saves the hash to the device, shows it for 2 seconds, then opens ATAK's standard Send dialog. |
| **?** | Help dialog. _Its text is out of date and will be updated._ |

### Files the plugin writes on the device

All paths are relative to the ATAK data directory (usually `/sdcard/atak/`).

| Path | Contents |
|---|---|
| `tools/lingualinkledger/<package name>_metadata.txt` | Metadata entered in the form, plus a creation timestamp |
| `tools/lingualinkledger/hashed_packages/<last 6 hash chars>.txt.zip.dtg.sha256` | The SHA-256 hash (hex) of a sent package, saved read-only |
| `tools/datapackage/` | ATAK's default data package folder; the file picker starts here |

The plugin requests the `MANAGE_EXTERNAL_STORAGE` permission so it can read and write these folders.

---

## Requirements

| Requirement | Version / notes |
|---|---|
| ATAK-CIV SDK | **5.4.0** (see note below). [Releases](https://github.com/TAK-Product-Center/atak-civ/releases) |
| Android Studio | A recent version |
| JDK | 17 |
| Gradle | 8.9 (pinned by the included wrapper) |
| Android device | ATAK-CIV **5.4.0** installed, developer mode and USB debugging enabled |

> **Match the ATAK version.** The plugin is built for ATAK **5.4.0** (`ATAK_VERSION` in `app/build.gradle`), and ATAK loads only plugins built for its own version. Download the **5.4.0** SDK release, not the latest one, and install the ATAK APK from that same SDK on your device.

---

## Build and install

### 1. Set up the SDK and the device

1. Download the ATAK-CIV **5.4.0** SDK zip from the [releases page](https://github.com/TAK-Product-Center/atak-civ/releases) and extract it. This README calls that folder `atak-civ-sdk/`.
2. Copy the ATAK APK from the extracted folder to your Android device and install it.
3. On the device, enable developer mode and USB debugging.

### 2. Put the repo in the right place

Clone the PET repository **as the `plugins` folder** inside the SDK:

```bash
git clone https://github.com/SyracuseUniversity/PET.git atak-civ-sdk/plugins
```

```
atak-civ-sdk/
├── atak-gradle-takdev.jar
└── plugins/                  ← the PET repository
    ├── lingualinkledger/     ← open this folder in Android Studio
    └── witec-demo/
```

**Why this location:** the build needs `atak-gradle-takdev.jar`, which ships with the SDK. `app/build.gradle` looks for it two folders above `lingualinkledger/`. Cloning PET as `plugins/` puts the plugin at `atak-civ-sdk/plugins/lingualinkledger`, the same path as the original setup, so the jar is found there.

### 3. Generate signing keys

ATAK only loads signed plugins. Generate a debug and a release keystore by running these commands in your terminal from the plugin folder (`lingualinkledger/`):

```bash
keytool -genkeypair -dname "CN=Android Debug,O=Android,C=US" -validity 9999 -keystore debug.keystore -alias androiddebugkey -keypass android -storepass android -keyalg RSA

keytool -genkeypair -dname "CN=Android Release,O=Android,C=US" -validity 9999 -keystore release.keystore -alias androidreleasekey -keypass android -storepass android -keyalg RSA
```

### 4. Create `local.properties`

Create `lingualinkledger/local.properties` (already ignored by git):

```properties
# the sdk.dir should be automatically assigned to the path of your Android Studio SDK
sdk.dir=<ANDROID_SDK_PATH>

takDebugKeyFile=<ABSOLUTE_PLUGIN_PATH>\\debug.keystore
takDebugKeyFilePassword=android
takDebugKeyAlias=androiddebugkey
takDebugKeyPassword=android

takReleaseKeyFile=<ABSOLUTE_PLUGIN_PATH>\\release.keystore
takReleaseKeyFilePassword=android
takReleaseKeyAlias=androidreleasekey
takReleaseKeyPassword=android
```

On Windows, escape backslashes in paths (`C:\\Users\\...`) or use forward slashes.

### 5. Configure Android Studio

1. Open `atak-civ-sdk/plugins/lingualinkledger/` (the folder containing `settings.gradle`).
2. Go to **File → Project Structure**, and under **Project** change the Gradle version to **8.9**.
3. Set the Gradle JDK to **Java 17** (Java 17 works at the time of writing): press **Shift** twice, search for "Gradle JDK", and select 17.
4. Click the dropdown next to the **Run** button → **Edit Configurations**, and under **Launch Options** set **Launch** to **Nothing**, then apply. The plugin has no app screen of its own; Android Studio installs it and ATAK loads it.
5. In the left-hand **Build Variants** panel, make sure the active build variant is **civDebug**.

### 6. Build, install, and load

1. Connect the device and click **Run**. Android Studio builds and installs the plugin APK.
2. In ATAK, open the **Plugins** tool, find **Lingua Link Ledger**, and load it.
3. The **Lingua Link Ledger** button appears in the ATAK toolbar.

<!-- TODO: Confirm step 2 wording against ATAK 5.4.0 UI. -->

The built APK is written to `app/build/outputs/apk/` and named `ATAK-Plugin-lingualinkledger-1.0-...-5.4.0-...apk`. (Gradle takes `lingualinkledger` from the folder name.)

---

## Build variants

`app/build.gradle` defines product flavors for many TAK distributions (`civ`, `mil`, `gov`, and several national variants). **Use `civ`**, which is the default and the only one that works with the public ATAK-CIV SDK. The other flavors, and the `.gitlab-ci.yml` file, come from the TAK plugin template and require access to TAK's private build infrastructure.

| Variant | Use |
|---|---|
| `civDebug` | Local development |
| `civRelease` | Minified (ProGuard) release build, signed with your release key |

---

## Project structure

```
lingualinkledger/
├── app/
│   ├── build.gradle                  Plugin build config (versions, flavors, signing)
│   ├── proguard-gradle.txt           ProGuard rules for release builds
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   Permissions and ATAK plugin discovery entry
│       │   ├── assets/plugin.xml     Registers the plugin class with ATAK
│       │   ├── java/com/atakmap/android/lingualinkledger/plugin/
│       │   │   ├── LinguaLinkLedger.java     All plugin logic (UI, packaging, hashing, send)
│       │   │   └── PluginNativeLoader.java   TAK template native-library loader (currently unused)
│       │   └── res/                  Layouts, drawables, strings, styles
│       ├── gov/                      Resources for the gov flavor (template)
│       └── test/                     Unit tests (placeholder only)
├── build.gradle, settings.gradle, gradle.properties
└── gradlew, gradlew.bat, gradle/     Gradle wrapper (8.9)
```

**Entry point:** ATAK reads `assets/plugin.xml` and instantiates `LinguaLinkLedger`, which implements `gov.tak.api.plugin.IPlugin`. `onStart()` adds the toolbar button, and tapping it calls `showPane()`.

---

## Testing

There are no meaningful automated tests yet. `ExampleTest.java` is a template placeholder. Test manually on a device: create a package, send it, and confirm the hash file appears under `tools/lingualinkledger/hashed_packages/`.

---

## Troubleshooting

- **ATAK APK won't install:** you may not be able to install it if a different TAK version was installed before. Try removing any TAK-related folders and files from the device, then install again.
- **After installing ATAK:** grant all permissions, then manually go to Android **Settings** and allow ATAK to access location **all the time**.
- **Gradle can't find `atak-takdev-plugin`:** the build can't locate `atak-gradle-takdev.jar`. Check the repo location ([step 2](#2-put-the-repo-in-the-right-place)).
- **Plugin installs but doesn't appear in ATAK:** check that the ATAK version on the device matches the SDK version the plugin was built against.
- More setup help: [LearnATAK: Android Studio setup](https://toyon.github.io/LearnATAK/docs/setup/android_studio_setup/) (third-party guide).

---

## Roadmap

- Record package hashes on-chain through the [blockchain backend](../witec-demo/README.md). Planned, not yet implemented.
