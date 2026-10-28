"""Regression checks for native setup, shared checkouts, and emulator startup."""

import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import zipfile

import dev


class DevelopmentTests(unittest.TestCase):
    def test_hosts_have_separate_tools_devices_and_caches(self):
        hosts = {}
        for name in ("win32", "linux"):
            with mock.patch.object(sys, "platform", name):
                hosts[name] = runpy.run_path(str(dev.ROOT / "dev.py"), run_name="dev_host_test")
        for key in ("WORK", "JDK_CACHE", "DEBUG_APK", "PLAYER_BUILD", "AVD_HOME", "AVD_NAME", "SERIAL"):
            self.assertNotEqual(hosts["win32"][key], hosts["linux"][key], key)
        self.assertEqual(hosts["win32"]["executable"]("adb"), "adb.exe")
        self.assertEqual(hosts["linux"]["executable"]("adb"), "adb")

    def test_sdk_zip_preserves_executable_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "sdk.zip"
            entry = zipfile.ZipInfo("cmdline-tools/bin/android")
            entry.create_system = 3
            entry.external_attr = (stat.S_IFREG | 0o755) << 16
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("cmdline-tools/", b"")
                bundle.writestr("cmdline-tools/bin/", b"")
                bundle.writestr(entry, b"#!/bin/sh\nexit 0\n")
            dev.extract(archive, root / "sdk", "cmdline-tools")
            executable = root / "sdk/bin/android"
            self.assertTrue(executable.is_file())
            if os.name != "nt":
                self.assertTrue(os.access(executable, os.X_OK))
                subprocess.run([str(executable)], check=True)

    def test_sdk_zip_cannot_escape_destination(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "sdk.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                bundle.writestr("cmdline-tools/../../outside", b"bad")
            with self.assertRaises(RuntimeError):
                dev.extract(archive, root / "sdk", "cmdline-tools")
            self.assertFalse((root / "outside").exists())

    def test_linux_jdk_tar_extracts_executables(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "jdk.tar.gz"
            entry = tarfile.TarInfo("jdk/bin/java")
            content = b"#!/bin/sh\nexit 0\n"
            entry.size, entry.mode = len(content), 0o755
            with tarfile.open(archive, "w:gz") as bundle:
                bundle.addfile(entry, io.BytesIO(content))
            dev.extract(archive, root / "jdk")
            executable = root / "jdk/jdk/bin/java"
            self.assertEqual(executable.read_bytes(), content)
            if os.name != "nt":
                subprocess.run([str(executable)], check=True)

    def test_linux_jdk_tar_rejects_external_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "jdk.tar.gz"
            entry = tarfile.TarInfo("jdk/link")
            entry.type, entry.linkname = tarfile.SYMTYPE, "../../outside"
            with tarfile.open(archive, "w:gz") as bundle:
                bundle.addfile(entry)
            with self.assertRaises(tarfile.FilterError):
                dev.extract(archive, root / "jdk")
            self.assertFalse((root / "outside").exists())

    def test_shared_checkout_updates_only_sdk_property(self):
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(dev, "ROOT", Path(temporary)):
            properties = dev.ROOT / "player/local.properties"
            properties.parent.mkdir()
            properties.write_text("sdk.dir=old\nother.setting=preserve\n", encoding="utf-8")
            for sdk in (dev.ROOT / "windows-sdk", dev.ROOT / "linux-sdk"):
                dev.write_sdk_location({"sdk": str(sdk)})
                self.assertEqual(properties.read_text(encoding="utf-8").splitlines(),
                                 ["other.setting=preserve", "sdk.dir=" + sdk.as_posix()])

    def test_linux_emulator_detaches_without_windows_flags(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(mock.patch.object(dev, "IS_WINDOWS", False))
            stack.enter_context(mock.patch.object(dev, "WORK", Path(temporary)))
            stack.enter_context(mock.patch.dict(os.environ, {"DISPLAY": ":0"}, clear=True))
            stack.enter_context(mock.patch.object(dev, "connected_devices", return_value={}))
            stack.enter_context(mock.patch.object(dev, "check_acceleration"))
            stack.enter_context(mock.patch.object(dev, "verify_emulator"))
            stack.enter_context(mock.patch.object(dev, "adb", return_value=subprocess.CompletedProcess([], 0, "1\n")))
            popen = stack.enter_context(mock.patch.object(dev.subprocess, "Popen"))
            popen.return_value.poll.return_value = None
            dev.start_emulator({"sdk": temporary, "java_home": temporary})
            arguments, options = popen.call_args
            self.assertEqual(Path(arguments[0][0]).name, "emulator")
            self.assertTrue(options["start_new_session"])
            self.assertNotIn("creationflags", options)
            self.assertEqual(options["env"]["QT_QPA_PLATFORM"], "xcb")

    def test_linux_emulator_requires_desktop(self):
        with mock.patch.object(dev, "IS_WINDOWS", False), mock.patch.dict(os.environ, {}, clear=True), \
                mock.patch.object(dev, "connected_devices", return_value={}):
            with self.assertRaisesRegex(RuntimeError, "DISPLAY"):
                dev.start_emulator({})

    def test_emulator_exit_reports_failure_immediately(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.ExitStack() as stack:
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(mock.patch.object(dev, "IS_WINDOWS", False))
            stack.enter_context(mock.patch.object(dev, "WORK", Path(temporary)))
            stack.enter_context(mock.patch.dict(os.environ, {"DISPLAY": ":0"}))
            stack.enter_context(mock.patch.object(dev, "connected_devices", return_value={}))
            stack.enter_context(mock.patch.object(dev, "check_acceleration"))
            popen = stack.enter_context(mock.patch.object(dev.subprocess, "Popen"))
            popen.return_value.poll.return_value = 7
            popen.return_value.returncode = 7
            with self.assertRaisesRegex(RuntimeError, "exited with code 7"):
                dev.start_emulator({"sdk": temporary, "java_home": temporary})

    def test_setup_can_finish_without_kvm_but_run_requires_it(self):
        with mock.patch.object(dev, "IS_WINDOWS", False), \
                mock.patch.object(dev, "run", return_value=subprocess.CompletedProcess([], 1)), \
                contextlib.redirect_stderr(io.StringIO()):
            dev.check_acceleration({"sdk": "/sdk"}, required=False)
            with self.assertRaisesRegex(RuntimeError, "/dev/kvm"):
                dev.check_acceleration({"sdk": "/sdk"})

    def test_linux_editor_tasks_use_python3(self):
        tasks = json.loads((dev.ROOT / ".vscode/tasks.json").read_text(encoding="utf-8"))["tasks"]
        self.assertTrue(tasks)
        for task in tasks:
            self.assertEqual(task["linux"]["command"], "python3", task["label"])


if __name__ == "__main__":
    unittest.main()
