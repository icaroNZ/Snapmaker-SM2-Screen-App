# CLAUDE.md - Snapmaker-SM2-Screen-App ("FabScreen")

## Snapmaker 2.0 / A250T workspace notes

Part of the 20-repo workspace at `/Users/icaro/Documents/Projects/SnapMaker` (all on branch `a250t/main`). The owner
has a Snapmaker 2.0 **A250T** (A250 frame, TMC linear modules) with single/dual extruder, laser and CNC, and uses Luban
and Snapmaker Orca on macOS. Workspace dependency map: `../.claude/knowledge/DEPENDENCIES.md`. Facts file:
`../.claude/knowledge/repos/Snapmaker-SM2-Screen-App.json`. Specialist agent: `sm-screen`. Full review:
`../_snapmaker2-review/repos/Snapmaker-SM2-Screen-App.md`.

### Purpose
FabScreen is the Android app on the Snapmaker 2.0 touchscreen (stock UI for 3DP, laser, CNC, calibration wizards,
add-ons and firmware updates). It talks to the controller over UART with the binary **FabPacket** protocol (the
controller calls it **SSTP**; Luban's client class is `SstpHttpChannel`), and serves the LAN **HTTP API on port 8080**
and **UDP discovery on 20054** that Luban, Snapmaker Orca and the Cura plugin use. It is **not SACP**.

### State of this branch
- `a250t/main` = upstream `main` (one squashed commit `b9c4764`, app `V1.19.3`) + this file. No code changes.
- No licence ("TBD"). CI: `.github/workflows/a250t-build.yml` (ours, ubuntu-22.04, JDK 8, NDK 22.1.7171670; runs
  `assembleDebug` + `test`, uploads the debug APKs; CI-only `.github/ci/repos.init.gradle` puts google()/mavenCentral()
  before the Aliyun mirrors). As of 07/11/2026 it has NOT run yet (no GitHub fork to run it on). One real JVM test class, `fabscreen/src/test/java/com/snapmaker/fabscreen/FabPacketTest.java`
  (frame round-trip, checksum vectors, stream reader); the other test files are Android Studio placeholders.

### Tech stack
Java 8 (603 files), Android compileSdk/targetSdk 28, minSdk 23 (stock screen runs SDK 25), AGP 3.5.3, Gradle wrapper
5.4.1, NDK 22.1.7171670 + CMake for one JNI file (`libraries/fabscreen-legacy/src/main/cpp/SerialPort.cpp`), RxJava 2,
ButterKnife, AndServer 2.0.4 (HTTP server), Retrofit 2.5, Okio, ARouter, libaums (USB), Glide, orhanobut Logger,
Firebase Crashlytics/Analytics. Versions live in `buildSrc/src/main/java/Dependencies.kt` (`Libs.INSTANCE.*`).
Repos are Aliyun mirrors + mavenCentral + jitpack (`build.gradle:6-43`).

### Build and test (UNVERIFIED on this Mac)
Nothing has been built here: this Mac (arm64) has **no JDK and no Android SDK** (`/usr/libexec/java_home` fails,
`ANDROID_HOME` unset). Prerequisites: JDK 8 (Gradle 5.4.1 does not run on modern JDKs), Android SDK platform 28 +
build-tools, NDK 22.1.7171670, CMake 3.x; `local.properties` with `sdk.dir=...` (gitignored).
```bash
cd /Users/icaro/Documents/Projects/SnapMaker/Snapmaker-SM2-Screen-App
export JAVA_HOME=$(/usr/libexec/java_home -v 1.8)
cp fabscreen/google-services.json.example fabscreen/google-services.json   # or remove the 2 Firebase plugins
./gradlew assembleDebug        # -> fabscreen/build/outputs/apk/debug/fabscreen-release_V1.19.3.apk (name set in fabscreen/build.gradle:9-13)
./gradlew test                 # FabPacketTest (4 tests) + placeholder ExampleUnitTests
```
See skill `screen-build-test` for details and failure modes. Debug builds inject `MockSlaveComputer`
(`data/Model.java:43-44`, reports an A250) so the UI, HTTP API and update flow run in an emulator without hardware.

### Directory map
| Path | Purpose |
|---|---|
| `fabscreen/` | App module `com.snapmaker.fabscreen`; `src/main/java/com/snapmaker/fabscreen/modules/<feature>/` (home, browse, preview, print, printsettings, control, calibration, lasercalibration, laser10wcamera, cnc*assistant, dualextrudercalibration, loadfilament, guide*, settings, update, remote, enclosure, airpurifier, emergencystop, about, factory, experiment); `res/` 7 locales, ~60 MB GIF/PNG |
| `libraries/fabscreen-legacy/` | Core library, package `fabscreen.libraries.legacy` (below `L/` = `src/main/java/fabscreen/libraries/legacy/`) |
| `L/data/` | `Model`, `MachineController`, `PrintController`, `Preferences`, `Constants`, `ISlaveComputer`, `MockSlaveComputer`, `LaserCameraController` (Bluetooth), `module/`, `remote/`, `version/`, `api/` (cloud), `calibration/` |
| `L/data/serial/` | `SerialConnection` (115200 baud; device `/dev/ttyHSL1` set in `MachineController.java:33`), `SerialController` (request/response state machine), `fabpacket/` (frame, builders, payload parsers) |
| `L/server/` | `http/HTTPServer` (8080), `http/handlers/` (Auth, Main, Camera, Orca), `socket/DiscoverServer` (UDP 20054) |
| `L/lib/` | `serialport` (JNI), `fabserver` (AndServer wrapper), `file` (local + USB via libaums), `network` (Wi-Fi), `parser` (G-code header/preview), `update` (bundle parser), `opengl` (preview), `crash`, `LogHelper` |
| `L/route/RoutePath.java` | ARouter paths |
| `libraries/fabscreen-legacy/src/main/res/raw/` | committed `fabscreen_updating_1_3.apk`, `a{150,250,350}_{dual_extruder_print_check,xy_calibration_print}_04_04.gcode` |
| `fabscreen-updating/` | Helper APK `com.snapmaker.updating` 1.3 (updating splash, relaunch after replace, crash restart) |
| `features/about/` | Near-empty library placeholder |

### Entry points and core files
- `fabscreen/.../FabScreenApplication.java:28-66` - starts `SerialPortService`, `DiscoverServer`, `HTTPServer`.
- `modules/home/MainActivity` (HOME/LAUNCHER, kiosk) -> `HomeActivity`; `modules/remote/RemoteHomeFragment` shows the
  "remote connect request" Yes/No dialog (`:566-590`).
- Protocol: `FabPacket.java:12-34` (event IDs), `FabPacketBuilder.java` (every request), `FabPacketContent.java`
  (reply parsers; `MachineSize` `:509-541`), `SerialController.onReceive()` `:1051` (reply dispatcher).
- HTTP: `server/http/handlers/MainRequestHandler.java` (routes `:54-104`), `AuthRequestHandler.java`,
  `BaseRequestHandler.ensureConnection()` `:22-46`, `CameraRequestHandler.java`, `OrcaRequestHandler.java`.
- Machine knowledge: `data/Constants.java` (models `:11-14`, `HEAD_*` `:17-26`, `FILE_TYPE_*` `:34-39`),
  `data/module/SnapModule.java:30-75` (module type IDs), `data/version/VersionRequirementManager.java` (min V1.9.2).
- Update: `lib/update/FabUpdatePackage.java`, `FabUpdater.java` (512-byte parts), `modules/update/UpdateViewModel.java`,
  `receiver/InstallProcessReceiver.java` (APK self-install via PackageInstaller).

### Coding conventions seen in the code
- Logging: `com.orhanobut.logger.Logger.d/i/w/e("fmt %s", arg)`; exceptions go to `LogHelper.log(e)` (also disk log
  under cacheDir/log, `lib/LogHelper.java:37-39`). HTTP handlers log slow calls via `logResponseCostTime`.
- Async: RxJava 2 everywhere; controller calls return `Observable<T>` from `ISlaveComputer`; collect `Disposable`s in a
  `CompositeDisposable`; UI uses AutoDispose `.as(bindToLifecycle())` and `observeOn(AndroidSchedulers.mainThread())`.
- UI: ButterKnife (`@BindView`, `@OnClick`), `BaseFragment` with `getLayoutResID()`, `initView()/initData()` (README
  style guide); ViewModels extend `BaseViewModel`; navigation via ARouter `RoutePath`.
- Naming: `m`-prefixed fields, `UPPER_SNAKE` constants, `URI_*` constants for HTTP routes, opcodes as hex literals with a
  `(0xEE 0xOO)` javadoc comment in `FabPacketBuilder`. Always `String.format(Locale.US, ...)` for G-code.
- Strings: every user string in `fabscreen/src/main/res/values*/strings.xml` (7 locales).

### How to extend
- **New controller command (FabPacket opcode):** builder in `FabPacketBuilder.java` -> method in `ISlaveComputer.java`
  -> implement in `SerialController.java` (`request(packet[, timeout]).map(...)`) **and** `MockSlaveComputer.java` ->
  reply parser in `FabPacketContent.java` -> `case` in `SerialController.onReceive()`. Requests are matched to replies by
  `FabPacket.getKey()` (event id pair, plus the first payload byte for event ids > 0x04). The controller side must exist in
  `Snapmaker2-Controller/snapmaker/src/hmi/event_handler.{h,cpp}` (ask `sm-controller`).
- **New HTTP endpoint:** add a `URI_*` constant and `@GetMapping/@PostMapping` method in a `@RestController` handler
  under `server/http/handlers/`; call `if (!ensureConnection(request, response)) return;` first unless it must be
  public. Update the clients (Luban `SstpHttpChannel.ts`, Orca `Snapmaker2.cpp`) only if they need it.
- **New toolhead:** `Constants.HEAD_*` (must equal controller `ModuleToolHeadType`, `module_base.h:342-352`),
  `SnapModule.ModuleType` + `SnapModuleFactory`, `MachineController` head switches (`:868`, `:932`), `/api/v1/status`
  toolhead block in `MainRequestHandler`, Control/Preview/Print fragments that switch on `HEAD_*`, a `guide*` package.
- **New G-code header key:** `lib/parser/GcodeParser.java` switch at `:382` (+ `SnapmakerParser.java` for preview).
- **New string / locale:** add to all `values-*/strings.xml`.

### Cross-repo interfaces (details in DEPENDENCIES.md and the JSON facts file)
| Interface | Other side (agent) |
|---|---|
| UART FabPacket/SSTP: `AA55`, len, ver, len-check, checksum, event id; EIDs 0x01-0x14, 0xa9/0xaa | `Snapmaker2-Controller` `snapmaker/src/common/protocol_sstp.h`, `snapmaker/src/hmi/event_handler.h` (`sm-controller`) |
| Machine model/size 0x09 0x14, status 0x07/0x08 (headType 0-9) | controller `service/system.cpp:2533`, `module/linear.cpp`, `module/module_base.h` (`sm-controller`) |
| Module type IDs (12 = TMC linear module, the A250T rails) | controller `module_base.h:83-102`, `Snapmaker2-Modules` `Marlin/src/configuration.h:111` (`sm-modules`) |
| Firmware bundle `.bin` (major header, part types 0/1/2) | controller `snapmaker/scripts/pack.py` (`sm-controller`) |
| HTTP 8080 `/api/v1/*` (token + touchscreen approval) | Luban `src/server/services/machine/channels/SstpHttpChannel.ts` (`sm-luban`), OrcaSlicer `src/slic3r/Utils/Snapmaker2.cpp` (`sm-orca`), Cura `network_plugin/HTTPNetworkedPrinterOutputDevice.py` (`sm-cura`) |
| UDP 20054 `discover` | Luban `network-discover/NetworkedMachineFinder.ts:9`, Cura `network_plugin/DiscoverSocket.py` |
| G-code `;Header Start` block | written by Luban (`src/server/slicer/post-processor.ts`, wraps LunarSlicer output; `workers/generateGcode.js` laser/CNC) and Cura (`gcode_writer/SnapmakerGCodeWriter.py`) (`sm-luban`, `sm-cura`); Orca and the CNC posts write none, so no preview/bounds/time (`sm-orca`, `sm-cnc-post`) |
| Cloud `GET /v1/fabscreen/version` (api.snapmaker.com) | same JSON shape as `snapmaker-version-center/upgrade/firmware/fabscreen/version.json` (`sm-version-center`) |
| SACP | **not used** here (`sm-sacp` covers J1/Artisan/A400) |

### HTTP API behaviour other repos depend on
- `POST /api/v1/connect` (optional `token`): returns `{token, readonly, series, headType, hasEnclosure}`; 403 whenever
  a session with a different token is current, pending or granted (`AuthRequestHandler.java:33`). A new token must be approved with **Yes** on the touchscreen.
- Every other `/api/v1/*` call: `?token=`; 400 no token, 401 not current session, **204 = awaiting approval**.
- Session expires after **5 s** without requests (`SessionManager.java:161`, checked every 5 s by
  `RemoteController.java:63`); up to 10 granted tokens persist in `session.json` (`SessionManager.java:86`).
- `execute_code`: GBK-decoded, **only the first line** is sent, refused while a job runs (`MainRequestHandler.java:613-660`).
- `prepare_print`: `type` must be exactly `3DP`, `CNC` or `Laser`; rejected if it conflicts with the mounted toolhead.
- `POST /api/files/local`, `GET /api/version`, `GET /` (`OrcaRequestHandler.java`) and the camera routes are
  **unauthenticated**; `/api/files/local?print=true` only starts FDM jobs.
- `/api/v1/status` `toolHead` key is deprecated (comment in code) - use `headType` from connect.

### A250T-specific notes
- No A250T model exists in the app: `getMachineSize` model byte is 1/2/3 (A150/A250/A350); A250T == A250 everywhere
  except the About string `settings_snapmaker2_a250` = "Snapmaker 2.0 A250/A250T/F250" (`strings.xml:760`).
- TMC rails report module type 12 (`MOTION_LINEAR_MODULE_TMC_2021`), mapped to the generic `LinearModule`; min module
  firmware V1.9.2 (`VersionRequirementManager.java`).
- Our controller build `SM2-5.2.1-a250t.1` is flashed through this app as a `.bin` (USB or Wi-Fi). `UpdateViewModel.java:134-140`
  **skips** the controller part when the bundle's controller version (minor image bytes 5-36, `FabUpdatePackage.java:82`)
  equals the 0xa9 0x03 reply, unless `FLAG_FORCE_UPDATE` (bit 1) is set; `pack.py:195` writes flag 0. The controller
  reply drops the first 10 bytes of its stored version (`Snapmaker2-Controller/snapmaker/src/service/upgrade.cpp`,
  `GetMainControllerVer`): `Snapmaker_V5.2.1-a250t.1` vs `V5.2.1-a250t.1`, and neither side strips the prefix
  (`SerialController.java:1568-1580`), so per code the controller part is always re-sent (hardware UNVERIFIED). Still
  bump the version for every reflash.
- With the controller's optional `HOST_PRINT_STATUS_TRACKING` (default OFF), USB-serial host jobs show status 3
  ("working with PC port") and the screen's Pause/Stop are refused (see `../_snapmaker2-review/integration/Snapmaker2-Controller.md`).
- The dual-extruder calibration prints come in A150/A250/A350 variants; A250T uses the `a250_*` files.

### What NOT to do
- Never flash firmware, never push, never change remotes. Commit only what you are asked to (this file was committed alone).
- Do not try to sideload a self-built APK onto the stock screen: `android:sharedUserId="android.uid.system"`
  (`fabscreen/src/main/AndroidManifest.xml:59`) needs the platform key; `release` is signed with the debug key
  (`build.gradle:109`). Do not "fix" signing or commit keystores.
- Never commit `fabscreen/google-services.json` (not in `.gitignore`!), `local.properties` or build outputs.
- Do not regenerate or edit the committed binaries (`res/raw/fabscreen_updating_1_3.apk`, `res/raw/*.gcode`, GIFs).
- Do not change protocol opcodes, event IDs, HTTP routes or response keys without changing the controller and every
  client in the same change; Luban/Orca/Cura depend on them.
- Do not switch the Aliyun repositories in `build.gradle` in a commit (fine locally if downloads are slow).
