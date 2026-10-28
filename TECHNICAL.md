# How the Rear Dashcam works

The details behind the [README](README.md), for the curious and for anyone debugging.

## The pieces

The package installs two things:

- **The recorder**, a background service with no screen. NEMESIS starts it at boot as `svc/recorder.sh`, which runs `dashcam.jar` under `app_process`. It starts as root, keeps the steering-wheel key device open, then switches to the system user (uid 1000) with group `sdcard_r`, so it can use the camera and write to the USB drive.
- **The Dashcam app** (`dev.skramer.dashcam.player`), for watching recordings and changing settings. The app and the recorder only talk through files (see [Files](#files)).

A second service, `svc/watchdog.sh`, kills the recorder if it stops responding (see [Reverse](#reverse-and-the-camera-button)).

## Recording

- The recorder starts the backup camera the way the car's reverse view does: through the head unit's own rear camera service (`android.hardware.RearCamera`), with the reverse view's parameters and the recording size (720×480). It never gives the camera a preview surface: on this HAL that path can hang the camera until a reboot.
- It opens the camera the stock `RearCamera.open()` would: the first one whose info says it faces back. Only cameras 0 and 3 have the direct-display path that runs without a surface, so for anything else it uses 0. On the analog-camera IONIQ 5 that's camera 0.
- Started like that, the camera runs in "direct display" mode. The hardware deinterlaces the picture, scales it to the recording size and writes each frame, as BGRA, into a ring of four buffers in memory the kernel reserves for the camera (`rearcamera` in `/proc/pmap`). It also puts it on a display layer, which the home screen covers. The recorder reads those buffers through `/dev/admem`, mapped read-only, which doesn't touch the camera driver.
- Nothing signals a finished frame. A watcher thread looks at a strip near the top of each buffer every 2 ms (waiting 12 ms right after a buffer starts, since the next can't start for about a frame): when one changes, the hardware has started writing it, so the buffer before it is complete. That one is copied out while the next three are written (about 50 ms), and dropped if it starts being written again during the copy.
- The picture comes out as the reverse view shows it: mirrored, with the parking guide lines drawn in. The guide lines are switched off while recording (see [Parking guide lines](#parking-guide-lines)), and frames are mirrored back when they're converted.
- Copying frames out is the biggest cost: about 23 ms a frame, half a CPU core at 20 fps. The camera memory isn't System RAM, so the kernel maps it strongly-ordered (uncached, no burst reads) whatever we ask for; it's read with one `memcpy` per frame, which is as fast as that memory allows. At 720×480 and 20 fps the recorder uses a little over half of one core (of four).
- Every 30 seconds the log gets a line on how heavy recording is: frame rate, milliseconds per frame to copy out and to convert, frames lost, and CPU use from `/proc`: the recorder (and its busiest threads), mediaserver (where the camera service and the H.264 encoder run), the whole system, and the load average. The GPU isn't measured: the kernel has no load counters for it, and recording doesn't use it.
- Frames are kept down to the chosen frame rate on an even cadence. They're converted to I420 and mirrored left to right in the same pass, in C with NEON (`libdashcam.so`, about 1 ms a frame; the head unit's Dalvik VM took 12 ms in Java, which is still the fallback), straight into the encoder's input buffer, then passed to the head unit's hardware H.264 encoder (`OMX.TCC.ENC.avc`), with a keyframe every second.
- Encoded video is written as MPEG-TS files, one per minute by default, named by start time (`20261026_220609.ts`). A new file always starts on a keyframe. The file is synced to the drive every second, so a sudden power cut loses at most about a second.
- There's no audio; the camera has none.
- Before each new file, the oldest files in the `Dashcam` folder are deleted until the chosen share of the drive is free. Files in `Saved` and `Snapshots` are never touched.
- Recording waits until the drive is mounted at `/storage/usb0`, so it never fills the head unit's internal storage.

### Settings

Set in the app under **Settings**:

| Setting | Choices | Default |
|---|---|---|
| Frame rate | 10, 15, 20, 30 fps | 15 fps |
| Quality | 1–5 Mbit/s | 1.5 Mbit/s (about 0.7 GB per hour) |
| File length | 1, 2, 5 minutes | 1 minute |
| Keep free | 5, 10, 20% of the drive | 5% |

Changing one of these restarts the recorder with the new values. Stopping and starting recording from the app takes effect without a restart.

Less common options go in `/data/nemesis/apps/dashcam/dashcam.conf`, as arguments to the recorder (`DASHCAM_ARGS="--out=/storage/usb0/MyFolder"`). Updating the package replaces that file.

| Option | Default | |
|---|---|---|
| `--out` | `/storage/usb0/Dashcam` | where recordings go |
| `--mount` | `/storage/usb0` | recording waits until this is mounted |
| `--width`, `--height` | `720`, `480` | recording size; the camera hardware scales to it |
| `--mirror` | `true` | flip left to right |
| `--hide-guide-lines` | `true` | turn the parking guide lines off while recording |
| `--save-button` | `daudio_wp_daudioplus_neev23_us:37` | input device and key code for the save button; empty turns it off |
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

- Four times a second, the recorder reads the gear (`RearCamera.getGearStatus()`, which also reports the camera button). In reverse it lets go of the camera at once. It takes the camera back 3 seconds after the last reverse reading, and only once the car's camera app has closed it (checked through the rear camera service's status).
- If the car's camera app tries to open the camera before the dashcam has let go, it fails and tries again every half second, for up to 15 seconds.
- **Recording goes on through reverse.** The car's camera app runs the camera the same way, and its frames land in the same memory ring, at its own size (1140×720 on the analog-camera IONIQ 5). After letting go, the recorder samples that memory for a third of a second at a time until frames show up, and works out their size: the one for which every change has its counterparts in the other three frames of the ring, from a list of likely sizes. It then copies those frames out, reading only the rows it needs and scaling them to the recording size, into the same file. The guide lines are back on for you, so they're in this part. There's a gap of about a second while the camera changes hands, and another while the recorder takes it back; if no size fits, nothing is recorded until then. Each reverse ends with a log line: how long, the frame size, frames recorded.
- Letting go has 0.8 s to finish. If it doesn't, the recorder kills itself, which frees the camera too, and NEMESIS starts it again.
- The recorder updates a heartbeat file every second. If the heartbeat stops for about 6 seconds, the watchdog kills the recorder.

## Saving from the steering wheel

- The recorder reads the head unit's key device (`/dev/input/event1`, named `daudio_wp_daudioplus_neev23_us`) without grabbing it, so mute still works as usual. It opens the device while still root, since the system user can't.
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
| `/data/nemesis/dashcam/status` | what the recorder is doing (recording, reversing, debug, reverse, caroff, off, nodrive, starting), for the app |
| `/data/nemesis/dashcam/debug`, `debug-report.txt` | the debug session's state and recent log lines, and the recorder's part of a debug report, for the camera debug screen |
| `/data/nemesis/dashcam/settings` | the recorder's copy of the app's settings |
| `/data/nemesis/dashcam/heartbeat` | for the watchdog |
| `/data/nemesis/dashcam/guidelines-hidden` | present while the recorder has the guide lines off |
| `/data/data/dev.skramer.dashcam.player/files/recorder.conf` | the app's settings |
| `/data/data/dev.skramer.dashcam.player/files/debug` | the camera debug screen's request (camera, size, report), rewritten every half second while it's open |
| `Dashcam/camera-debug-*.txt`, `.png` on the drive | debug reports and the picture at the time |

**Updates:** a NEMESIS update uninstalls the app before installing the new one, which wipes the app's own files. The recorder keeps a copy of the settings outside the app, and the update keeps that copy, so settings survive. Recordings and names are on the drive, so they're never affected.

**Uninstalling** stops the recorder, puts the guide lines back if needed, and deletes everything under `/data/nemesis/dashcam`. Recordings on the drive stay.

## The Dashcam app

- Plays a whole hour as one continuous timeline with Media3 ExoPlayer, which steps frame by frame and plays at 0.5× to 8×.
- Snapshot saves the frame as shown, zoom included.
- Like the stock apps, it follows the car's driving restriction (`com.hkmc.regulation`) and only works in Park.
- Renaming uses a keyboard built into the app, the same one as the Tilepad launcher's.

## Camera debug

**Camera debug**, at the top right of the app's Settings, is for working out why a car records blank video. While it's open, the recorder stops recording and runs a debug session instead: the camera and size chosen on the screen (whether or not recording is on, and without a drive), checked like recording frames but not encoded. It still hands the camera to the reverse view.

- **Picture:** the screen maps the camera's memory itself, read-only, and draws completed frames as they arrive (up to 30 a second) with OpenGL, mirrored back like recordings. Debug sessions turn the guide lines off too, so frames from the stock view's session are the ones with guide lines. Because it reads the memory directly, it also shows frames the stock reverse view's session writes.
- **Log:** its own lines (`app`) and the recorder's (`rec`), timed to the millisecond: when frames start and stop arriving, when the picture appears or goes flat, the recorder's camera start, parameters, video signal and hand-offs. Press the camera button with the screen open, and the log shows whether the picture appeared before or after the recorder let go of the camera.
- **Scan RAM** (also run when the screen opens and whenever frames start or stop) samples every 16 KiB of `rearcamera` and `rearcamera_viqe` for a second. It lists which ranges change and what they hold, and guesses the frame size from how far the changing part reaches.
- **Save report** writes `Dashcam/camera-debug-<time>.txt` and a `.png` of the picture. The text holds the log, the last scan, the camera list, `/proc/pmap`, the rear camera service's dump, camera-related lines from `getprop`, `dmesg` and the recorder's logcat, and the recorder's recent log. The recorder runs as the system user, so its logcat includes the stock camera app's lines but not mediaserver's.
- Leaving the screen ends the session; recording starts again a few seconds later.
- After a minute out of sight, the screen closes itself.
