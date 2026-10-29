# Consolidation record — lingualinkledger

Prepared 2026-09-30. Read this before changing anything.

## Where this code came from

Three archives were supplied. Two were byte-identical, so there were only two
distinct branches of `nickmiceli7/lingualinkledger`:

| Commit    | What it is                         | Chosen? |
|-----------|------------------------------------|---------|
| `f4f2af8` | main — original plugin, no network | No      |
| `c10988a` | feature — adds blockchain client   | **Yes** |

This folder is **commit `c10988a`**. It contains everything meaningful in
`main` plus the blockchain integration:

- `BlockchainService.java` (351 lines, OkHttp) — talks to the gateway
- `health_check_dialog.xml`, `sign_record_dialog.xml`, `blockchain_loading_dialog.xml`
- `LinguaLinkLedger.java` grows 525 → 757 lines, adding Health Check,
  Create Evidence Trace and Sign Evidence Trace buttons

The only thing `main` had that this branch lacks is a Gradle wrapper bump to
8.9. That was deliberately NOT carried over: Gradle 8.9 is not a supported
pairing with the Android Gradle Plugin 7.4.2 this build uses, whereas this
branch's Gradle 7.6.1 is. Both are replaced by the ATAK 5.8 migration anyway.

## What was changed (complete list)

1. **Removed `.gitlab-ci.yml`** — finding OPS-01. TAK's internal GitLab
   pipeline: needs TAK's private registry and Artifactory credentials, pins a
   JDK 11 image. Cannot run for this project. Implied CI existed; none did.
2. **Appended to `.gitignore`** — finding PL-01. Now blocks `*.keystore`,
   `*.p12`, `*.pem`, `*.key`, `.env`, and migration backups. Previously only
   `*.jks`, while the setup guide generates `.keystore` files in this folder.
3. **Added this file.**

## What was deliberately NOT changed

Everything else is byte-identical to commit `c10988a`. In particular:

- **Build configuration** still targets ATAK 5.4.0 / AGP 7.4.2 / Gradle 7.6.1.
  It will not build against any SDK you can currently download. The 5.8
  migration must be done against the real 5.8 SDK template — never guessed.
- **All Java source and layouts.** Known defects are tracked as findings and
  should be fixed in reviewed commits, not silently in a cleanup, so the git
  history shows exactly what changed from the original.
- `PluginNativeLoader.java`, `ExampleTest.java`, the `gov` flavour strings and
  the unfilled `README.md` are all flagged for later decisions.

See the handoff document for the full findings register.
