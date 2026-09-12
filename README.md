# DTerm Mobile

DTerm is one project in two repositories.  
This repo is the Android client; the server lives in  
[DTerm Server](https://github.com/DavidJaramillo132/dterm).

An Android terminal client that connects to a custom C++ remote shell server over TCP, using the same hand-designed binary protocol. Built to complete the other half of DTerm without hiding the hard parts.

## Status

| Version | What works |
| --- | --- |
| v0.1 | Frame codec, HMAC-SHA256 handshake, session attach, live output, line input |
| v0.2 | Terminal emulation, colour, the real window size, per-keystroke input |

## Requirements

- Android Studio, or just the Android SDK plus a JDK
- A running DTerm server and the shared secret it uses
- `minSdk` 24 (Android 7.0)

## Build

For building, installing and connecting step by step, see [START.md](START.md).

```sh
./gradlew assembleDebug     # APK
./gradlew installDebug      # build and install on the connected device
./gradlew test              # unit tests, no device needed
```

## Running it against a server

With the phone and the host on the same network, nothing but the port has to be open.

```sh
# On the host, if there is no secret yet:
mkdir -p ~/.dterm && openssl rand -hex 32 > ~/.dterm/secret && chmod 600 ~/.dterm/secret

sudo firewall-cmd --add-port=4242/tcp    # Fedora; adjust for your firewall
dterm 4242
```

In the app, enter the host's LAN address (`ip -4 -br addr` will show it), the port, and the secret from `~/.dterm/secret`.

If the connection times out with no error from the firewall, check whether the host has two interfaces on the same subnet. The reply then leaves by a different interface than the request arrived on, and something in between will usually drop it. Connecting to the other interface's address is the quick way to tell.

Over a USB cable instead, `adb reverse tcp:4242 tcp:4242` tunnels the port through it and the app connects to its own `127.0.0.1`, with no firewall involved. That needs USB debugging enabled on the phone.

## Security

The transport is **not encrypted**. The shared secret never crosses the wire — the server sends a random challenge and the client answers `HMAC-SHA256(secret, challenge)` — but everything after the handshake, including keystrokes and output, travels in the clear. Use it on a network you trust, or tunnel it through Tailscale or WireGuard.

## Wire protocol

Length-prefixed binary frames, identical to `protocol/protocol.hpp` in the server repository. Protocol version 2.

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

The handshake is strictly ordered: HELLO, CHALLENGE, AUTH, AUTH_OK, ATTACH. Anything else in between drops the connection, and the server allows five seconds for the whole exchange.

Nothing enforces that this file and the C++ header agree. `LiveServerTest` is what catches a divergence — it runs the real server binary and talks to it.

## Layout

```
app/src/main/java/com/example/dterm/
├── net/Protocol.kt          frame types, reader, writer
├── net/Auth.kt              HMAC-SHA256 over the challenge
├── net/Emulator.kt          the cell grid, cursor and escape sequences
├── net/Keys.kt              what each key sends down the wire
├── net/DtermConnection.kt   socket, handshake, read loop
├── TerminalViewModel.kt     connection state, survives rotation
└── MainActivity.kt          connection form and terminal view
```

## How the screen works

`Emulator` keeps a grid of cells and a cursor, not a string of text. That distinction is the whole reason full-screen programs work: they do not print forwards, they say "put the cursor at row 3 column 1, erase to the end of the line, write this", dozens of times a second. Those instructions only mean something against a grid that can be overwritten in place.

Implemented: cursor movement, erase and insert and delete, a scrolling region, the alternate screen, auto-wrap deferred at the margin, and colour up to direct 24-bit values. Not implemented: mouse reporting, character sets, double-width characters, and the status-report queries that expect a reply.

The view repaints on a timer rather than on every frame that arrives, because a busy program can emit dozens a second and each one would otherwise rebuild the whole screen on the UI thread.

## Testing

```sh
./gradlew test
```

Everything runs on the plain JVM — no device, no emulator. `LiveServerTest` starts the real C++ server and completes a handshake against it; it skips itself when the binary is missing, so set `DTERM_SERVER` if it is not at `~/Projects/dterm/build/dterm`.

Watch for tests that pass without proving anything. The suite checks its own inputs where it can: `AuthTest` compares against a MAC produced independently by `openssl dgst -sha256 -mac HMAC`, and `EmulatorTest` asserts on the sequences a full-screen program really sends, including that a repaint replaces the previous frame rather than stacking below it.

A green suite is worth what its worst test is worth. Breaking a behaviour on purpose and watching the right test go red is the only way to know it bites.

## Limitations

- **No mouse.** Programs that offer click targets will not see them.
- **Wide characters count as one cell.** CJK text and some emoji will sit a column out of place.
- **No status-report replies.** A program asking the terminal to identify itself, or to say where the cursor is, gets no answer.
- **Ctrl is a latch, not a held key**, because a soft keyboard has no modifier to hold. Tap CTRL, then tap a letter.
- **Backspace relies on padding.** A soft keyboard reports nothing when Backspace is pressed on an empty field, so the input keeps invisible characters for it to delete. A keyboard that ignores this will not send it.
