# camwall

Always-on RTSP camera wall for Chromecast with Google TV. No root.

A single Activity: one `TextureView` + Media3 `RtspMediaSource` per camera, laid out in a
grid, `FLAG_KEEP_SCREEN_ON`, auto-reconnect with backoff, and a `BOOT_COMPLETED` receiver
so the device powers on straight into the tiles.

## Remote control

| Input | Action |
|-------|--------|
| D-pad | move the selection (green border) |
| OK twice | full screen the selected tile |
| BACK | leave full screen; in the grid, leave the app |
| OK long-press | actions: full screen, mute/unmute, open lock, move, exit |

The Chromecast remote has no MENU or colour buttons, so long-press OK carries the menu.

**Move** puts the tile in an amber border labelled `↔ moving`; the D-pad then swaps it with
its neighbour and OK or BACK confirms. The arrangement is stored in SharedPreferences by
camera *name*, deliberately not written back to `cameras.json` — pushing a new config must
not clobber the layout, and a file holding gate credentials should not be app-writable.
Renaming a camera drops it to the end of the grid.

BACK must be able to exit: the wall is started by `BootReceiver` and sits on top of the
Google TV launcher, so swallowing BACK strands anyone whose HOME button does not work.

## Language

The UI is Arabic. The strings live in `res/values/strings.xml` — the **default** folder,
not `values-ar/`, deliberately: the device locale is `en-GB`, and a `values-ar/` set would
only apply on an Arabic-locale device. Keeping Arabic as the fallback makes the app Arabic
regardless of device locale. Add `values-en/` if an English build is ever wanted.

`android:supportsRtl="true"` is set, so dialogs and labels lay out right-to-left. The grid
itself is unaffected — tile positions come from the saved layout, not text direction, so
switching language does not rearrange the wall.

Camera names are **not** translated: they come from `cameras.json` and are yours to set.
Rename them there in Arabic if you want the tile labels to match the rest of the UI.

## Virtual remote

`tools/tvremote.py` drives the device over `_androidtvremote2._tcp` (port 6466) — the same
protocol the Google TV phone remote uses. Useful when a physical button dies, and to reach
Settings when ADB is off (which it will be after every reboot).

    ./.venv/bin/python tools/tvremote.py pair        # once; type the code shown on the TV
    ./.venv/bin/python tools/tvremote.py key HOME
    ./.venv/bin/python tools/tvremote.py key BACK DPAD_DOWN DPAD_CENTER
    ./.venv/bin/python tools/tvremote.py app camwall://open   # relaunch the wall
    ./.venv/bin/python tools/tvremote.py current              # what is in the foreground

Certificates are written next to the script and survive reboots, so unlike ADB this is a
one-time setup. Install with `python -m venv .venv && .venv/bin/pip install androidtvremote2`.

## Why TextureView and not SurfaceView

`SurfaceView` requests a hardware overlay plane. With four video planes live, this SoC's
compositor mis-assigns buffers — two tiles render the *same* camera while another
disappears. It is a compositor bug, not an app one: the same frame captured with overlays
forced off (`service call SurfaceFlinger 1008 i32 1`) was always correct.

`TextureView` composites on the GPU and sidesteps the plane allocation entirely. At
2x720p + 2x1408x528 the GPU cost is not measurable on an S905D3. A side benefit is that
plain `adb shell screencap` now shows exactly what the TV shows.

## Layout

    app/src/main/java/net/khaledez/camwall/
      MainActivity.kt    grid, players, reconnect
      CameraConfig.kt    reads cameras.json, falls back to built-in defaults
      BootReceiver.kt    BOOT_COMPLETED
    tools/
      probe-rtsp.py      find substream paths, report codec + resolution
      deploy.sh          build, install, configure over ADB

## Setup

One-time host toolchain. **Build on JDK 17 or 21** — AGP is not validated on newer JDKs,
and Gradle 8.9 does not run on Java 23+. If your default `java` is newer, either set
`JAVA_HOME` or let `deploy.sh` find a supported one:

    sudo pacman -S jdk21-openjdk        # system default may stay newer
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
    ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
        "platform-tools" "platforms;android-34" "build-tools;34.0.0"

