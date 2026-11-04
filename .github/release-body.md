Rear dashcam @VERSION@ for Gen5W head units (Android 4.4), as a NEMESIS package: the recorder service, the Dashcam playback app and the Digital Rearview Mirror app in one install.
@CHANGES@
**Install [gen5-codec-patch](https://github.com/KramerTech/gen5-codec-patch/releases) (`media.codecs` 2.1+) first.** It's required for on-device video playback. Needs NEMESIS 1.16 or newer. Only for the IONIQ 5 SE and SEL (no 360° view); the installer refuses other cars.

Install over ADB with `nemesis install --update @ZIP@`, or by USB: extract `@USBZIP@` onto the root of a FAT32/exFAT drive and plug it in. **Reboot the head unit after installing or updating to activate the camera overlay fix.** Recordings and the recording on/off setting carry over when you update. Check downloads against `SHA256SUMS`. `DashcamPlayer.apk` and `DigitalRearviewMirror.apk` are the apps on their own; the package already contains them, and the mirror needs the package's recorder.

**If the reverse camera ever fails to come up, stop and report it, and uninstall the package until it's fixed.** Details in the README.
