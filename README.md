# Rear dashcam for Gen5W head units

Records the backup camera to a USB drive, continuously, on Hyundai/Kia/Genesis Gen5W head units (Android 4.4.2, Telechips), as a [NEMESIS](https://github.com/xwtk/hmg_nemesis) package. It starts with the head unit and records whenever a drive is plugged in. A Dashcam app plays the recordings back on the head unit.

## What it does

- Records the backup camera at 720×480, 15 fps, H.264, into one-minute `.ts` files in `Dashcam/` on the USB drive, named by date and time (`20261026_220609.ts`). They play in VLC and most video players.
- When the drive is nearly full (less than 5% free), it deletes the oldest recordings to make room.
- Hands the camera to the normal reverse view whenever you shift into reverse or press the camera button, and starts recording again a few seconds after the reverse view closes. Those moments aren't recorded.
- If the power is cut mid-recording, the current file still plays up to about a second before the cut.

The parking guide lines appear in recordings because the camera draws them itself. Turning them off in the car's camera settings should remove them from recordings too.

## The Dashcam app

- **Home:** whether it's recording, drive space and how many hours it holds, footage by day and by hour. Tap an hour to play it. Hold a day to delete its footage (saved clips are kept).
- **Player:** plays a day's files back to back as one video, against the clock. Drag the hour bar to scrub; skip ±10 s or ±1 min, jump between hours, step frame by frame, play at ½× to 8×, pinch to zoom (double tap resets).
- **Save** keeps the minute before and after the moment on screen: those files move to `Dashcam/Saved/` and are never deleted to make room. **Saved** lists them to play, unsave or delete.
- **Snapshot** saves what's on screen, zoom included, as a JPEG in `Dashcam/Snapshots/`.
- **Stop / Start recording**, and **Settings**: frame rate, quality, file length and how much of the drive to keep free. Changes take effect within a few seconds.

Like SmartTube for Gen5W, the app closes when the car leaves Park.

Recording pauses while the player is open, and while SmartTube is in front. With the camera running, video playback on these units shows flashes of corrupt frames (a fault in the Telechips video drivers), so the recorder lets go of the camera while you watch.

## Install

You need a USB drive formatted FAT32 or exFAT; 64 GB holds about 85 hours of recording at the default settings.

- **USB:** extract `dashcam-<version>-usb.zip` onto the root of a drive, so it holds `NEMESIS.data` and `packages/dashcam.zip`. With the head unit fully booted, plug it in and confirm the install prompt. Then use any drive for recording, or the same one.
- **ADB:**
  ```sh
  adb push dashcam-<version>.zip /data/local/tmp/
  adb shell '/system/xbin/nemesis install --update /data/local/tmp/dashcam-<version>.zip'
  ```

Recording starts a few seconds after a drive is mounted. To update, install the new version the same way; settings are kept.

## Settings

| Setting | Default | |
|---|---|---|
| Frame rate | 15 fps | the camera runs at 60 |
| Quality | 1.5 Mbit/s | about 0.7 GB an hour |
| File length | 1 min | |
| Keep free | 5% | oldest files are deleted to keep this much of the drive free |

Set them in the app. Where recordings go is set in `/data/nemesis/apps/dashcam/dashcam.conf` (`DASHCAM_ARGS="--out=/storage/usb0/Dashcam --mount=/storage/usb0"`); updating the package replaces that file.

## Safety

The dashcam and the reverse view share the camera, and while the dashcam holds it the reverse view can't show anything. So the dashcam lets go the moment the car goes into reverse or the camera button is pressed, and if letting go ever takes longer than 0.8 s, it shuts itself down instead, which also frees the camera. A separate watchdog shuts it down if it stops responding. **If the reverse camera ever fails to come up, stop and report it, and uninstall the package until it's fixed.**

Log: `/data/nemesis/dashcam.log`.

## License

MIT ([`LICENSE`](LICENSE)).