On the device: Settings -> System -> About -> tap *Android TV OS build* 7x,
then System -> Developer options -> **ADB debugging** on.

## Cameras

Find the substream paths and confirm the codec:

    ./tools/probe-rtsp.py --user admin --pass 'secret' \
        192.168.88.200 192.168.88.202 192.168.88.10 192.168.88.15

It prints a ready-made `cameras.json`. Entries take two optional fields:

    {
      "name": "Gate 200",
      "url":  "rtsp://user:pass@192.168.88.200:554/cam/realmonitor?channel=1&subtype=0",
      "audio": false,
      "unlock": {
        "url":  "http://192.168.88.200/cgi-bin/accessControl.cgi?action=openDoor&channel=1&UserID=101&Type=Remote",
        "user": "admin", "pass": "..."
      }
    }

`audio` defaults to false — four live audio streams at once is not useful; unmute per
tile from the menu instead (unmuted tiles show a musical note by their name). `unlock`
adds an **Open lock** menu entry, behind a confirmation because a door release is
one-way. Any vendor's relay URL works.

Digest auth is implemented in `Unlocker.kt` rather than delegated: Android's
`HttpURLConnection` is backed by OkHttp, **which dropped Digest support**, so
`java.net.Authenticator` only ever answers Basic — and these VTOs reject Basic with a
flat 401, so the platform path throws `IOException` before authenticating. The header form
follows github.com/khaledez/camonitor `digest.go`: parameter order mirroring
`curl --digest`, `qop`/`nc` unquoted, and **no `algorithm` parameter**. A camera given the
wrong form answers `401 Invalid Authority!`, which reads like a permissions problem but is
an auth-response mismatch.

Cleartext HTTP also has to be permitted explicitly. Android 9+ refuses it for apps
targeting SDK 28+, failing the request before a socket opens:

    IOException: Cleartext HTTP traffic to 192.168.88.200 not permitted

`res/xml/network_security_config.xml` allows it, referenced from the manifest. The door
stations do listen on 443, but with self-signed certificates needing their own trust
anchors, and the unlock hosts come from `cameras.json` so they cannot be pinned ahead of
time.

**Test auth changes on the device, not from a workstation.** Both failures above —
OkHttp's missing Digest support and the cleartext policy — are Android-side, and a
`curl`/Python check against the camera passes cleanly while the app still cannot make
the call.

The two Dahua units here are DHI-VTO3311Q-WP door stations, which answer
`accessControl.cgi`; `?action=getDoorStatus&channel=1` reports lock state without
triggering anything. Save it at the repo root — it is gitignored,
because the RTSP URLs carry credentials. It is pushed to the device's external files
dir, so credentials never sit inside the APK.

Two constraints worth respecting:

- **Use substreams (~640x360), not main streams.** The S905D3 has a small, finite number
  of hardware decoder instances; four main streams will exhaust them.
- **H.265 is fine.** `media3-exoplayer-rtsp` 1.4.1 ships `RtpH265Reader`, so HEVC
  depayloads correctly — verified by inspecting the AAR, not assumed. The Tiandy
  cameras here are HEVC-only and are expected to work.

RTP-over-TCP is forced in `MainActivity.kt` (`setForceUseRtpTcp(true)`), which is what
most cameras that refuse UDP need.

## Cameras on this network

| IP | Model | Main | Substream | Using |
|----|-------|------|-----------|-------|
| .200 | Dahua-family | h264 1280x720 | h264 352x288 (`subtype=1`) | main — sub is CIF, too soft |
| .202 | Dahua-family | h264 1280x720 | h264 352x288 (`subtype=1`) | main |
| .10 | Tiandy TC-C382V | hevc 4640x1728 (`/1/1`) | hevc 1408x528 (`/1/2`) | sub |
| .15 | Tiandy TC-C382V | hevc 4640x1728 (`/1/1`) | hevc 1408x528 (`/1/2`) | sub |

