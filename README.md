# Rear Dashcam for Gen5W head units

Turns your backup camera into a dashcam. Recordings go to a USB drive plugged into the car, and a Dashcam app lets you watch them on the head unit's screen.

It's a [NEMESIS](https://github.com/xwtk/hmg_nemesis) package for Hyundai, Kia and Genesis cars with the Gen5W head unit (Android 4.4). Once it's installed there's nothing to open or turn on: whenever the car is on and a USB drive is plugged in, it's recording.

## How it records

- Video is saved in one-minute files in a `Dashcam` folder on the USB drive, named by date and time, like `20261026_220609.ts`. Any computer can play them (VLC works well).
- When the drive gets full, the oldest recordings are deleted to make room, so it never stops recording.
- When you shift into reverse or press the camera button, the dashcam gets out of the way so the normal backup view works as usual. It starts recording again a few seconds later. It can't record while the backup view is on screen.
- If the car shuts off mid-recording, you lose at most the last second.

The parking guide lines show up in the recordings. Turning them off in the car's camera settings should remove them.

## The Dashcam app

Open **Dashcam** on the head unit to see whether it's recording, how much space is left, and your recordings by day and hour.

- Tap an hour to watch it. The recordings play back to back, so you can drag through the whole hour without stopping at each one-minute file.
- While watching, you can skip back or forward 10 seconds or a minute, go frame by frame, speed up or slow down, and pinch the video to zoom in on something like a license plate. Double-tap to zoom back out.
- **Save** keeps the minute before and after what you're watching, so it's never deleted to make room. Saved recordings are in **Saved** on the home screen.
- **Snapshot** saves a picture of what's on screen to the `Dashcam/Snapshots` folder on the drive.
- To delete a whole day, hold it in the list on the left. Saved recordings are kept.
- **Stop recording** turns the dashcam off until you turn it back on.
- **Settings** changes the frame rate, video quality, how long each file is, and how much of the drive to keep free.

The dashcam pauses while you're watching a recording or using SmartTube, and picks back up when you leave. The head unit can't play video cleanly while the camera is recording, so this keeps playback from glitching.

Like the Gen5W SmartTube build, the app only works in Park.

## Install

You need a USB drive formatted as FAT32 or exFAT. A 64 GB drive holds about 85 hours of driving at the default settings.

**From a USB drive:** unzip `dashcam-<version>-usb.zip` onto an empty USB drive, so the drive has `NEMESIS.data` and a `packages` folder on it. With the head unit fully started up, plug it in and confirm the install. Afterwards you can record to that same drive or any other.

**Over ADB:**
```sh
adb push dashcam-<version>.zip /data/local/tmp/
adb shell '/system/xbin/nemesis install --update /data/local/tmp/dashcam-<version>.zip'
```

Recording starts a few seconds after a USB drive is plugged in. To update, install the new version the same way. Your settings are kept.

## Default settings

| Setting | Default | |
|---|---|---|
| Frame rate | 15 fps | |
| Quality | 1.5 Mbit/s | about 0.7 GB per hour of driving |
| File length | 1 minute | |
| Keep free | 5% | |

To record somewhere other than the USB drive's `Dashcam` folder, edit `/data/nemesis/apps/dashcam/dashcam.conf` (for example `DASHCAM_ARGS="--out=/storage/usb0/MyFolder"`). Updating replaces that file.

## Safety

While the dashcam has the camera, the normal backup view can't use it. The dashcam lets go of it as soon as you shift into reverse or press the camera button. If it ever can't let go quickly, it shuts itself down instead, and a separate watchdog shuts it down if it stops responding.

**If your backup camera ever doesn't come up when you reverse, uninstall this package and report it.**

The dashcam's log is at `/data/nemesis/dashcam.log`.

## License

MIT ([`LICENSE`](LICENSE)).
