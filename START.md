# Building and connecting the app

The server has to be running first — see
[dterm/START.md](https://github.com/DavidJaramillo132/dterm/blob/main/START.md).

## Build

```sh
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

**With a USB cable**, if the phone has USB debugging enabled:

```sh
./gradlew installDebug
```

**Without one**, serve the APK over the network and fetch it from the phone's
browser:

```sh
cd app/build/outputs/apk/debug && python3 -m http.server 8000
```

Then open `http://<the machine's address>:8000/app-debug.apk` on the phone.
Android will ask for permission to install from the browser the first time.
Installing over an existing copy keeps its data, because both are signed with
the same debug key.

## Connect

| Field | What goes in it |
| --- | --- |
| Host | The machine's address. `tailscale ip -4` on the host prints the one that works from anywhere. |
| Port | Whatever the server was started with. 4242 unless you changed it. |
| Shared secret | The contents of `~/.dterm/secret` on the host, exactly. |
| Session | A name. Reusing it puts you back in the same shell. |

Tap **Show** to check the secret before sending it — a 64-character hex string
typed on a phone is otherwise unverifiable, and the only feedback for getting it
wrong arrives after a failed handshake.

**Keep the secret on this phone** is off by default. The host, the port and the
session are always remembered; the secret is the only one of them that opens a
shell, so keeping it on disk is a decision rather than a default.

Once an address has actually connected, it appears as a chip under the Host
field. The same machine usually answers at one address on the local network and
another through the tunnel, and switching between them is then one tap.

## Reading the screen

The rule under the top bar is the **lifeline**. The server pings a quiet client
every 30 seconds, so even an idle session produces traffic. The rule breathes
while that traffic is recent and fades as silence grows — it is the answer to
"is my session still there?".

The bar above it names the machine and the session you are attached to, and the
terminal size being reported to the shell.

**Detach** lives up there, away from where the thumb works, and asks for no
confirmation: detaching costs nothing. The shell and its jobs keep running on
the host, and attaching again to the same session name picks them back up.

## Keys a phone does not have

`ESC`, `TAB`, the arrows, `HOME`, `END` and the page keys are on the first row.
`^C`, `^D` and `^Z` sit on the second, deliberately away from the arrows.

`CTRL` is a latch rather than a held key: tap it, then tap a letter. While it is
armed it inverts completely, because sending a control code by accident is
otherwise silent.

## When it will not connect

The app names the cause rather than showing an exception:

| Message | What to do |
| --- | --- |
| Nothing is listening on that port | Start the server, or fix the port. |
| That address cannot be reached | Check the host, and that both devices are on the same network or tunnel. |
| The host never answered | A firewall is probably dropping the port. |
| The server turned down the secret | It must match `~/.dterm/secret` byte for byte. |
| The server speaks a different version | Rebuild the server from the same commit as this app. |

Two of them are not failures and are not drawn as one:

- **The link dropped** — your session is still running. Attach again.
- **Detached** — you asked for it. The session keeps running.

That distinction is the promise the whole project is built on: losing the
network is not losing the work.

## Tests

```sh
./gradlew test
```

Everything runs on the plain JVM. `LiveServerTest` starts the real C++ server
binary and completes a handshake against it, so a change that breaks the wire
format fails here rather than on the phone. It skips itself when the binary is
missing; point `DTERM_SERVER` at it if it is not at
`~/Projects/dterm/build/dterm`.
