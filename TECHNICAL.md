# How the Rear Dashcam works

The details behind the [README](README.md), for the curious and for anyone debugging.

## The pieces

The package has three main pieces:

- **The recorder**, a background service with no screen. NEMESIS starts it at boot as `svc/recorder.sh`, which runs `dashcam.jar` under `app_process`. It starts as root, keeps the steering-wheel key device open, then switches to the system user (uid 1000) with group `sdcard_r`, so it can use the camera and write to the USB drive.
- **The Dashcam app** (`dev.skramer.dashcam.player`), for watching recordings and turning recording on and off. The app and the recorder only talk through files (see [Files](#files)).
- **Digital Rearview Mirror** (`dev.skramer.dashcam.mirror`), a separate live-only viewer. It receives completed frames from the recorder over a local socket; it has no playback, camera HAL or storage access. The player keeps its own parked-only driving guard.

Two more NEMESIS services support the recorder: `svc/watchdog.sh` kills it if it stops responding (see [Reverse](#reverse-and-the-camera-button)), and `svc/overlay.sh` starts the native display helper with the boot-time privileges needed to hide the camera layer (see [Projection](#projection-and-the-camera-display-layer)).

## Recording

- The recorder starts the backup camera the way the car's reverse view does: through the head unit's own rear camera service (`android.hardware.RearCamera`), with the reverse view's parameters and the recording size (720×480). It never gives the camera a preview surface: on this HAL that path can hang the camera until a reboot.
- It opens the camera the stock `RearCamera.open()` would: the first one whose info says it faces back. Only cameras 0 and 3 have the direct-display path that runs without a surface, so for anything else it uses 0. On the analog-camera IONIQ 5 that's camera 0.
- Started like that, the camera runs in "direct display" mode. The hardware deinterlaces the picture, scales it to the recording size and writes each frame, as BGRA, into a ring of four buffers in memory the kernel reserves for the camera (`rearcamera` in `/proc/pmap`). It also puts it on a display layer, which the home screen covers. The recorder reads those buffers through `/dev/admem`, mapped read-only, which doesn't touch the camera driver.
- Nothing signals a finished frame. A watcher thread looks at a strip near the top of each buffer every 2 ms (waiting 12 ms right after a buffer starts, since the next can't start for about a frame): when one changes, the hardware has started writing it, so the buffer before it is complete. That one is copied out while the next three are written (about 50 ms), and dropped if it starts being written again during the copy.
- The picture comes out as the reverse view shows it: mirrored, with the parking guide lines drawn in. The guide lines are switched off while recording (see [Parking guide lines](#parking-guide-lines)), and frames are mirrored back when they're converted.
- Copying frames out is the biggest cost: about 23 ms a frame, half a CPU core at 20 fps. The camera memory isn't System RAM, so the kernel maps it strongly-ordered (uncached, no burst reads) whatever we ask for; it's read with one `memcpy` per frame, which is as fast as that memory allows. At 720×480 and 20 fps the recorder uses a little over half of one core (of four).
- 720×480 is also the analog camera's own resolution: NTSC, two 720×240 fields (`vin_crop` 720×240, interlaced, in the kernel log), which the hardware deinterlaces. A bigger recording size is only upscaled, with no more detail. Tried at 1136×720 (the stock view's 1140 rounded down: the hardware encoder won't start unless the width is a multiple of 16), it took about 2.7 times the CPU and tore about 3% of frame copies.
- Every 30 seconds the log gets a line on how heavy recording is: frame rate, milliseconds per frame to copy out and to convert, frames lost, and CPU use from `/proc`: the recorder (and its busiest threads), mediaserver (where the camera service and the H.264 encoder run), the whole system, and the load average. The GPU isn't measured: the kernel has no load counters for it, and recording doesn't use it.
- Frame-detection strip hashing runs in native C in one JNI call for all four buffers, with the same 32-bit hashes and Java fallback. It reuses its buffers and preserves the existing capture cadence.
- Frames are kept down to the chosen frame rate on an even cadence. They're converted to I420 and mirrored left to right in the same pass, in C with NEON (`libdashcam.so`, about 1 ms a frame; the head unit's Dalvik VM took 12 ms in Java, which is still the fallback), straight into the encoder's input buffer, then passed to the head unit's hardware H.264 encoder (`OMX.TCC.ENC.avc`), with a keyframe every second.
- Encoded video is written as MPEG-TS files, one per minute by default, named by start time (`20261026_220609.ts`). A new file always starts on a keyframe. The file is synced to the drive every second, so a sudden power cut loses at most about a second.
- There's no audio; the camera has none.
- Before each new file, the oldest files in the `Dashcam` folder are deleted until the chosen share of the drive is free. Files in `Saved` and `Snapshots` are never touched.
- Normal recording waits until the drive is mounted at `/storage/usb0`, so it never fills the head unit's internal storage.

### Settings

Normal recording has one setting: on or off (tap the status at the top of the main screen). Recording quality is fixed, at what's been tested: 720×480 at 20 fps, 3 Mbit/s (about 1.35 GB an hour), one file a minute, and 5% of the drive kept free.

To experiment, the recorder takes arguments in `/data/nemesis/apps/dashcam/dashcam.conf` (`DASHCAM_ARGS="--fps=15"`). Updating the package replaces that file.

| Option | Default | |
|---|---|---|
| `--fps` | `20` | frames per second kept from the camera's 60; each costs a copy, so this sets the CPU load |
| `--bitrate` | `3000000` | H.264 bits per second |
| `--segment` | `60` | seconds per file |
| `--min-free-percent` | `5` | the oldest files are deleted to keep this share of the drive free |
| `--out` | `/storage/usb0/Dashcam` | where recordings go |
| `--mount` | `/storage/usb0` | recording waits until this is mounted |
| `--width`, `--height` | `720`, `480` | recording size; the camera hardware scales to it. The width must be a multiple of 16 |
| `--mirror` | `true` | flip left to right |
| `--hide-guide-lines` | `true` | turn the parking guide lines off while recording |
| `--save-button` | `auto:37` | input device and key code for the save button; `auto` is the head unit's key device, `daudio_` plus its model; empty turns it off |
| `--save-seconds` | `120` | how much the save button keeps |
| `--swap-uv` | `false` | for a camera with red and blue swapped |

## Parking guide lines

The camera draws the guide lines into its own frames, so they'd end up in every recording. The camera settings' "parking guidance" switch is the car's parking service (`com.hkmc.micom.pgs`): `IPgsService.requestGuideLineState()` sets it (1 off, 2 on) and `getRvmGuideLineState()` reads it.

- Before starting the camera, the recorder reads the setting. Only if the lines are on does it turn them off, and it leaves a marker file (`guidelines-hidden`). It also drops the first 0.3 s of frames, because the camera takes about two frames to stop drawing them.
- When it lets go of the camera, it turns them back on and removes the marker.
- If the recorder is killed with the lines off, the marker is still there. The next recorder at startup, the watchdog after a kill, and an uninstall or update all turn them back on.
- If you turned the lines off yourself, the recorder sees them off and never touches them.

## Only while the car is on

The head unit doesn't only run while you drive. It also starts up when you unlock the car, and every half hour or so overnight while the car checks itself. At those times the camera has no power.

- Four times a second, the recorder asks the car's body control service whether the ignition is on (`com.hkmc.micom.bcm`, `IBCMService.getIGNStatus()`). Accessory mode counts as off.
- At startup, an "off" reading counts at once, so the camera is never opened. While recording, the ignition has to read off for about a second before the recorder stops.
- Each change is logged together with the car's power status, boot mode and boot reason.

**Frames with no picture are skipped too.** For about 25 seconds after the head unit starts, the camera has no picture. If its buffers stop changing, nothing is recorded until they change again (and after a minute the camera is restarted). If they're flat, every pixel in a grid of 96 exactly the same color, the frame is skipped: real video, even pitch dark, always has some noise.

## Reverse and the camera button

Only one program can have the camera at a time, and while the dashcam has it, the car's backup view can't open.

- While it owns the camera, the recorder reads the gear every 50 ms (`RearCamera.getGearStatus()`, which also reports the camera button); idle and passive recording use a 250 ms interval. In reverse it lets go of the camera at once. It takes the camera back 3 seconds after the last reverse reading, and only once the car's camera app has closed it (checked through the rear camera service's status).
- If the car's camera app tries to open the camera before the dashcam has let go, it fails and tries again every half second, for up to 15 seconds.
- **Recording goes on through reverse.** The car's camera app runs the camera the same way, and its frames land in the same memory ring, at its own size (1140×720 on the analog-camera IONIQ 5). After letting go, the recorder samples that memory for a third of a second at a time until frames show up, and works out their size: the one for which every change has its counterparts in the other three frames of the ring, from a list of likely sizes. It then copies those frames out, reading only the rows it needs and scaling them to the recording size, into the same file. The guide lines are back on for you, so they're in this part. There's a gap of about a second while the camera changes hands, and another while the recorder takes it back; if no size fits, nothing is recorded until then. Each reverse ends with a log line: how long, the frame size, frames recorded.
- **The early camera.** While the head unit starts, a separate processor in the chip (a Cortex-M) shows the backup camera on its own, so reverse works before Android is up; that's the plain picture with no on-screen buttons. The car's camera app normally ends it about 10 seconds after Android starts, and after a wake from unlocking the door it may not have yet when you switch the car on. If the camera is opened while the early camera still runs, the display path is set up twice (the kernel logs `RDMA1 was recovered` every frame), and going into reverse then gets the head unit reset by the car. So before every camera open the recorder asks the kernel whether the Cortex-M is still loaded (ioctl 4 on `/dev/tcc_cm_ctrl`; `RearCamera.knockEarlyCamera()` always returns 0, so it can't be used) and, if it is, stops it the way the car's camera app does: clears `persist.sys.earlycam.handover`, `RearCamera.stopEarlyCamera()` (up to three tries), then `disableCMControl()`, and waits half a second. It also reads the gear right before opening and doesn't open in reverse.
- Letting go has 0.8 s to finish. If it doesn't, the recorder kills itself, which frees the camera too, and NEMESIS starts it again.
- The recorder updates a heartbeat file every second. If the heartbeat stops for about 6 seconds, the watchdog kills the recorder.

## Saving from the steering wheel

- The recorder reads the head unit's key device without grabbing it, so mute still works as usual. The device is named `daudio_` plus the head unit's model (`ro.product.device`): `daudio_wp_daudioplus_neev23_us`, `/dev/input/event1`, on the IONIQ 5. If no device has that name, the recorder uses the only `daudio_` device there is. It opens the device while still root, since the system user can't.
- Mute is key 37. Four presses, each within 0.7 s of the last, trigger a save. Quick presses on this car come about 0.2 s apart and ordinary ones over a second apart.
- On a save, the file being written ends at the next keyframe, under a second later, and recording carries on in a new file with nothing lost. Then every file last written to within the past two minutes moves to `Saved`. With 1-minute files that's 2 or 3 files, covering at least the two minutes before the press.
- The saved recording is named "Saved from steering wheel", and the head unit shows a pop-up through the car's own toast message.

## Saved recordings

- Saving moves files from `Dashcam` to `Dashcam/Saved`. It doesn't copy them, so saving is instant and uses no space.
- In the app, a saved recording is a run of saved files with no unsaved file between them and less than a minute between one's end and the next one's start. So a pause for reverse doesn't split one, and a save that touches an earlier one joins it. Unsaving and deleting always act on the whole run.
- **Save** in the player keeps the minute before and after what's on screen.
- Names are kept on the drive in `Dashcam/Saved/names.txt`, one line per recording: the name of its first file, a tab, then the name.

## Digital Rearview Mirror and long Mode

The own-car firmware already classifies steering-wheel Mode (`deviceId=5`, key code 254) as a short or long press. `PushKeyFromMicom.getPressType` treats a down event carrying flag `0x80` as long. `ModeKeyDispatcher.sendBroadcastKeyEvent` cycles media only on a short release; after a long press its release does not cycle media. The long event requests `com.hkmc.intent.action.MODE_SHORTCUT_LONG`, which normally opens Button Settings. There is therefore no need to delay or synthesize short presses.

`MirrorKey` registers a read-only `IModeKeyListener` (transaction 76 on `com.hkmc.micom.mode`) for mode 0, and handles `onAllKeyForService` (callback 3). Mode 0 is a mode registration, not a key-code filter or exclusive input priority. The listener filters steering-wheel Mode itself, deduplicates hardware long repeats, and posts launches outside the OEM Binder callback. It refuses launches during stock camera requests, with ignition off, or while another OEM key owner has an exclusive priority. It never grabs `/dev/input`, calls `disableAllKeysExcept`, changes the media mode, or alters framework firmware.

While this listener's heartbeat is fresh, the existing root watchdog invokes `scripts/mirror-filter.sh` as uid 1000 (system) to atomically install `mirror-mode.xml` as `/data/system/ifw/dashcam-mirror-mode.xml`. System owns the firewall directory (0700); NEMESIS root has no DAC override. Android's IntentFirewall blocks only the default Mode-long broadcast. The listener sees hardware events independently of broadcasts, so it can open the mirror without racing Button Settings. A one-second reload delay precedes the acknowledgement used by the listener. The watchdog removes this one rule if the listener or recorder disappears; startup, crash cleanup and package preuninstall also remove it. Other firewall rules are untouched. Uninstall restores the stock long-Mode action, with no factory APK, ODEX or register patch to restore.

The live socket `dev.skramer.dashcam.mirror` accepts only the installed mirror application's UID. Each pull renews a 1.5-second lease. Responses contain a magic/version value, dimensions, the capture uptime, mirror flag, and little-endian BGRA pixels. Dimensions −1×0 indicate a request to close the viewer. The server keeps only the newest completed frame and caps clients at 20 fps. It publishes an existing cached recorder frame, with no second read of uncached camera RAM or extra encoder. The viewer uses OpenGL ES 2 for color-channel conversion, aspect fitting and mirroring; both apps compile the same small `RamView` source, without sharing activities or playback dependencies.

When recording is already running, viewing leaves that session running. Opening or closing the viewer does not reopen an existing camera session. Its window transitions are disabled; a new shared camera session starts with its overlay horizontally centered. Without a USB drive, or with recording disabled, the guard can run a preview-only session at the configured source resolution, without creating an encoder. Holding Mode again sends a close command through the live socket, without relaunching the activity. Tapping anywhere, pressing Back, or backgrounding the viewer closes its socket; an expired lease ends a preview-only session. Ignition and stock reverse/parking-view handoff still take priority. The server withholds frames during handoff, and both server and viewer reject frames older than 750 ms, so a frozen picture is replaced with an explicit waiting/interrupted message.

Emulator verification is documented in [tools/mirror-tests](tools/mirror-tests/README.md). It covers captured OEM short/long dispatch, real Android broadcast filtering and removal, actual Binder callbacks into the production listener, and the production socket/viewer with synthetic 1140×720 frames. Physical button timing, live camera load and stock-camera transitions still need a parked-car check; the emulator has no Hyundai camera HAL or display controller.

Native dialog checks use the [OEM alert theme port](tools/emulator-theme/README.md). On this firmware `AlertDialog.resolveDialogTheme` ignores the requested theme and always selects the legacy alert theme, including for Holo Dark requests. The derived SDK image reproduces that selection, the light wide-screen alert assets, and the Mobis font family while retaining the SDK resource IDs and hardware implementations. The donation dialog supplies its own background, header and text colors to remain readable on both systems. This does not emulate the whole OEM SystemUI.

## Files

| Path | What's there |
|---|---|
| `Dashcam/` on the drive | recordings |
| `Dashcam/Saved/` | saved recordings, plus `names.txt` |
| `Dashcam/Snapshots/` | snapshots from the player, as JPEG |
| `/data/nemesis/dashcam.log` | the recorder's log (rotated at 1 MB) |
| `/data/nemesis/dashcam/status` | state (recording, reversing, live, reverse, caroff, off, unsupported, nodrive, starting), configured enabled state and drive presence, for the app |
| `/data/nemesis/dashcam/mirror-key-ready`, `mirror-key-filtered` | Mode-listener heartbeat and root watchdog's filter acknowledgement |
| `/data/system/ifw/dashcam-mirror-mode.xml` | temporary default-long-Mode broadcast filter; removed on listener failure or uninstall |
| `/data/nemesis/dashcam/settings` | the recorder's copy of the app's setting (recording on or off) |
| `/data/nemesis/dashcam/heartbeat` | for the watchdog |
| `/data/nemesis/dashcam-overlay.log` | native display helper startup, refusals and alpha changes; rotated at 1 MiB |
| `/data/nemesis/apps/dashcam/dashcam-overlay` | boot-started native display helper executable |
| `/data/nemesis/dashcam/guidelines-hidden` | present while the recorder has the guide lines off |
| `/data/data/dev.skramer.dashcam.player/files/recorder.conf` | the app's setting: `enabled=true` or `false` |

**Updates:** a NEMESIS update uninstalls the app before installing the new one, which wipes the app's own files. The recorder keeps a copy of the on/off setting outside the app, and the update keeps that copy, so it survives. Recordings and names are on the drive, so they're never affected.

**Uninstalling** stops the recorder, puts the guide lines back if needed, and deletes everything under `/data/nemesis/dashcam`. Recordings on the drive stay.

## The Dashcam app

- Plays a whole hour as one continuous timeline with Media3 ExoPlayer, which steps frame by frame and plays at 0.5× to 8×.
- Playback needs the Video Codec Fix package (`media.codecs`, from [gen5-codec-patch](https://github.com/KramerTech/gen5-codec-patch)). The firmware's codec list names decoders that can't be created, so without it ExoPlayer's decoder query fails and nothing plays. `NEMESIS.data` declares `depends=media.codecs`; NEMESIS blocks an install without it and prevents uninstalling the dependency while the dashcam is installed. Version 2.1 uses the current package-version metadata. Existing 2.0.x packages contain the same decoder binaries, so the dependency checks their presence without requiring newer metadata.
- Snapshot saves the frame as shown, zoom included.
- Like the stock apps, it follows the car's driving restriction (`com.hkmc.regulation`) and only works in Park.
- Renaming uses a keyboard built into the app, the same one as the Tilepad launcher's.

## Cars it can't record

The HAL reports a camera type (`RearCamera.getCameraType()`). The analog camera this was built on is type 0. Cars with a 360° (surround view) camera, such as the IONIQ 5 Limited, and the EV6 tested so far report type 6, a digital (LVDS) camera behind a deserializer (`tcc_camera/max96706_lvds` in the device tree). There, a direct-display session opens and reports a video signal, but the hardware writes only a few frames of dark gray and stops, at every size and with either parameter style. Frames reach the same RAM only while the car itself shows a camera view: about 60 ms after the camera button, even with our session still open. The button goes to the parking controller, which switches the video on and tells the head unit over CAN. No head-unit request found so far starts it (the parking service's rear-camera power request made no difference).

So the installer's `preinstall` hook (`scripts/preinstall.sh`) asks the HAL for the camera type, through `CameraCheck` in the staged `dashcam.jar`, and refuses type 6 with a toast. If it can't get an answer, it installs anyway; then the recorder checks the type itself when it starts, doesn't record on type 6, and reports the state `unsupported`, which the Dashcam app explains on its main screen. On an update, NEMESIS runs the old package's `preuninstall` before this hook, so a refused update leaves the dashcam uninstalled.

Only the IONIQ 5 is supported. `NEMESIS.data` says so with `os=NEEV23.*` (the firmware it was tested on, `ro.build.product`) and `models=neev*` (the IONIQ 5's model directories under `/system/onebin`, which NEMESIS reads from `ro.mobis.targetdir`). NEMESIS 1.16 uses these for its compatibility verdict, but a mismatch is only a warning the user can accept, and the NEEV23 firmware also carries the IONIQ 6 (`ceev_us`, `ceev_ca`), which would match `os`. So `preinstall` first refuses unless the model directory is `neev*`, then does the camera-type check. NEMESIS can't tell trims apart; the camera-type check is what keeps the 360-view trims out.

## Projection and the camera display layer

On the tested NEEV23/tcc803x head unit (`wp_daudioplus_neev23_us`, 1920×720),
RDMA0 is the UI, RDMA1 is the camera, and normal wired Android Auto video uses
RDMA3. The camera's direct-display plane sits above projected video, so keeping
the camera open for recording used to cover Android Auto. The native helper
makes the camera plane transparent while DMA keeps feeding camera RAM. Recording
continues with normal video cropping and UI composition. Overlay priority, video
routing and SurfaceFlinger settings are not changed.

### Boot privileges and register mapping

NEMESIS registers the helper as service `overlay` in `NEMESIS.data`, with
`uid=root`, `kind=sh`, and `mode=restart`. `svc/overlay.sh` waits for Android boot,
waits 10 seconds, rotates its log, sets executable permission on `dashcam-overlay`
(NEMESIS extracts it without execute permission), and starts it. The recorder
allows up to 12 seconds for its socket to appear on the matching display-tree
hierarchy before continuing.

The helper needs **CAP_SYS_RAWIO** to open `/dev/mem`. NEMESIS services started
at boot have it; a service started later by an install or `nemesis service start`
does not. `nemesis root` alone does not supply that capability either.
**Reboot after installing or updating the package.** Without register access,
the recorder logs that overlay control is unavailable and continues recording.
The temporary experimental `rootq` package is not needed.

Before mapping registers, the helper reads big-endian device-tree cells:

- `/proc/device-tree/fbdisplay@12000000/reg` must start at `0x12000000` and cover
  at least `0x2000` bytes.
- `fbdisplay0@12000000/telechips,rdma` beneath that node must list RDMA 0, 1, 2, 3
  after its phandle. These checks gate the hardware layout, not a model whitelist.
- `/proc/pmap` must provide a valid `rearcamera` memory region.

A different layout or missing camera region leaves the helper asleep without
mapping registers. With the matching layout, it maps only `0x2000` bytes at
physical `0x12000000`, read/write with `O_SYNC`, then closes the device descriptor.
It switches to uid/gid **1000**, dropping root capabilities, before listening.
The existing mapping remains usable after that privilege drop. There is no
persistent kernel, firmware, or device-permission patch.

The register offsets were checked against this head unit's kernel; older
published Telechips headers have different offsets. Before each hide request,
the helper checks an enabled BGRA RDMA1, even dimensions within its supported
range, the requested size, the frame address and four-buffer ring bounds against
`rearcamera`, and a 1920×720 mixer size.

| Address | Use |
|---|---|
| `0x12000500` | RDMA1 CTRL: alpha enable bit 11, alpha select bit 24, update bit 16; preserve DMA enable bit 28 and other fields |
| `0x12000508` | Read camera dimensions |
| `0x1200050c` | Read camera frame base address |
| `0x12000524` | RDMA1 constant alpha fields, mask `0x00ff00ff` |
| `0x12001808` | Read WMIX0 dimensions; require `0x02d00780` (1920×720) |

It saves the alpha fields and selection/enable bits, zeros both constant alpha
values, enables alpha with select 0, and issues the RDMA update. The camera plane
stays enabled and on screen so the DMA ring continues running. It does not write
frame addresses, dimensions, the mixer's priority, or other display planes.

### Camera ownership and recovery

The helper listens on abstract Unix socket `dev.skramer.dashcam.overlay`; there
is no socket file. `SO_PEERCRED` restricts clients to uid 1000. Its narrow protocol
accepts a five-byte hide request (`H` plus big-endian 16-bit width and height),
then a release byte. It accepts no arbitrary register addresses or commands.
Partial initial requests time out after 500 ms.

After `RearCam.startPreview`, `CameraOverlay` connects, requests transparency,
and waits for acknowledgement. The connection stays open for that camera
ownership period. Before `stopPreview`/`release`, including reverse and camera
button handoff, the recorder asks for restoration and waits for acknowledgement.
Client I/O has a 500 ms timeout so an unavailable helper cannot block camera
release indefinitely; the guard retains its 800 ms release deadline.

On the first camera start after boot, `startPreview` returns before the kernel
has turned the camera layer on (RDMA1 CTRL without its enable bit, then with a
zero size), and the helper refuses a layer that's off. A refused request is
retried in the background every 250 ms, for up to a minute, until it's granted or
the camera is released. Recording never waits for it. On the NEEV23 unit the
layer was ready about 10 seconds after boot; later starts succeed at once.

Socket closure or recorder death also restores alpha. SIGTERM, SIGINT and SIGHUP
ask the helper to restore and exit. Restoration preserves the current DMA enable
bit and respects a camera-driver reset: it only restores the saved alpha when
the expected alpha selection and camera size are still present. Cleanup never
re-enables a stopped camera plane. A forced SIGKILL cannot run cleanup; the
camera driver's subsequent stop/start resets its layer configuration. Live
writes are confined to volatile display registers, not persistent storage.

Between ownership changes the helper blocks in socket I/O: no per-frame work,
polling, shell commands, GPU rendering or frame copies. Its log is
`/data/nemesis/dashcam-overlay.log`, rotated at 1 MiB. Refused requests include
register values.

### Switching screens

The camera's alpha only takes where a layer is under it. The mixer's color
control for each layer (WMIX0 CCON, Telechips' default `0x20c44cc4`) blends by
alpha only in the region where both layers have pixels; where nothing is under a
layer it shows that layer's pixels as they are. Over Android Auto's video the
camera is hidden; with no video layer on (RDMA2 and RDMA3 off) it isn't.

That shows when switching screens. Telechips' hardware composer
(`hwcomposer.tcc803x.so`, `tc_hide_fbTarget`) hides the UI layer for a few
frames between apps, through framebuffer ioctl `0x91`, which sets RDMA0 to
constant alpha 0. The next frame flip turns its per-pixel alpha back on. On a
stock unit nothing changes on screen, since with nothing under the UI its alpha
doesn't count either and its last frame stays up. With the camera layer under
it, the camera showed for those frames.

So while it holds a lease, the helper checks the UI layer every millisecond, at
the lowest real-time priority (set at boot, before it drops root). When the UI is
hidden that way and no video layer is on, it sets RDMA0's constant alpha to
opaque, so the UI's last frame shows as on a stock unit; when a video layer comes
on, or the lease ends, it puts 0 back. With a video layer on it leaves the UI
alone, so video shows through. A screen refresh can still fall between the
composer's hide and the check, so now and then a single frame of camera shows.
The checks cost about 1.3% of one core.

Changing the camera layer's color control instead (pixel × alpha where nothing
is under it) hides it with no delay, but then a black rectangle shows where the
camera layer is. The chroma key could drop the layer out entirely, but nothing
resets it when the camera restarts, so a helper killed while it was set would
leave the stock reverse view invisible.

Wired Android Auto, continuous camera capture, stock-camera handoff and recorder
death recovery were checked on the NEEV23 unit. CarPlay and other units have not
been validated in that session. `native/test.sh` checks conversion, strip hashing,
and alpha guards/restoration against ordinary RAM rather than hardware registers.

Reverse detection polls every 50 ms while the recorder owns the camera. Other
guard work runs on a 250 ms cycle; ignition is checked on that cycle, settings
and heartbeat once per second. Idle and passive recording keep the 250 ms gear
interval. The stock-camera broadcast still releases immediately. Passive
ownership is published before acknowledging release, preventing a duplicate
handoff while cleanup finishes.

## USB status-bar notices

When a drive mounts, two notices show at the top of the screen while the media
scanner reads it: SystemUI's "Reading USB" (tag `StorageNotification`) and the
media scanner's own "No Supported Files" for a drive with no media (tag
`MediaScannerService`), both id 0. The scan still runs; only the notices are
taken down, with `INotificationManager.cancelNotificationWithTag`, which the
system uid may call for any package.

The recorder cancels both every 250 ms for a minute after it starts and after
each mount; while the drive is out it checks for a mount on every 250 ms poll.
At boot the drive mounts while Android is still starting, before the recorder
(which waits for boot), so `recorder.sh` first starts `UsbNotices` in the
background: it switches to the system uid, waits for the notification service,
cancels both notices every 250 ms for a minute and exits. A notice can still
flash briefly before it's cancelled.

## The main screen

With no drive, Saved (0) and Play Latest are disabled and dimmed.

The main recording button has a fixed width measured from all its status labels.
Its indicator is red while recording and amber while waiting. Neither recording toggle uses a selected border. Recording commands
hold their pending display until acknowledged by the recorder, ignoring stale
polls; the control becomes usable again after a ten-second timeout if no answer
arrives. `tests/record-command.sh` covers stale polls, transitions and the timeout.

Settings changes compare file length as well as modification time, because this
KitKat build can timestamp consecutive writes in the same second. This prevents
a fast enabled=true/false toggle from being missed.
