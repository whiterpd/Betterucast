# Recovered UCast wire protocol notes

This document separates fields confirmed in the supplied native client from inferred/unfinished behavior.

## Network endpoints

| Purpose | Direction | Port / destination | Confidence |
|---|---|---|---|
| Discovery broadcast | Phone → LAN broadcast | UDP `255.255.255.255:17789` | Confirmed from `DeviceOperation::scanDevice()` |
| Phone UDP receive/source port | Phone listens and advertises | UDP `60000` in the original; replacement uses 60000 or a checked ephemeral fallback | Confirmed original / improved replacement |
| Pair/control | Phone → receiver | Receiver's advertised UDP port | Confirmed call path; exact device port depends on discovery reply |
| Local media host | Receiver → phone | TCP/HTTP `:8080` | Confirmed from native server; replacement preserves 8080 |

## Legacy encryption envelope

- Cipher: AES-256-CBC.
- Padding: zero bytes to a 16-byte boundary (not PKCS#7).
- Key seed: `S58B7BME75V9V549C2T6SB26412K2U2U` (32 ASCII bytes).
- IV seed: `U6XN2CFBNC3VCT74` (16 ASCII bytes).
- The native `transitionKey()` function derives three swap indexes from the first IPv4 octet of the phone address, mutates the key bytes, and increments the matching IV bytes.
- Base64 is encoded without line breaks, then translated as `+` → `!`, `/` → `)`, and `=` → `(`.
- The wire envelope begins with two literal ASCII bytes `ut`, followed by the translated Base64 ciphertext.

This is reverse-engineered compatibility data, not a recommendation. Static secrets and CBC without authentication are not secure.

## Discovery request

The decrypted cleartext body constructed by the original scan routine is:

```text
ut search ucastdev[<phone-udp-port>]
```

The encrypted envelope is sent by UDP broadcast to port 17789.

## Pair request

The pairing cleartext is:

```text
paring[<receiver-name>]::<phone-udp-port>ut<phone-wifi-ipv4>
```

The name is the receiver's reported device name, and the address is the phone's Wi-Fi IPv4 address. The phone targets the receiver IP and its discovered unicast UDP port.

## Web URL command — recovered layout

Before encryption the command starts with ASCII `utWebV0100` and contains:

| Field | Encoding |
|---|---|
| Marker | 10 ASCII bytes: `utWebV0100` |
| Filename character count | 1 byte, Qt `QString::size()` (UTF-16 code units) |
| Filename | UTF-8 bytes |
| URL character count | 2-byte big-endian count, Qt `QString::size()` (UTF-16 code units) |
| URL | UTF-8 bytes |
| Checksum field | 6 bytes inserted at byte offset 6: one XOR of the pre-checksum command bytes followed by five ASCII `0` bytes |

The device-send wrapper strips the first two command bytes (`ut`), encrypts the remaining command bytes, then prepends the outer `ut` envelope prefix.

## Local media HTTP URL

`TcpServer::getHttpRemoteUrl(..., true)` constructs a URL from:

```text
http://<phone-wifi-ip>:<configured-http-port>?<lowercase-sha1-of-current-unix-seconds>
```

The original configured server port is 8080. The file is held in the phone-side server object; the query token is not a cryptographic authentication mechanism. The replacement preserves the URL form and adds an IP allowlist.

## Local video/audio commands

Native code contains the markers:

- `utLocV0100` — local video path
- `utLocA0100` — local audio path
- `utLocM` — additional local media/list mode, not implemented here

The replacement reconstructs the local video/audio command in the same marker + filename + URL-length + URL pattern and applies the same six-byte XOR insert. The packet generation is internally tested, but this exact local-media layout still requires a capture or box test before being called fully verified.

## Discovery response caveat

The native code stores a device IP, name and UDP port extracted from a `QStringList`, but the exact splitting rules are not completely recoverable from the isolated parser routine. The replacement therefore reports raw decoded payloads and uses a best-effort parser; a human can correct IP/name/port before sending a pairing request.
