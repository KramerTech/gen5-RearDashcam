# Development on Windows and Linux

The launcher supports x86_64 Windows and Linux. Use Python 3.11.8 or newer.
From the repository root on Windows:

```powershell
python dev.py setup
python dev.py run
```

On a Linux desktop:

```sh
python3 dev.py setup
python3 dev.py run
```

Linux uses KVM for emulator acceleration and X11/XWayland for the interactive window.
`DISPLAY` must be set. On Ubuntu 24.04, install any missing runtime dependencies with:

```sh
sudo apt-get update
sudo apt-get install qemu-kvm libxcb-cursor0 libxcb-xinerama0 libxkbcommon-x11-0 libxkbfile1 \
  libnss3 libgl1 libegl1 libpulse0 libasound2t64 libsm6 libice6
sudo usermod -aG kvm "$USER"
```

Log out and back in after changing group membership. Other distributions need the
equivalent packages and read/write access to `/dev/kvm`. Setup does not change system
packages or account permissions. It can finish without KVM or a display; builds and
tests work in that environment, but running the interactive emulator needs both.

`setup` installs Google's Android command-line tools, SDK platform 36, build tools 36.0.0,
the emulator, and an Android 9 (API 28) x86_64 image. It reuses an existing JDK 17+ or
downloads Amazon Corretto 17 into `.cache/jdk` (Windows) or `.cache/jdk/linux` (Linux).
SDK downloads use `%LOCALAPPDATA%\Android\Sdk` on Windows and `~/Android/Sdk` on Linux,
or the path in `ANDROID_HOME` / `ANDROID_SDK_ROOT`. The AVD, logs, and configuration
use `.cache/dev` on Windows and `.cache/dev/linux` on Linux. It writes
`player/local.properties` for the SDK and refreshes it before each app build, so switching
OS in a shared checkout does not leave Gradle using the other OS's SDK path. Use separate
checkouts for simultaneous work in two IDEs, which can each write `local.properties`.
The setup needs internet access and several GB of free disk space. No release signing
key is needed. Existing tools are reused on subsequent setup runs.

To choose existing tools explicitly:

```powershell
python dev.py setup --sdk C:\Android\Sdk --java-home C:\Java\jdk-21
```

```sh
python3 dev.py setup --sdk "$HOME/Android/Sdk" --java-home /usr/lib/jvm/java-17-openjdk-amd64
```

`run` builds the recorder JAR and a debug APK, opens the **Gen5 Dashcam Development** emulator in a desktop
window, installs the app, seeds sample footage, and opens Dashcam. Click or drag with
the mouse to interact; the keyboard works too. The emulator uses a 1280×720 landscape
display, 2 GB of RAM, four virtual CPU cores, and software graphics rendering with
hardware-accelerated CPU virtualization. Closing the emulator window shuts it down.

The sample library contains three synthetic test-pattern clips under `/sdcard/Dashcam`.
Use **Play latest**, the day/hour timeline, **Saved**, and **Settings** to exercise the UI.
A small shell simulator updates recorder status and responds to the recording switch
and settings, including encoder selection. This simulator does not record or encode.
It runs only on this dedicated AVD; the launcher checks the AVD name before changing
device data. SELinux is permissive in this AVD so the unmodified car app can read the
simulator's `/data/nemesis` files. Other devices and their apps are not managed.

| Command | What it does |
|---|---|
| `python dev.py run` | Build and open the interactive app with demo data |
| `python dev.py run --no-build` | Reinstall the existing debug APK and open it |
| `python dev.py run --no-demo` | Skip demo seeding and starting the simulator |
| `python dev.py build` | Build the recorder JAR and cache `DashcamPlayer-debug.apk` for this OS |
| `python dev.py build-recorder` | Build the API 19 recorder JAR at `.cache/dev/dashcam.jar` |
| `python dev.py test` | Compile the recorder and run the JVM encoder regression suite |
| `python dev.py logs` | Stream logcat from this emulator; Ctrl+C stops the log stream |
| `python dev.py doctor` | Print paths and verify Java and emulator acceleration |
| `python dev.py stop` | Shut down this project's emulator |

Use `python3` instead of `python` for these commands on Linux. The recorder JAR is
`.cache/dev/linux/dashcam.jar` on Linux. `test` runs development-launcher regression
checks as well as the JVM encoder suite.

Each OS keeps Gradle's project cache, app build outputs (`player-build`), and
`DashcamPlayer-debug.apk` in its own cache directory. `--no-build` uses that
copy so switching systems does not install an APK signed with the other OS's debug key.
Run a normal build once on each OS before using `--no-build` there.
Direct Android Studio or Gradle builds still use `player/app/build`; the release build
script is unchanged.

`run` preserves app settings and saved clips. It refreshes the three sample files for
the emulator's current date. To test an empty library, delete clips in the app and
relaunch with `--no-demo`. This flag does not stop a simulator already running; restart
the emulator with `stop` and `run --no-demo` for a session without simulation.

In VS Code, use **Terminal → Run Task → Dashcam: Run emulator**. **Ctrl+Shift+B** builds
the recorder and debug app. Tasks automatically use `python3` on Linux. Tasks are also
provided for setup, recorder tests, logs, and shutdown.
For Android Studio, open the `player` directory, select the JDK printed by `doctor`
as the Gradle JDK, and use the running emulator as the deployment target:
`emulator-5580` (`Gen5_Player`) on Windows, or `emulator-5582` (`Gen5_Player_Linux`) on Linux.
The launcher invokes the checked-in Gradle wrapper directly; Windows does not need Bash.

## Virtualization

On Windows, the emulator needs Windows Hypervisor Platform (WHPX), with CPU virtualization enabled
in firmware. `python dev.py doctor` should report that WHPX is installed and usable.
If it is unavailable, enable **Windows Hypervisor Platform** in **Turn Windows features
on or off**, then reboot. See Google's [hardware acceleration instructions](https://developer.android.com/studio/run/emulator-acceleration).
On Linux, `python3 dev.py doctor` should report that KVM is installed and usable.
If `/dev/kvm` is absent, check CPU virtualization and the host's KVM modules. If access
is denied, check the `kvm` group and sign in again after adding your account.
Emulator startup logs are `emulator.stdout.log` and `emulator.stderr.log` in the OS's
`.cache/dev` directory listed above. Early emulator exits report these log paths instead
of waiting for the boot timeout.

On WSL2, builds and tests work with the Linux tools. The Linux emulator additionally
requires working nested KVM and WSLg/XWayland; those are not available in every WSL setup.
The Windows emulator remains available through the Windows launcher and uses its own AVD
and port, so neither launcher manages the other OS's virtual device.

## What still needs the car

This is a stock Android 9 emulator for app development, not the Gen5W Android 4.4 firmware.
It does not supply the Telechips `OMX.TCC.ENC.avc` encoder, the rear-camera capture HAL,
NEMESIS, ignition/gear services, steering-wheel input, or parking-guide controls.
The JVM tests simulate codec behavior; the emulator does not establish hardware encoder
compatibility or correct handoff to the stock reverse camera. Test those on the head unit.

The fixture `dev/sample.ts` was generated locally using FFmpeg's synthetic test source:

```sh
ffmpeg -f lavfi -i testsrc2=size=720x480:rate=15 -t 12 -c:v libx264 \
  -preset veryfast -pix_fmt yuv420p -b:v 350k -g 15 -an -f mpegts dev/sample.ts
```
