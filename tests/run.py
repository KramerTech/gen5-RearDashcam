"""Compile the recorder and run JVM encoder regression checks (not device encoding).

Usage: python tests/run.py --android-jar /path/to/android.jar [--java-home /path/to/jdk]
Requires a JDK (17+) and an Android SDK platform jar. No test dependencies.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME") or None)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    android = args.android_jar.resolve(strict=True)

    def java_tool(name):
        if args.java_home:
            return str(args.java_home / "bin" / (name + (".exe" if os.name == "nt" else "")))
        return name

    def compile_java(destination, sources, classpath):
        subprocess.run([
            java_tool("javac"), "-source", "8", "-target", "8", "-Xlint:-options",
            "-bootclasspath", str(android), "-cp", str(classpath), "-d", str(destination),
            *map(str, sources),
        ], check=True)

    with tempfile.TemporaryDirectory(prefix="dashcam-encoder-tests-") as work:
        work = Path(work)
        stubs, production, tests = (work / name for name in ("stubs", "production", "tests"))
        for directory in (stubs, production, tests):
            directory.mkdir()
        compile_java(stubs, sorted((root / "stubs").rglob("*.java")), stubs)
        compile_java(production, sorted((root / "src").rglob("*.java")), stubs)
        compile_java(tests, sorted((root / "tests").rglob("*.java")), production)
        # Only the tests use fake codec/log classes. Production compiles against the SDK.
        subprocess.run([
            java_tool("java"), "-cp", os.pathsep.join(map(str, (tests, production, stubs, android))),
            "dev.skramer.dashcam.EncoderTest",
        ], check=True)


if __name__ == "__main__":
    main()
