# Rear Dashcam for Gen5W head units (Without 360 View)

Turns your backup camera into a dashcam for the Hyundai IONIQ 5 with the Gen5W head unit. It records to a USB drive whenever the car is on, and a **Dashcam** app lets you watch the recordings on the car's screen.

> [!IMPORTANT]
> **Only the IONIQ 5 SE and SEL are supported** (a plain backup camera, no 360° view). It's been tested on an IONIQ 5 SEL. The installer refuses other models with an "only supported on the Hyundai IONIQ 5" message.
>
> **Cars with a 360° (surround view) camera aren't supported**, including the IONIQ 5 Limited and the EV6 GT-Line. On those cars the camera only sends a picture while the car is showing its own camera view, so there's nothing to record the rest of the time. The installer checks your car's camera and refuses to install if it won't work, with a "camera system not supported" message.

> [!CAUTION]
> **Experimental software. It may brick your head unit, and it comes with no warranty of any kind.** It runs with root access, takes over your backup camera, and changes the head unit's display hardware settings while it records. It's a personal hobby project, not made or approved by Hyundai, Kia or Genesis, and it has only been tested on a few cars. Installing it can void your warranty, and if anything goes wrong (a frozen screen, a backup camera that won't come up, a head unit that won't start) that's yours to fix. Use it at your own risk.

## What you get

- **Always recording while the car is on.** Nothing to open or turn on. It stops when the car is off.
- **A red dot in the status bar** (next to the Bluetooth and signal icons) whenever it's recording to the drive. No dot means nothing is being saved.
- **Never fills up.** When the drive is full, the oldest recordings are deleted to make room.
- **Your backup camera still works.** When you reverse or press the camera button, the dashcam hands the camera over and keeps recording what the backup view shows, guide lines and all, then takes the camera back a few seconds after.
- **Save from the steering wheel.** Press the mute button four times quickly to keep the last two minutes.
- **Watch on the screen** (in Park): browse by day and hour, skip around, step frame by frame, zoom in on a license plate, take a snapshot.
- **Save clips.** Saved recordings are never deleted to make room, and you can rename them.

## What it changes in your car

- The parking guide lines are switched off while it records, so they don't end up in the video, and back on when you reverse. If you had them off already, they stay off.

## Install

You need [NEMESIS](https://github.com/xwtk/hmg_nemesis) 1.16 or newer on the head unit, and a USB drive formatted as FAT32 or exFAT. A 64 GB drive holds about 85 hours of driving.

> **Install [gen5-codec-patch](https://github.com/KramerTech/gen5-codec-patch) first.** The dashcam depends on its `media.codecs` package, which is required for on-device video playback: without it, the Dashcam app can't play recordings on the car's screen. NEMESIS checks for it and warns before installing the dashcam without it.

1. Install `media.codecs` from [gen5-codec-patch releases](https://github.com/KramerTech/gen5-codec-patch/releases) first. Version 2.1 is recommended for new installs. Existing 2.0.x installations use the same decoder binaries and also work; their older metadata does not report a package version to NEMESIS.
2. Download the latest dashcam release from the [releases page](https://github.com/KramerTech/gen5-RearDashcam/releases/latest) and install it from a USB drive or over ADB:

**From a USB drive:** unzip `dashcam-<version>-usb.zip` onto an empty USB drive. Plug it in once the head unit has fully started, and confirm the install. You can keep recording to that same drive afterwards.

**Over ADB:**
```sh
adb push dashcam-<version>.zip /data/local/tmp/
adb shell '/system/xbin/nemesis install --update /data/local/tmp/dashcam-<version>.zip'
```

After installing or updating version 0.15 or newer, reboot the head unit so the camera picture stops showing over Android Auto and CarPlay. If it still shows on your car, report it with `/data/nemesis/dashcam-overlay.log`, the fix's log.

The package also includes **Digital Rearview Mirror**, a separate app showing only the live rear camera. Hold the left steering-wheel **Mode** button to open it; a normal short press still changes media sources. Hold **Mode** again, tap anywhere, or press Back to close it. Its bottom-right corner shows a blinking red dot and "Recording to USB" while the dashcam is recording, or "Recording Disabled" or "No Flash Drive" when it isn't. The bottom-left corner reminds you the camera has a wide-angle lens: objects closer than they appear. It uses the recorder's camera session, including a preview-only session when there is no USB drive, and yields to the stock parking camera. It does not offer recording playback; the Dashcam player's parked-only restriction remains in place. Installing the mirror APK alone requires the matching recorder package to supply its video and Mode trigger.

To update, install the new version the same way. Your recordings, saved clips and the on/off setting are kept.

## Finding your recordings

Everything is in the `Dashcam` folder on the USB drive, one file per minute, named by date and time. Saved recordings are in `Dashcam/Saved` and snapshots in `Dashcam/Snapshots`. Any computer can play them; VLC works well.

## If something goes wrong

**If your backup camera ever doesn't come up when you reverse, uninstall the dashcam and report it.** The log is at `/data/nemesis/dashcam.log`.

## How it works

The details, settings files and limits are in [TECHNICAL.md](TECHNICAL.md).

## License

MIT ([`LICENSE`](LICENSE)).
