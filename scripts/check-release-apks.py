#!/usr/bin/env python3
"""Check standalone ABI APKs and the universal APK before publishing a release."""

import argparse
from pathlib import Path
import sys
import zipfile

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
REQUIRED_LIBS = ("libaw_server.so", "libaw_sync.so")


def check_apk(path, expected_abis):
    with zipfile.ZipFile(path) as apk:
        files = {entry.filename: entry.file_size for entry in apk.infolist()}
        actual_abis = {name.split("/")[1] for name in files if name.startswith("lib/") and name.endswith(".so")}
        if actual_abis != set(expected_abis):
            raise ValueError(f"{path.name}: expected ABIs {sorted(expected_abis)}, got {sorted(actual_abis)}")
        # Each download must be installable alone, not a config-only split APK.
        required = ["AndroidManifest.xml", "classes.dex"] + [
            f"lib/{abi}/{lib}" for abi in expected_abis for lib in REQUIRED_LIBS
        ]
        for name in required:
            if not files.get(name):
                raise ValueError(f"{path.name}: missing or empty {name}")


def check_release(directory, published=False):
    for abi in ("universal", *ABIS):
        if published:
            name = "aw-android.apk" if abi == "universal" else f"aw-android-{abi}.apk"
        else:
            name = f"mobile-standard-{abi}-release-unsigned.apk"
        path = directory / name
        check_apk(path, ABIS if abi == "universal" else (abi,))
        print(f"OK {name}: {path.stat().st_size / (1024 * 1024):.1f} MiB")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--published", action="store_true", help="Check dist/ filenames after signing/copying")
    args = parser.parse_args()
    try:
        check_release(args.directory, args.published)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Release APK check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
