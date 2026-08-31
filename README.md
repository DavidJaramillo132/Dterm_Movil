# DTerm Mobile

Android client for [DTerm](https://github.com/DavidJaramillo132/dterm), a remote
Linux terminal server written in C++. The server owns the PTY and keeps shells
alive across disconnects; this app attaches to one of those sessions over TCP.

Built as a learning project, not as a Termux replacement — see
[Limitations](#limitations).

## Status

| Version | What works |
| --- | --- |
| v0.1 | Frame codec, HMAC-SHA256 handshake, session attach, live output, line input |

## Requirements

- Android Studio, or just the Android SDK plus a JDK
- A running DTerm server and the shared secret it uses
- `minSdk` 24 (Android 7.0)

## Build

```sh
./gradlew assembleDebug     # APK
./gradlew installDebug      # build and install on the connected device
./gradlew test              # unit tests, no device needed
```

## Running it against a server over USB

`adb reverse` tunnels a port through the USB cable, so the phone reaches the
server on its own `127.0.0.1` without touching Wi-Fi or the host firewall.

```sh
# On the phone: Settings -> About -> tap "Build number" 7 times,
# then Developer options -> USB debugging.

adb devices                          # authorise the prompt on the phone
adb reverse tcp:4242 tcp:4242        # phone's localhost:4242 -> host's 4242

# On the host, if there is no secret yet:
mkdir -p ~/.dterm && openssl rand -hex 32 > ~/.dterm/secret && chmod 600 ~/.dterm/secret

dterm 4242                           # start the server
```

In the app, leave the host as `127.0.0.1`, paste the secret from
`~/.dterm/secret`, and connect.

Over Wi-Fi instead, use the host's LAN address and open the port
(`sudo firewall-cmd --add-port=4242/tcp` on Fedora).

## Security

The transport is **not encrypted**. The shared secret never crosses the wire —
the server sends a random challenge and the client answers
`HMAC-SHA256(secret, challenge)` — but everything after the handshake, including
keystrokes and output, travels in the clear. Use it on a network you trust, or
tunnel it through Tailscale or WireGuard.

## Wire protocol

Length-prefixed binary frames, identical to `protocol/protocol.hpp` in the
server repository. Protocol version 2.

```
 0        1                                5
 +--------+--------+--------+--------+--------+---------------+
 |  type  |          length (uint32 BE)       |    payload    |
 +--------+--------+--------+--------+--------+---------------+
```

| Type | Code | Direction | Payload |
| --- | --- | --- | --- |
| HELLO | 0x00 | client → server | `"DTRM"` + version byte |
| INPUT | 0x01 | client → server | keystrokes |
| OUTPUT | 0x02 | server → client | PTY output |
| RESIZE | 0x03 | client → server | rows, cols — two uint16 BE |
| PING | 0x04 | server → client | empty |
| PONG | 0x05 | client → server | empty |
| CHALLENGE | 0x06 | server → client | 32 random bytes |
| AUTH | 0x07 | client → server | HMAC-SHA256(secret, challenge) |
| AUTH_OK | 0x08 | server → client | empty |
| AUTH_FAIL | 0x09 | server → client | empty |
| ATTACH | 0x0A | client → server | session name, empty means `default` |

The handshake is strictly ordered: HELLO, CHALLENGE, AUTH, AUTH_OK, ATTACH.
Anything else in between drops the connection, and the server allows five
seconds for the whole exchange.

Nothing enforces that this file and the C++ header agree. `LiveServerTest` is
what catches a divergence — it runs the real server binary and talks to it.

## Layout

```
app/src/main/java/com/example/dterm/
├── net/Protocol.kt          frame types, reader, writer
├── net/Auth.kt              HMAC-SHA256 over the challenge
├── net/TerminalBuffer.kt    ANSI stripping and UTF-8 reassembly
├── net/DtermConnection.kt   socket, handshake, read loop
├── TerminalViewModel.kt     connection state, survives rotation
└── MainActivity.kt          connection form and terminal view
```

## Testing

```sh
./gradlew test
```

Everything runs on the plain JVM — no device, no emulator. `LiveServerTest`
starts the real C++ server and completes a handshake against it; it skips itself
when the binary is missing, so set `DTERM_SERVER` if it is not at
`~/Projects/dterm/build/dterm`.

Watch for tests that pass without proving anything. The suite checks its own
inputs where it can: `AuthTest` compares against a MAC produced independently by
`openssl dgst -sha256 -mac HMAC`, and `TerminalBufferTest` feeds in escape
sequences captured from a real `ls --color=always`.

## Limitations

This is not a terminal emulator. A real one keeps a grid of cells and a cursor
that can be moved anywhere, which is how `vim` and `htop` repaint the screen.
This client strips the control sequences and appends what is left, so:

- **Full-screen programs do not work.** `vim`, `htop`, `nano` and `less` will
  stack their repaints instead of replacing them.
- **No colours.** They are removed along with everything else in the sequence.
- **No control keys.** There is no way to send Ctrl+C yet.
- **Input is line-based.** A line is sent when you press Send, not per keystroke.
- **The terminal size is fixed** at 40×80 rather than measured from the screen.
