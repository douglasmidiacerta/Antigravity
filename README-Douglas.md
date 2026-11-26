# Antigravity Douglas

This distribution installs alongside the original Antigravity application.

- Application ID: `id.vern.agydouglas`
- Launcher name: **Antigravity Douglas**
- Local Android gateway: `127.0.0.1:45261`
- Browser login callback: `127.0.0.1:18085`
- Return-to-app scheme: `antigravity-douglas://open`

It includes upstream changes through `5fb3252` and the live execution notifications.
The application ID stays short enough for the bundled runtime's fixed-size DNS paths.
The original application keeps its own private files, accounts, and conversation
history. The Douglas installation performs its own initial setup and sign-in.
Existing projects in shared storage can be added by selecting their folders;
private data and conversation history are not migrated automatically.

## Updates

APK updates are checked in `douglasmidiacerta/Antigravity`. Publish signed assets
named `Antigravity-Douglas-<version>.apk` with normal version tags, such as
`v1.0.2`. Upstream APKs use a different distribution and must not be offered as
updates to this application.

Use the same permanent release keystore for every Douglas release. Its private
backup is kept separately from this public repository. Never commit keystores or
passwords.

## Build and sign

Run `./gradlew :app:assembleRelease` with JDK 17 and Android SDK 36. The existing
Gradle signing configuration accepts a private keystore path through
`-PstoreFile=/absolute/private/path/to/release.keystore`, the alias through
`-Palias=antigravity-douglas`, and passwords through the
`SIGNING_STORE_PASSWORD` and `SIGNING_KEY_PASSWORD` environment variables.
Alternatively, sign the aligned unsigned APK with Android SDK `apksigner` and
the same keystore. Verify the certificate fingerprint before distribution.
