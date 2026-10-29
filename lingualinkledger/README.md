Lingua Link Ledger (PET App v1)

To install it on a phone, read docs\PET_App_v1_Install_Guide.txt (in the
PET repository, one level above this folder). This README is for working
on the code.


_________________________________________________________________
PURPOSE AND CAPABILITIES

An ATAK plugin for PET (Provable Evidence Tracing, Dynamic Sustainability
Lab, Syracuse University). It records seed-batch evidence on the phone, at
four stages: Collecting, Cleaning, Stored and Planting. Each event starts
with a photo of the batch's QR label, from which the plugin reads the
batch ID (ZXing core). It builds a package of the photos and a
metadata.json (stage, batch, the phone's GPS position by fixed rules, UTC
time) and keeps its SHA-256. Cleaning, and Combine batches at Stored,
merge several batches into a new one as one event; a stage table decides
what each batch may take next. Saved batches and Look up batch show what
the phone holds. Beside the capture flow are four tools: Create/Edit Data
Package, Create Evidence Trace, Add Stage Event and Send Data Package
(ATAK's own sending).

App v1 sends nothing to any server or gateway. Every record stays on the
phone, in atak/tools/lingualinkledger.

_________________________________________________________________
STATUS

App v1: a test build for the project team's feedback (October 2026).
Debug-signed, with simulated test labels; not for real nursery records.
The gateway, contract and TAK Server legs (Track B) come later. The plan,
decisions and findings are in docs\ (the two handoffs and the corrections
file).

_________________________________________________________________
POINT OF CONTACTS

Darrel Ramasray, ddramasr@syr.edu (DSL, Syracuse University).

_________________________________________________________________
PORTS REQUIRED

None. App v1 opens no network connection (D45). Send Data Package hands a
file to ATAK's own Send dialog, which uses whatever ATAK is set up for.

_________________________________________________________________
EQUIPMENT REQUIRED

An Android phone with Android 6.0 or newer, a camera and GPS, running the
SDK build of ATAK-CIV 5.8.0.5 (a306924) from the tak.gov developer kit.
The Play Store ATAK does not load self-signed plugins.

_________________________________________________________________
EQUIPMENT SUPPORTED

Tested on a Samsung Galaxy S21+ (Android 15), in landscape. Not yet tested
on a 4 GB phone such as the Galaxy A15.

_________________________________________________________________
COMPILATION

  - The ATAK-CIV 5.8.0.5 SDK unpacked at C:\atak-civ-sdk, and this folder
    exactly two levels below it (C:\atak-civ-sdk\PET\lingualinkledger):
    app\build.gradle finds the build tool at ..\..\atak-gradle-takdev.jar.
  - JDK 21 (gradle.properties names jbr-21.0.11 on the developer's PC;
    change org.gradle.java.home on another machine). Also set JAVA_HOME
    to it in the build shell.
  - local.properties (not in git): sdk.dir=<the Android SDK>.

      gradlew.bat assembleCivDebug        the plugin APK, in
                                          app\build\outputs\apk\civ\debug\
      gradlew.bat testCivDebugUnitTest    the unit tests (JUnit, on the PC)

  Every build first runs checkForbiddenHosts, which fails if any source
  names the retired gateway hosts (D19). Before every install:

      python -I tools\preinstall_scan.py <apk>     retired hosts, inside
                                                   the unpacked APK
      python -I tools\api_level_check.py <apk>     no Android API newer
                                                   than the APK's minSdk

  Releases for teammates come only from tools\make_release.py, which
  refuses an unclean tree, a failed build or test, a failed scan or API
  check, JUnit in the APK, or a guide that names a release; it stamps the
  install guide for the release. Each tool has a --self-test.

_________________________________________________________________
DEVELOPER NOTES

  Code: app\src\main\java\com\atakmap\android\lingualinkledger\plugin\
    LinguaLinkLedger   the plugin and its main screen
    RecordStage        the four stage buttons and the merges
    StageRules         the stage table (the scope's assumptions, in one
                       place for the nursery walkthrough)
    Ledger, LedgerStore, LedgerRebuild, EventPackage, AtomicFile
                       the records: ledger.json saved whole by rename;
                       each package self-describing, so a damaged ledger
                       is rebuilt from the packages
    LabelReader        the PET1 label format (provisional, scope Q3)
    PositionPolicy, AtakPosition
                       the GPS rules; never 0,0 or the map's centre
    ToolRecords, ToolFlows
                       the tools' records and screens
    BatchView, SavedBatches, LookUpBatch
                       what the phone holds
    BlockchainService  kept for Track B; its address is unset and nothing
                       calls it
  Plain-Java classes are tested on the PC (app\src\test); Android and ATAK
  code is checked on the phone. Dialogs are AlertDialogs against ATAK's
  activity with scrolling content (PetDialogs), and pickers are ATAK's
  PluginSpinner; a plugin-context Spinner crashed ATAK. The test labels
  are in docs\test-qr\.