Tiandy's RTSP path is `/<channel>/<stream>`; it answers *every* other path with the main
stream, so path brute-forcing looks like it succeeds everywhere. Its ISAPI is partial —
`/ISAPI/System/deviceInfo` works, `/ISAPI/Streaming/channels` returns `notSupport`.

Total decoder load: 2x h264 720p + 2x hevc 1408x528, comfortably inside the S905D3.

## Deploy

    ./tools/deploy.sh                    # discover device, build, install, push config
    ./tools/deploy.sh <dev> --autostart  # also start the wall on boot
    ./tools/deploy.sh <dev> --restore    # undo autostart

## Getting back into the app after exiting

`BACK` in the grid and the **إغلاق التطبيق** menu entry both leave the app. Four ways back,
none of which need a reinstall:

1. **Google TV's Apps row** — the app registers `LEANBACK_LAUNCHER`, so it is a proper
   launcher entry, but Google TV is known to hide sideloaded apps from its rows.
2. **Settings → Apps → See all apps → open it.** Always works, remote only.
3. **`tvremote.py app camwall://open`** — needs no ADB.
4. **Reboot** — `BootReceiver` brings it up.

Route 3 is why the manifest carries a `camwall://` scheme. `send_launch_app_command`
accepts a bare package name, and an `intent:` URI, and silently does nothing with either;
it needs a deep link the app actually registers.

## Starting on boot — do not take over HOME

The obvious approach is a `CATEGORY_HOME` intent filter plus
`pm disable-user com.google.android.apps.tv.launcherx`. **It does not work on this
device and it is dangerous.** The app registers correctly as a HOME candidate, but:

- `cmd package set-home-activity` reports `Success` and changes nothing; Google TV routes
  the Home key to its own launcher regardless.
- Disabling `launcherx` does not promote the next HOME candidate. Google TV drops into
  `com.google.android.tungsten.setupwraith/.RecoveryActivity` instead.
- **Wireless debugging does not survive a reboot**, even with `adb_wifi_enabled=1`
  persisted. So a device left with no launcher and no ADB needs a factory reset.

Use `BOOT_COMPLETED` instead. Google TV stays installed, enabled and reachable, and there
is no state the device cannot get out of with the remote.

The catch is that Android 10+ blocks activity starts from a receiver:

    W ActivityTaskManager: Background activity launch blocked
      [callingPackage: net.khaledez.camwall; callingUidProcState: RECEIVER; ...]
    I ActivityTaskManager: START ... (BAL_BLOCK) result code=102

`SYSTEM_ALERT_WINDOW` is the exemption — the app never draws an overlay, it only needs the
appop for this. **Flush it to disk**; `appops` writes are debounced, so setting the appop
and rebooting straight away silently loses the grant and the start is blocked again with
no indication why:

    adb shell appops set --user 0 net.khaledez.camwall SYSTEM_ALERT_WINDOW allow
    adb shell appops write-settings

`--autostart` does both. Verify with `appops get`: it must read `allow`, not `default`.

## Verifying playback

With `TextureView`, `adb shell screencap` shows the real output and one HWC layer (the
app window) is expected. If you ever switch back to `SurfaceView`, note that screencap
cannot read overlay planes — tiles come out black while being fine on the TV — and you
would have to check `dumpsys SurfaceFlinger | sed -n '/HWC layers/,/^$/p'` instead, or
force GPU composition with `service call SurfaceFlinger 1008 i32 1` (undo with `i32 0`).

## Logs

    adb -s <dev> logcat -s CamWall:V ExoPlayerImpl:V

`MainActivity` hides a tile's label only on `STATE_READY`, so a tile showing no label is
connected. A label reading `Cam N - <ERROR_CODE>` means that stream is in backoff.

## Connecting

USB debugging alone does **not** open a network ADB port on Android 11+. Use
**Wireless debugging**, which uses a random port and requires pairing:

    adb pair 192.168.88.172:<pair-port> <6-digit-code>     # both shown on the TV
    adb connect 192.168.88.172:<connect-port>              # from avahi-browse -rtp _adb-tls-connect._tcp

The connect port changes on reboot; rediscover it via mDNS rather than hardcoding.
