# Rear Dashcam for Gen5W head units

Turns your backup camera into a dashcam for Hyundai, Kia and Genesis cars with the Gen5W head unit. It records to a USB drive whenever the car is on, and a **Dashcam** app lets you watch the recordings on the car's screen.

## What you get

- **Always recording while the car is on.** Nothing to open or turn on. It stops when the car is off.
- **Never fills up.** When the drive is full, the oldest recordings are deleted to make room.
- **Your backup camera still works.** When you reverse or press the camera button, the dashcam steps aside, and starts again a few seconds after.
- **Save from the steering wheel.** Press the mute button four times quickly to keep the last two minutes.
- **Watch on the screen** (in Park): browse by day and hour, skip around, step frame by frame, zoom in on a license plate, take a snapshot.
- **Keep and name the moments that matter.** Saved recordings are never deleted to make room.

## What it changes in your car

- The parking guide lines are switched off while it records, so they don't end up in the video, and back on when you reverse. If you had them off already, they stay off.
- Recording pauses while you watch a recording or use SmartTube, because the car can't play video smoothly while the camera is recording.

## Install

You need [NEMESIS](https://github.com/xwtk/hmg_nemesis) on the head unit, and a USB drive formatted as FAT32 or exFAT. A 64 GB drive holds about 85 hours of driving.

Download the latest release from the [releases page](https://github.com/KramerTech/gen5-RearDashcam/releases/latest).

**From a USB drive:** unzip `dashcam-<version>-usb.zip` onto an empty USB drive. Plug it in once the head unit has fully started, and confirm the install. You can keep recording to that same drive afterwards.

**Over ADB:**
```sh
adb push dashcam-<version>.zip /data/local/tmp/
adb shell '/system/xbin/nemesis install --update /data/local/tmp/dashcam-<version>.zip'
```

To update, install the new version the same way. Your settings and saved recordings are kept.

## Finding your recordings

Everything is in the `Dashcam` folder on the USB drive, one file per minute, named by date and time. Saved recordings are in `Dashcam/Saved` and snapshots in `Dashcam/Snapshots`. Any computer can play them; VLC works well.

## If something goes wrong

**If your backup camera ever doesn't come up when you reverse, uninstall the dashcam and report it.** The log is at `/data/nemesis/dashcam.log`.

## License

MIT ([`LICENSE`](LICENSE)).
