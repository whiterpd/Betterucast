# UCast Bridge — replacement Android companion app

A source-only Android Studio project intended to replace the old UCast Android companion while retaining the UCast 1.0.2 receiver protocol where it could be recovered from the uploaded APK.

## Current implementation

- UDP discovery to `255.255.255.255:17789`, with the phone's actual bound UDP source port embedded in the discovery message.
- Legacy UCast pairing request and legacy AES-256-CBC envelope format.
- Web URL command encoding (`utWebV0100`), including the six-byte XOR field and the original UTF-16 length semantics.
- Local video/audio transfer path using a phone-side HTTP server on TCP/8080 and the recovered local media markers (`utLocV0100`, `utLocA0100`). The local media packet layout is reconstructed from the native call sequence and **must be checked on the physical box**.
- HTTP `GET`, `HEAD`, single-byte-range handling (`206`), invalid-range response (`416`), complete-header buffering, and streaming without loading the full file into RAM.
- The file server binds only to the selected Wi-Fi IPv4 address, accepts requests only from the chosen receiver IP, and requires a per-transfer query token. The original server bound to a wildcard IPv4 address and did not perform this peer restriction.
- Android Storage Access Framework file picking; no broad shared-storage permission is requested.
- No automatic updater. The old APK used a hardcoded HTTP update endpoint; this replacement does not download or install remote APKs.

## Build

This deliverable is the complete Android Studio source project; it is not a prebuilt APK. The inspection environment did not contain the Android SDK, Gradle, `adb`, or Android build tools, so an APK could not be compiled or installed here.

Open this directory in Android Studio with Android SDK Platform 35 and JDK 17 installed, let Gradle sync, then use **Build → Build Bundle(s) / APK(s) → Build APK(s)**. Alternatively, with Gradle 8.9+ installed, run `./build-apk.sh`. The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

Project settings:

- Android Gradle Plugin: `8.7.3`
- `compileSdk`: 35
- `minSdk`: 23
- `targetSdk`: 28 (chosen for the initial compatibility build; review target policy before store publication)
- Java: 17
- Base64 codec: self-contained RFC 4648 implementation so `minSdk 23` does not depend on Android API 26's `java.util.Base64`

## First-use steps

1. Connect the Android phone and UCast box to the same local Wi-Fi network.
2. Open UCast Bridge and tap **Discover UCast boxes**.
3. Check the discovered IP and UDP port. The scan parser is deliberately conservative; if the device name is not decoded, enter the exact name shown by the original UCast UI or the receiver's device information screen.
4. Tap **Pair / connect to box**.
5. Send an HTTP/HTTPS web URL, or choose a local video/audio file and send it. Keep the app open while the receiver fetches a locally hosted file.

If discovery cannot parse a response, manually enter the receiver's IPv4 address, exact device name, and UDP port. Port 17789 is prefilled as a starting point because it is the original discovery destination; some firmware may advertise a different unicast control port.

## Important compatibility and safety notes

- The legacy AES seed values are embedded in the APK and have no modern authentication. They are reproduced only for compatibility. This is not secure encryption and must not be reused for any new protocol or sensitive content. This weakness cannot be corrected in a phone-only replacement without changing the receiver firmware.
- Do not expose the box or phone to an untrusted Wi-Fi network. The legacy box protocol itself has no modern authentication.
- The discovery-response parser is heuristic until a real receiver packet capture is available. The app reports raw replies and allows manual correction rather than silently treating guesses as facts.
- The app resets its UDP session when the phone's IPv4 address changes; discover and pair again after switching Wi-Fi networks.
- The local video/audio message structure is reconstructed but not yet confirmed by a live receiver. If local media fails but web URL playback works, capture one original-app local-video send to compare the exact packet fields.
- A receiver-side firmware image or packet capture is required to prove full compatibility, not merely internal packet generation.

## Tests included

Run the pure-Java smoke tests (requires a JDK 17 or compatible JDK):

```bash
./tools/run-smoke-tests.sh
```

They validate Base64 vectors and malformed padding, local encode/decode round-trips for discovery/pairing/web/local-media envelopes, host-JVM compilation of the pure-Java protocol/network code, and HTTP range/416, HEAD, fragmented-header, token-gate and paired-peer allowlist behavior. They do not contact a UCast receiver and are not a compatibility certification.

## Build from an Android phone using GitHub Actions

This project includes `.github/workflows/build-apk.yml`. From a phone, create a GitHub repository, upload the contents of this ZIP (extract it first so the project files are at the repository root), and push to the `main` branch. GitHub Actions will build the debug APK. In the repository, open **Actions → Build UCast Bridge APK → latest successful run → Artifacts → UCastBridge-debug-apk** and download the artifact ZIP. Extract it to obtain `app-debug.apk`. You can also start a build manually from the workflow's **Run workflow** button. Keep the repository private if you do not want the source public.
