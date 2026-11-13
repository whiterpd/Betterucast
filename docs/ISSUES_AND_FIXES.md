# UCast 1.0.2 APK — issue audit and replacement fixes

## Scope and confidence

The audit is based on static inspection of the supplied `UCast.apk`, including its ARM64 native library `libboxControlApp.so`, preserved C++ symbols, and disassembly of the device-control, crypto, media-send, HTTP-server and URL-building methods. The replacement source project is `UCast Bridge`.

No physical receiver, receiver firmware, original phone, or packet capture was provided. Findings are labeled as confirmed from code, or as compatibility risks requiring runtime validation.

## Findings

| Severity | Finding in original app | Evidence / consequence | Replacement action |
|---|---|---|---|
| High | Legacy AES protocol is not cryptographically secure | The APK contains fixed 32-byte AES key seed and 16-byte IV seed, then modifies them using a few digits of the phone IPv4 address. It uses AES-256-CBC with zero padding and no authenticated-encryption tag. A party able to observe/modify traffic can tamper with packets; there is no cryptographic replay protection in this layer. | Reproduces the exact legacy transform only for interoperability. Documentation explicitly warns that it is not security. New local HTTP serving adds peer-IP restriction and a short-lived transfer token, but does not pretend to upgrade the box's UDP trust model. |
| High | File server exposed on wildcard IPv4 | `TcpServer::listenPort()` constructs a `QHostAddress` from special-address enum value 4 and calls `QTcpServer::listen()` on it (wildcard IPv4). Requests are not restricted to the selected receiver IP. The server also emits `Access-Control-Allow-Origin: *`. | Binds only to the current Wi-Fi IPv4 interface, rejects all peers except the selected box IP, checks a per-transfer token, removes wildcard CORS, and serves only the one selected file. |
| High | Updater uses cleartext HTTP | Hardcoded update/config URL is `http://www.satxiptv.com/ucast/config.txt`; related version/APK paths are referenced in the native updater. Cleartext transport permits interception or tampering in transit. | Removes automatic remote APK downloading/updating. Any future updater should use HTTPS and verify a publisher signature/hash before offering installation. |
| High | Missing matching Wi-Fi interface can break legacy key derivation | The device-side IPv4 lookup filters for a `wlan*` interface. If that lookup returns an empty address, downstream conversion/array-index use is not safely guarded. Devices with renamed interfaces, VPN routing, Ethernet, hotspot behavior or no active WLAN can fail or reach invalid indexes. | Reads the active Wi-Fi `LinkProperties` first, then safely falls back to viable IPv4 interfaces. Rejects missing/non-IPv4 addresses before deriving the legacy key and checks all key indexes. |
| Medium | UDP source port is fixed and bind result is not handled robustly | The original device object binds local UDP port 60000, while discovery advertises that port. If the bind fails because the port is occupied or restricted, the box may reply to a port where the app is not listening. | Attempts 60000 first, checks the result, then binds an ephemeral UDP port and advertises the actual port in discovery and pairing. A single socket is kept alive for the session. |
| Medium | Interface selection is brittle | Native key setup is coupled to a `wlan`-named interface rather than the actual active Wi-Fi network. A device with multiple interfaces can select the wrong source address. | Uses Android's active network transport and link properties, preferring Wi-Fi IPv4. |
| Medium | HTTP request parsing / range support is fragile | The original is a hand-written HTTP server. It supports `206 Partial Content` and byte ranges, but this implementation does not have the validation and bounded incremental header reader provided here. Fragmented TCP headers and malformed range values are important failure cases for a home-grown server. | Reads headers incrementally with a 16 KiB cap, supports one valid byte range and suffix range, clamps the end offset, rejects invalid/multiple ranges with `416`, streams from disk, and handles `HEAD`. |
| Medium | Discovery response decoding is coupled to a parsed string-list layout | `readScanDeviceInfo(QStringList const&)` pulls device fields from a fixed set of list positions and stores an IP/name/port tuple. The exact delimiter/field order is not fully established from static inspection alone. | Uses sender IPv4 as a dependable locator and extracts name/port heuristically. Presents raw decoded response data and allows manual IP/name/port correction instead of silently inventing a device identity. |
| Medium | No modern UDP message authentication or replay counter | The protocol uses CBC encryption without a MAC/AEAD tag, and the observed device messages do not include a verified nonce/counter scheme. | Cannot be fixed on the phone alone without changing receiver firmware. The replacement keeps legacy packets compatible and documents the limitation rather than adding fields the box would reject. |
| Low | Old native framework/toolchain and narrow ABI packaging | The uploaded APK includes an ARM64 native library and Qt/C++ runtime components; it is not a clean Java-only Android app. This can reduce device coverage and make maintenance dependent on old Qt/native build tooling. | New code is plain Java and Android APIs, with no embedded Qt runtime; it targets Android API 23+ and is easier to rebuild. |
| Low | Web URL support is tied to native parser and embedded site logic | `PlayWebUrlParse` has its own URL-processing path and an embedded site list. Sites change their page and playback markup, so extraction logic can become stale independently of the box protocol. | Sends user-supplied HTTP/HTTPS URLs using the exact recovered `utWebV0100` binary layout. It does not promise to extract protected video streams from webpages. |

## Replacement implementation safeguards

- The replacement uses a self-contained RFC 4648 Base64 codec because `java.util.Base64` is only available on Android API 26+, while this project supports API 23+.
- File sending now validates the selected media type and active pairing before binding the HTTP server. The HTTP host uses the IP of the actual paired receiver rather than a possibly edited text field.
- When the phone's IPv4 address changes, the replacement resets its UDP session and requires discovery/pairing again so the IP-derived legacy AES material cannot silently remain stale.
- The file host closes itself if the UDP send fails, and its request parser is tested against fragmented headers, invalid ranges, missing tokens and a non-paired peer IP.

## Important non-findings

- The AES routines are not “broken” in the sense of a failed AES implementation; the issue is the weak/static key establishment and lack of message authentication.
- The APK demonstrably supports HTTP range responses. The replacement improves validation; this audit does not claim every old 206 transfer was broken.
- The APK's self-signed certificate identifies a signing label/organization in its certificate fields, not a verified legal developer or company. Do not treat the certificate as proof of personal identity.

## What remains unverified

1. Exact delimiter and field ordering in every firmware revision's UDP discovery reply.
2. Whether all receiver models accept the recovered local video/audio packet layout and URL format.
3. Whether the box sends a connection acknowledgement on every firmware revision, and the exact success response body.
4. Whether the receiver requests the local media URL with `GET`, `HEAD`, or a specific range pattern beyond the protocol behavior inferred from the old phone server.
5. Whether the receiver uses a different pairing name or control port on some hardware variants.

A short PCAP captured while the original app performs one scan, one pair, one web-URL send and one local-video send would resolve most of these points.
