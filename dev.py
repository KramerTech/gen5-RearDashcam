"""Windows/Linux development tools: python[3] dev.py setup, run, build, test, logs, or stop."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
IS_WINDOWS = sys.platform == "win32"
HOST = "windows" if IS_WINDOWS else "linux"
WORK = ROOT / ".cache" / "dev"
if not IS_WINDOWS:
    WORK = WORK / HOST
JDK_CACHE = ROOT / ".cache" / "jdk"
if not IS_WINDOWS:
    JDK_CACHE = JDK_CACHE / HOST
CONFIG = WORK / "environment.json"
DEBUG_APK = WORK / "DashcamPlayer-debug.apk"
PLAYER_BUILD = WORK / "player-build"
AVD_HOME = WORK / "avd"
AVD_NAME = "Gen5_Player" if IS_WINDOWS else "Gen5_Player_Linux"
EMULATOR_PORT = 5580 if IS_WINDOWS else 5582
SERIAL = f"emulator-{EMULATOR_PORT}"
PACKAGE = "dev.skramer.dashcam.player"
SDK_DOWNLOADS = {
    "windows": ("commandlinetools-win-16111833_latest.zip", "57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d"),
    "linux": ("commandlinetools-linux-16111833_latest.zip", "e025545c62a8e64c7559119566a569fb1dec5f60"),
}


def executable(name):
    return name + (".exe" if IS_WINDOWS else "")


def download(url, destination, checksum=None, algorithm="sha256"):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.exists():
        print(f"Downloading {destination.name}...", flush=True)
        partial = destination.with_suffix(destination.suffix + ".part")
        with urllib.request.urlopen(url, timeout=60) as source, partial.open("wb") as target:
            shutil.copyfileobj(source, target)
        partial.replace(destination)
    if checksum:
        with destination.open("rb") as source:
            digest = hashlib.file_digest(source, algorithm).hexdigest()
        if digest.lower() != checksum.lower():
            raise RuntimeError(f"Checksum mismatch: {destination}; remove it and rerun setup")


def extract(archive, destination, strip_prefix=None):
    if archive.name.endswith(".tar.gz"):
        if not hasattr(tarfile, "data_filter"):
            raise RuntimeError("Safe JDK extraction needs Python 3.11.8+ or 3.12+")
        with tarfile.open(archive, "r:gz") as bundle:
            bundle.extractall(destination, filter="data")
        return
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            parts = PurePosixPath(member.filename).parts
            if not parts or ".." in parts or PurePosixPath(member.filename).is_absolute():
                raise RuntimeError(f"Unsafe archive entry: {member.filename}")
            if strip_prefix:
                if parts[0] != strip_prefix:
                    raise RuntimeError(f"Unexpected archive entry: {member.filename}")
                parts = parts[1:]
            if not parts:
                continue
            target = destination.joinpath(*parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise RuntimeError(f"Unsafe archive entry: {member.filename}")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(member) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
                if not IS_WINDOWS:
                    mode = (member.external_attr >> 16) & 0o777
                    if mode:
                        target.chmod(mode)


def suitable_jdk(path):
    if not path or not (path / "bin" / executable("javac")).is_file():
        return False
    result = subprocess.run([str(path / "bin" / executable("java")), "-version"], capture_output=True, text=True)
    version = re.search(r'version "(\d+)', result.stderr)
    return result.returncode == 0 and version and int(version[1]) >= 17


def find_jdk(requested):
    if requested:
        if not suitable_jdk(requested):
            raise RuntimeError("--java-home must point to a JDK 17 or newer")
        return requested.resolve()
    candidates = list(JDK_CACHE.glob("*"))
    compiler = shutil.which(executable("javac"))
    if compiler:
        candidates.insert(0, Path(compiler).resolve().parent.parent)
    if os.environ.get("JAVA_HOME"):
        candidates.insert(0, Path(os.environ["JAVA_HOME"]))
    for candidate in candidates:
        if suitable_jdk(candidate):
            return candidate.resolve()
    filename = f"amazon-corretto-17-x64-{HOST}-jdk." + ("zip" if IS_WINDOWS else "tar.gz")
    url = "https://corretto.aws/downloads/latest/" + filename
    checksum_url = url.replace("/downloads/latest/", "/downloads/latest_sha256/")
    with urllib.request.urlopen(checksum_url, timeout=60) as response:
        checksum = response.read().decode().split()[0]
    archive = WORK / "downloads" / filename
    download(url, archive, checksum)
    extract(archive, JDK_CACHE)
    for candidate in JDK_CACHE.glob("*"):
        if suitable_jdk(candidate):
            return candidate.resolve()
    raise RuntimeError("JDK extraction did not produce a usable compiler")


def environment(config):
    env = os.environ.copy()
    env.update(JAVA_HOME=config["java_home"], ANDROID_HOME=config["sdk"],
               ANDROID_SDK_ROOT=config["sdk"], ANDROID_AVD_HOME=str(AVD_HOME))
    bins = [str(Path(config["java_home"]) / "bin"),
            str(Path(config["sdk"]) / "platform-tools"), str(Path(config["sdk"]) / "emulator")]
    env["PATH"] = os.pathsep.join(bins + [env.get("PATH", "")])
    return env


def run(command, config, check=True, **kwargs):
    return subprocess.run(list(map(str, command)), env=environment(config), check=check, **kwargs)


def adb(config, *args, **kwargs):
    return run([Path(config["sdk"]) / "platform-tools" / executable("adb"), "-s", SERIAL, *args], config, **kwargs)


def configure_avd(sdk):
    device = AVD_HOME / f"{AVD_NAME}.avd"
    device.mkdir(parents=True, exist_ok=True)
    settings = {
        "AvdId": AVD_NAME, "avd.ini.displayname": "Gen5 Dashcam Development",
        "avd.ini.encoding": "UTF-8", "abi.type": "x86_64", "hw.cpu.arch": "x86_64",
        "hw.cpu.ncore": "4", "hw.ramSize": "2048", "vm.heapSize": "256",
        "hw.lcd.width": "1280", "hw.lcd.height": "720", "hw.lcd.density": "160",
        "hw.initialOrientation": "landscape", "hw.keyboard": "yes", "hw.dPad": "no",
        "hw.mainKeys": "no", "hw.trackBall": "no", "hw.gpu.enabled": "yes",
        "hw.gpu.mode": "swiftshader", "hw.camera.back": "emulated", "hw.camera.front": "none",
        "hw.sdCard": "no", "disk.dataPartition.size": "2G", "PlayStore.enabled": "false",
        "image.sysdir.1": str(sdk / "system-images/android-28/default/x86_64") + "/",
        "tag.id": "default", "tag.display": "Default", "target": "android-28",
        "showDeviceFrame": "no", "skin.dynamic": "yes", "skin.name": "1280x720", "skin.path": "1280x720",
    }
    if not (device / "config.ini").exists():
        (device / "config.ini").write_text("".join(f"{k}={v}\n" for k, v in settings.items()), encoding="utf-8")
    (AVD_HOME / f"{AVD_NAME}.ini").write_text(
        f"avd.ini.encoding=UTF-8\npath={device}\ntarget=android-28\n", encoding="utf-8")


def setup(args):
    standard_sdk = (Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk" if IS_WINDOWS
                    else Path.home() / "Android/Sdk")
    default_sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
                       or str(standard_sdk))
    sdk = (args.sdk or default_sdk).resolve()
    java = find_jdk(args.java_home)
    config = {"sdk": str(sdk), "java_home": str(java), "host": HOST}
    tools = sdk / "cmdline-tools" / "latest"
    if not (tools / "bin" / executable("android")).exists():
        filename, checksum = SDK_DOWNLOADS[HOST]
        archive = WORK / "downloads" / filename
        download("https://dl.google.com/android/repository/" + filename, archive, checksum, "sha1")
        extract(archive, tools, "cmdline-tools")
    packages = {
        "platform-tools": sdk / "platform-tools" / executable("adb"),
        "platforms/android-36": sdk / "platforms/android-36/android.jar",
        "build-tools/36.0.0": sdk / "build-tools/36.0.0/lib/d8.jar",
        "emulator": sdk / "emulator" / executable("emulator"),
        "system-images/android-28/default/x86_64": sdk / "system-images/android-28/default/x86_64/system.img",
    }
    for package, marker in packages.items():
        if not marker.exists():
            run([tools / "bin" / executable("android"), f"--sdk={sdk}", "sdk", "install", package], config,
                input="y\n" * 100, text=True)
            if not marker.exists():
                raise RuntimeError(f"SDK package was not installed: {package}")
    configure_avd(sdk)
    CONFIG.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    write_sdk_location(config)
    doctor(config, require_acceleration=False)
    python = "python" if IS_WINDOWS else "python3"
    print(f"Tools installed. Start the interactive emulator and app with: {python} dev.py run", flush=True)


def write_sdk_location(config):
    sdk = Path(config["sdk"])
    properties = ROOT / "player/local.properties"
    lines = properties.read_text(encoding="utf-8").splitlines() if properties.exists() else []
    lines = [line for line in lines if not line.startswith("sdk.dir=")]
    properties.write_text("\n".join(lines + [f"sdk.dir={sdk.as_posix()}"]) + "\n", encoding="utf-8")


def build(config):
    build_recorder(config)
    write_sdk_location(config)
    run([Path(config["java_home"]) / "bin" / executable("java"), "-cp", "gradle/wrapper/gradle-wrapper.jar",
         "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--project-cache-dir", WORK / "gradle-project",
         f"-PdashcamDevBuildDir={PLAYER_BUILD}", "assembleDebug"], config, cwd=ROOT / "player")
    shutil.copy2(PLAYER_BUILD / "outputs/apk/debug/app-debug.apk", DEBUG_APK)


def build_recorder(config):
    sdk = Path(config["sdk"])
    java = Path(config["java_home"]) / "bin"
    android = sdk / "platforms/android-36/android.jar"
    output = WORK / "dashcam.jar"
    with tempfile.TemporaryDirectory(prefix="recorder-build-", dir=WORK) as temporary:
        stubs, classes = (Path(temporary) / name for name in ("stubs", "classes"))
        stubs.mkdir()
        classes.mkdir()
        for directory, destination in (("stubs", stubs), ("src", classes)):
            run([java / executable("javac"), "-source", "8", "-target", "8", "-Xlint:-options",
                 "-bootclasspath", android, "-cp", stubs, "-d", destination,
                 *sorted((ROOT / directory).rglob("*.java"))], config)
        run([java / executable("java"), "-cp", sdk / "build-tools/36.0.0/lib/d8.jar",
             "com.android.tools.r8.D8", "--min-api", "19", "--lib", android,
             "--classpath", stubs, "--output", output, *sorted(classes.rglob("*.class"))], config)
    print(f"Built recorder: {output}", flush=True)


def verify_emulator(config):
    name = adb(config, "emu", "avd", "name", capture_output=True, text=True, timeout=10).stdout.splitlines()
    if not name or name[0].strip() == "OK":
        raise subprocess.CalledProcessError(1, "adb emu avd name", "Emulator is shutting down")
    if name[0].strip() != AVD_NAME:
        raise RuntimeError(f"{SERIAL} belongs to another AVD; only {AVD_NAME} is managed by this script")


def connected_devices(config):
    result = run([Path(config["sdk"]) / "platform-tools" / executable("adb"), "devices"], config,
                 capture_output=True, text=True, timeout=10)
    return {line.split()[0]: line.split()[1] for line in result.stdout.splitlines()[1:] if len(line.split()) >= 2}


def start_emulator(config):
    process = None
    if SERIAL not in connected_devices(config):
        if not IS_WINDOWS and not os.environ.get("DISPLAY"):
            raise RuntimeError("The interactive Linux emulator needs an X11/XWayland desktop (DISPLAY). "
                               "Build and test commands can run without a display.")
        check_acceleration(config)
        log = WORK / "emulator.stdout.log"
        process_options = ({"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS}
                           if IS_WINDOWS else {"start_new_session": True})
        emulator_env = environment(config)
        if not IS_WINDOWS:
            emulator_env.setdefault("QT_QPA_PLATFORM", "xcb")
        with log.open("ab") as stdout, (WORK / "emulator.stderr.log").open("ab") as stderr:
            process = subprocess.Popen([
                str(Path(config["sdk"]) / "emulator" / executable("emulator")), "-avd", AVD_NAME,
                "-port", str(EMULATOR_PORT), "-no-boot-anim", "-no-snapshot", "-gpu", "swiftshader", "-no-audio",
            ], env=emulator_env, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, **process_options)
        print(f"Opening the emulator window. Logs: {log}", flush=True)
    elif connected_devices(config).get(SERIAL) == "device":
        verify_emulator(config)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Emulator exited with code {process.returncode}. "
                               f"Inspect {WORK / 'emulator.stderr.log'} and emulator.stdout.log")
        try:
            boot = adb(config, "shell", "getprop", "sys.boot_completed", capture_output=True,
                       text=True, timeout=10)
            if boot.stdout.strip() == "1":
                verify_emulator(config)
                adb(config, "shell", "input", "keyevent", "82", stdout=subprocess.DEVNULL)
                return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        print("Waiting for Android to finish booting...", flush=True)
        time.sleep(5)
    raise RuntimeError(f"Emulator did not boot in four minutes. Inspect {WORK / 'emulator.stderr.log'}")


def demo(config):
    verify_emulator(config)
    adb(config, "root")
    adb(config, "wait-for-device", timeout=60)
    # This is a dedicated, rootable AVD. Relax SELinux only here so the original
    # car app can read the recorder's /data/nemesis status files on stock Android.
    adb(config, "shell", "setenforce", "0")
    adb(config, "shell", "mkdir -p /data/nemesis/dashcam /sdcard/Dashcam/Saved")
    adb(config, "shell", "chmod 755 /data/nemesis /data/nemesis/dashcam")
    # Use the emulator's own time zone so Library.java's timestamp parsing agrees.
    today = adb(config, "shell", "date", "+%Y%m%d", capture_output=True, text=True).stdout.strip()
    for stamp in ("090000", "090012", "100000"):
        remote = f"/sdcard/Dashcam/{today}_{stamp}.ts"
        # adb push replaces only these synthetic fixtures; saved clips are left alone.
        adb(config, "push", ROOT / "dev/sample.ts", remote, stdout=subprocess.DEVNULL)
    adb(config, "push", ROOT / "dev/recorder-demo.sh", "/data/local/tmp/dashcam-dev-recorder.sh",
        stdout=subprocess.DEVNULL)
    adb(config, "shell", "nohup sh /data/local/tmp/dashcam-dev-recorder.sh "
        ">/data/local/tmp/dashcam-dev-recorder.log 2>&1 </dev/null &")
    print("Sample footage and recorder simulator are ready (no camera recording).", flush=True)


def check_acceleration(config, required=True):
    result = run([Path(config["sdk"]) / "emulator" / executable("emulator"), "-accel-check"], config, check=False)
    if result.returncode:
        message = ("Linux emulator acceleration requires a usable /dev/kvm. Check KVM support and your kvm group membership."
                   if not IS_WINDOWS else "Enable Windows Hypervisor Platform and CPU virtualization for the emulator.")
        if required:
            raise RuntimeError(message)
        print(message + " Build and test commands are still available.", file=sys.stderr)


def doctor(config, require_acceleration=True):
    print(f"JDK: {config['java_home']}\nSDK: {config['sdk']}\nAVD: {AVD_HOME / (AVD_NAME + '.avd')}\nADB: {SERIAL}", flush=True)
    run([Path(config["java_home"]) / "bin" / executable("java"), "-version"], config)
    check_acceleration(config, required=require_acceleration)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    setup_parser = commands.add_parser("setup", help="Install SDK tools, a JDK if needed, and the AVD")
    setup_parser.add_argument("--sdk", type=Path, help="Android SDK directory (defaults to the standard SDK for this OS)")
    setup_parser.add_argument("--java-home", type=Path, help="Use an existing JDK 17+")
    launch = commands.add_parser("run", help="Build, open the emulator window, install, and launch")
    launch.add_argument("--no-build", action="store_true", help="Use the last debug APK")
    launch.add_argument("--no-demo", action="store_true", help="Skip sample footage and recorder simulation")
    for command, help_text in {
        "build": "Build the recorder JAR and debug APK", "build-recorder": "Build the recorder JAR only",
        "test": "Compile the recorder and run JVM encoder checks",
        "logs": "Stream emulator logcat", "stop": "Stop this project's emulator", "doctor": "Check tools and acceleration",
    }.items():
        commands.add_parser(command, help=help_text)
    args = parser.parse_args()
    if sys.platform not in ("win32", "linux") or platform.machine().lower() not in ("amd64", "x86_64"):
        parser.error("This launcher supports Windows and Linux on x86_64")
    if args.command == "setup":
        setup(args)
        return
    if not CONFIG.exists():
        parser.error("Run " + ("python" if IS_WINDOWS else "python3") + " dev.py setup first")
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    if config.get("host", HOST) != HOST:
        parser.error("Development configuration belongs to another OS; run setup on this OS")
    if args.command == "build":
        build(config)
    elif args.command == "build-recorder":
        build_recorder(config)
    elif args.command == "test":
        run([sys.executable, "-m", "unittest", "discover", "-s", ROOT / "tests", "-p", "test_dev.py"], config, cwd=ROOT)
        run([sys.executable, ROOT / "tests/run.py", "--android-jar",
             Path(config["sdk"]) / "platforms/android-36/android.jar", "--java-home", config["java_home"]], config)
    elif args.command == "doctor":
        doctor(config)
    elif args.command in ("logs", "stop"):
        verify_emulator(config)
        adb(config, *(["logcat"] if args.command == "logs" else ["emu", "kill"]))
        if args.command == "stop":
            deadline = time.monotonic() + 30
            while SERIAL in connected_devices(config):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Emulator is still shutting down; retry in a few seconds")
                time.sleep(0.5)
            # ADB disconnects just before QEMU releases its AVD file locks.
            time.sleep(2)
    elif args.command == "run":
        if not args.no_build:
            build(config)
        apk = DEBUG_APK
        if not apk.exists():
            raise RuntimeError("No debug APK cached for this OS; run dev.py build with your Python interpreter")
        start_emulator(config)
        adb(config, "install", "-r", apk)
        if not args.no_demo:
            demo(config)
        adb(config, "shell", "am", "force-stop", PACKAGE)
        adb(config, "shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        print("Dashcam is open. Use the emulator window with your mouse and keyboard.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        pass
    except (OSError, RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        print(f"Development environment: {error}", file=sys.stderr)
        sys.exit(1)
