# How the Rear Dashcam works

The details behind the [README](README.md), for the curious and for anyone debugging.

## The pieces

The package has two main pieces:

- **The recorder**, a background service with no screen. NEMESIS starts it at boot as `svc/recorder.sh`, which runs `dashcam.jar` under `app_process`. It starts as root, keeps the steering-wheel key device open, then switches to the system user (uid 1000) with group `sdcard_r`, so it can use the camera and write to the USB drive.
- **The Dashcam app** (`dev.skramer.dashcam.player`), for watching recordings and turning recording on and off. The app and the recorder only talk through files (see [Files](#files)).

Two more NEMESIS services support the recorder: `svc/watchdog.sh` kills it if it stops responding (see [Reverse](#reverse-and-the-camera-button)), and `svc/overlay.sh` starts the native display helper with the boot-time privileges needed to hide the camera layer (see [Projection](#projection-and-the-camera-display-layer)).

## Recording

- The recorder starts the backup camera the way the car's reverse view does: through the head unit's own rear camera service (`android.hardware.RearCamera`), with the reverse view's parameters and the recording size (720×480). It never gives the camera a preview surface: on this HAL that path can hang the camera until a reboot.
- It opens the camera the stock `RearCamera.open()` would: the first one whose info says it faces back. Only cameras 0 and 3 have the direct-display path that runs without a surface, so for anything else it uses 0. On the analog-camera IONIQ 5 that's camera 0.
- Started like that, the camera runs in "direct display" mode. The hardware deinterlaces the picture, scales it to the recording size and writes each frame, as BGRA, into a ring of four buffers in memory the kernel reserves for the camera (`rearcamera` in `/proc/pmap`). It also puts it on a display layer, which the home screen covers. The recorder reads those buffers through `/dev/admem`, mapped read-only, which doesn't touch the camera driver.
- Nothing signals a finished frame. A watcher thread looks at a strip near the top of each buffer every 2 ms (waiting 12 ms right after a buffer starts, since the next can't start for about a frame): when one changes, the hardware has started writing it, so the buffer before it is complete. That one is copied out while the next three are written (about 50 ms), and dropped if it starts being written again during the copy.
- The picture comes out as the reverse view shows it: mirrored, with the parking guide lines drawn in. The guide lines are switched off while recording (see [Parking guide lines](#parking-guide-lines)), and frames are mirrored back when they're converted.
- Copying frames out is the biggest cost: about 23 ms a frame, half a CPU core at 20 fps. The camera memory isn't System RAM, so the kernel maps it strongly-ordered (uncached, no burst reads) whatever we ask for; it's read with one `memcpy` per frame, which is as fast as that memory allows. At 720×480 and 20 fps the recorder uses a little over half of one core (of four).
- Every 30 seconds the log gets a line on how heavy recording is: frame rate, milliseconds per frame to copy out and to convert, frames lost, and CPU use from `/proc`: the recorder (and its busiest threads), mediaserver (where the camera service and the H.264 encoder run), the whole system, and the load average. The GPU isn't measured: the kernel has no load counters for it, and recording doesn't use it.
- Frame-detection strip hashing runs in native C in one JNI call for all four buffers, with the same 32-bit hashes and Java fallback. It reuses its buffers and preserves the existing capture cadence.
- Frames are kept down to the chosen frame rate on an even cadence. They're converted to I420 and mirrored left to right in the same pass, in C with NEON (`libdashcam.so`, about 1 ms a frame; the head unit's Dalvik VM took 12 ms in Java, which is still the fallback), straight into the encoder's input buffer, then passed to the head unit's hardware H.264 encoder (`OMX.TCC.ENC.avc`), with a keyframe every second.
- Encoded video is written as MPEG-TS files, one per minute by default, named by start time (`20261026_220609.ts`). A new file always starts on a keyframe. The file is synced to the drive every second, so a sudden power cut loses at most about a second.
- There's no audio; the camera has none.
- Before each new file, the oldest files in the `Dashcam` folder are deleted until the chosen share of the drive is free. Files in `Saved` and `Snapshots` are never touched.
- Normal recording waits until the drive is mounted at `/storage/usb0`, so it never fills the head unit's internal storage. The explicit `/dev/null` test mode runs the encoder without saving files.

### Settings

Normal recording has one setting: on or off (tap the status at the top of the main screen). Camera Debug also offers an explicit `/dev/null` test mode when no drive is mounted. Recording quality is fixed, at what's been tested: 720×480 at 20 fps, 3 Mbit/s (about 1.35 GB an hour), one file a minute, and 5% of the drive kept free.

To experiment, the recorder takes arguments in `/data/nemesis/apps/dashcam/dashcam.conf` (`DASHCAM_ARGS="--fps=15"`). Updating the package replaces that file.

| Option | Default | |
|---|---|---|
| `--fps` | `20` | frames per second kept from the camera's 60; each costs a copy, so this sets the CPU load |
| `--bitrate` | `3000000` | H.264 bits per second |
| `--segment` | `60` | seconds per file |
| `--min-free-percent` | `5` | the oldest files are deleted to keep this share of the drive free |
| `--out` | `/storage/usb0/Dashcam` | where recordings go |
| `--mount` | `/storage/usb0` | recording waits until this is mounted |
| `--width`, `--height` | `720`, `480` | recording size; the camera hardware scales to it |
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
- Letting go has 0.8 s to finish. If it doesn't, the recorder kills itself, which frees the camera too, and NEMESIS starts it again.
- The recorder updates a heartbeat file every second. If the heartbeat stops for about 6 seconds, the watchdog kills the recorder.

## Saving from the steering wheel

- The recorder reads the head unit's key device without grabbing it, so mute still works as usual. The device is named `daudio_` plus the head unit's model (`ro.product.device`): `daudio_wp_daudioplus_neev23_us`, `/dev/input/event1`, on the IONIQ 5. If no device has that name, the recorder uses the only `daudio_` device there is. Camera Debug reports list the input devices. It opens the device while still root, since the system user can't.
- Mute is key 37. Four presses, each within 0.7 s of the last, trigger a save. Quick presses on this car come about 0.2 s apart and ordinary ones over a second apart.
- On a save, the file being written ends at the next keyframe, under a second later, and recording carries on in a new file with nothing lost. Then every file last written to within the past two minutes moves to `Saved`. With 1-minute files that's 2 or 3 files, covering at least the two minutes before the press.
- The saved recording is named "Saved from steering wheel", and the head unit shows a pop-up through the car's own toast message.

## Saved recordings

- Saving moves files from `Dashcam` to `Dashcam/Saved`. It doesn't copy them, so saving is instant and uses no space.
- In the app, a saved recording is a run of saved files with no unsaved file between them and less than a minute between one's end and the next one's start. So a pause for reverse doesn't split one, and a save that touches an earlier one joins it. Unsaving and deleting always act on the whole run.
- **Save** in the player keeps the minute before and after what's on screen.
- Names are kept on the drive in `Dashcam/Saved/names.txt`, one line per recording: the name of its first file, a tab, then the name.

## Files

| Path | What's there |
|---|---|
| `Dashcam/` on the drive | recordings |
| `Dashcam/Saved/` | saved recordings, plus `names.txt` |
| `Dashcam/Snapshots/` | snapshots from the player, as JPEG |
| `/data/nemesis/dashcam.log` | the recorder's log (rotated at 1 MB) |
| `/data/nemesis/dashcam/status` | state (recording, recording-null, reversing, debug, reverse, caroff, off, nodrive, starting), configured enabled state, drive presence and null-test request, for the app |
| `/data/nemesis/dashcam/debug`, `debug-report.txt` | the debug session's state and recent log lines, and the recorder's part of a debug report, for the camera debug screen |
| `/data/nemesis/dashcam/settings` | the recorder's copy of the app's setting (recording on or off) |
| `/data/nemesis/dashcam/heartbeat` | for the watchdog |
| `/data/nemesis/dashcam-overlay.log` | native display helper startup, refusals and alpha changes; rotated at 1 MiB |
| `/data/nemesis/dashcam/null-recording-cancelled` | request token cancelled by USB insertion, preventing automatic reactivation |
| `/data/nemesis/apps/dashcam/dashcam-overlay` | boot-started native display helper executable |
| `/data/nemesis/dashcam/guidelines-hidden` | present while the recorder has the guide lines off |
| `/data/data/dev.skramer.dashcam.player/files/recorder.conf` | the app's setting: `enabled=true` or `false` |
| `/data/data/dev.skramer.dashcam.player/files/null-recording` | explicit null-test request token; removed when the app turns testing off |
| `/data/data/dev.skramer.dashcam.player/files/debug` | the camera debug screen's request (camera, size, report), rewritten every half second while it's open |
| `Dashcam/camera-debug-*.txt`, `.png` on the drive | debug reports and the picture at the time |

**Updates:** a NEMESIS update uninstalls the app before installing the new one, which wipes the app's own files. The recorder keeps a copy of the on/off setting outside the app, and the update keeps that copy, so it survives. Recordings and names are on the drive, so they're never affected.

**Uninstalling** stops the recorder, puts the guide lines back if needed, and deletes everything under `/data/nemesis/dashcam`. Recordings on the drive stay.

## The Dashcam app

- Plays a whole hour as one continuous timeline with Media3 ExoPlayer, which steps frame by frame and plays at 0.5× to 8×.
- Snapshot saves the frame as shown, zoom included.
- Like the stock apps, it follows the car's driving restriction (`com.hkmc.regulation`) and only works in Park.
- Renaming uses a keyboard built into the app, the same one as the Tilepad launcher's.

## Camera Debug

**Camera Debug** (long-press the drive space at the top right of the main screen) is for working out why a car records blank video. By default, while it's open, the recorder pauses saved recording and runs a debug session instead: the camera and size chosen on the screen (whether or not recording is on, and without a drive), checked like recording frames but not encoded. It still hands the camera to the reverse view.

- **Picture:** the screen maps the camera's memory itself, read-only, and draws completed frames as they arrive (up to 30 a second) with OpenGL, mirrored back like recordings. Debug sessions turn the guide lines off too, so frames from the stock view's session are the ones with guide lines. Because it reads the memory directly, it also shows frames the stock reverse view's session writes.
- **Log:** its own lines (`app`) and the recorder's (`rec`), timed to the millisecond: when frames start and stop arriving, when the picture appears or goes flat, the recorder's camera start, parameters, video signal and hand-offs. Press the camera button with the screen open, and the log shows whether the picture appeared before or after the recorder let go of the camera.
- **Scan RAM** (also run when the screen opens and whenever frames start or stop) samples every 16 KiB of `rearcamera` and `rearcamera_viqe` for a second. It lists which ranges change and what they hold, and guesses the frame size from how far the changing part reaches.
- **Save Report** writes `Dashcam/camera-debug-<time>.txt` and a `.png` of the picture. The text holds the log, the last scan, the camera list, `/proc/pmap`, the rear camera service's dump, camera-related lines from `getprop`, `dmesg` and the recorder's logcat, and the recorder's recent log. The recorder runs as the system user, so its logcat includes the stock camera app's lines but not mediaserver's.
- **Auto Test** tries nine setups for 10 s each (after 5 s for the camera to reopen): sizes 720×480 to 1920×720 including the stock view's 1140×720, 1710×720 and 1780×720, camera 3, and both parameter styles (ours, or the HAL's own with only the position and size changed, as the stock view does). It logs how many frames reached RAM for each, then saves a report on its own.
- Reports also list the device-tree camera, deserializer and video-input nodes with their status, and the input devices.
- Closing the screen ends the preview session; normal recording resumes when eligible. The explicit null-test request continues independently after leaving the page.
- After a minute out of sight, the screen closes itself.

## Cars it can't record

The HAL reports a camera type (`RearCamera.getCameraType()`). The analog camera this was built on is type 0. Cars with a 360° (surround view) camera, such as the IONIQ 5 Limited, and the EV6 tested so far report type 6, a digital (LVDS) camera behind a deserializer (`tcc_camera/max96706_lvds` in the device tree). There, a direct-display session opens and reports a video signal, but the hardware writes only a few frames of dark gray and stops, at every size and with either parameter style. Frames reach the same RAM only while the car itself shows a camera view: about 60 ms after the camera button, even with our session still open. The button goes to the parking controller, which switches the video on and tells the head unit over CAN. No head-unit request found so far starts it (the parking service's rear-camera power request made no difference).

So the installer's `preinstall` hook (`scripts/preinstall.sh`) asks the HAL for the camera type, through `CameraCheck` in the staged `dashcam.jar`, and refuses type 6 with a toast. If it can't get an answer, it installs anyway; then the recorder checks the type itself when it starts, doesn't record on type 6 (Camera Debug still runs), and reports the state `unsupported`, which the Dashcam app explains on its main screen. On an update, NEMESIS runs the old package's `preuninstall` before this hook, so a refused update leaves the dashcam uninstalled.

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
register values, and Camera Debug reports include its last 100 lines.

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

## Wired projection testing without a USB drive

The camera debug page has a **Record to /dev/null** toggle button, immediately
left of **Scan RAM**, with an empty/green circle for off/on. It is visible
only while the configured USB drive is not mounted. It starts the normal camera
and hardware encoder and discards encoded output without allocating frame copies,
writing segments, or consuming storage. It remains active after leaving the debug
page, so wired Android Auto can use the USB port. The main screen says
**Recording to /dev/null**; with testing off and no mounted drive it says
**No Drive Detected**. Capacity is read only after checking `/proc/mounts`.

The app writes a unique request token in its private files directory. The recorder
cancels that token when a drive is mounted, records the cancellation in its state
directory, and returns to normal drive recording. This works with the app closed
and survives recorder restarts; removing the drive cannot reactivate an old test
request. A new explicit toggle action is needed. The toggle is hidden and
switched off with a drive present. Camera size/source controls are locked to the
normal recording configuration during this test mode. Saving clips is unavailable
because encoded output is discarded. Ignition and reverse handoff still apply.

The debug preview stops reading camera RAM while its activity is hidden, avoiding
a second full-frame copy loop behind AA. It resumes on returning to the page.
`tests/null-recording.sh` verifies request cancellation and restart behavior.

Tapping the main recording control during null recording cancels the test and
leaves normal recording enabled, waiting for its USB drive. A hidden debug page
suspends its preview lease during the test so it cannot restart a debug session
after this cancellation. With no drive, Saved (0) and Play Latest are disabled
and dimmed. The null-recording button uses the normal background in both states;
only its empty/green indicator changes.

The main recording button has a fixed width measured from all its status labels.
Its indicator is green for null recording, red for recording to the drive, and amber
while waiting. Neither recording toggle uses a selected border. Recording commands
hold their pending display until acknowledged by the recorder, ignoring stale
polls; the control becomes usable again after a ten-second timeout if no answer
arrives. `tests/record-command.sh` covers stale polls, transitions and cancellation.

Settings changes compare file length as well as modification time, because this
KitKat build can timestamp consecutive writes in the same second. This prevents
a fast enabled=true/false toggle from being missed.
