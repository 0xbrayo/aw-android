import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("check_release_apks", Path(__file__).with_name("check-release-apks.py"))
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class ReleaseApkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def apk(self, name, abis, omit=(), empty=()):
        path = self.directory / name
        entries = ["AndroidManifest.xml", "classes.dex"] + [
            f"lib/{abi}/{lib}" for abi in abis for lib in checker.REQUIRED_LIBS
        ]
        with zipfile.ZipFile(path, "w") as archive:
            for entry in entries:
                if entry not in omit:
                    archive.writestr(entry, b"" if entry in empty else b"fixture")
        return path

    def test_each_abi_and_universal_are_accepted(self):
        for abi in checker.ABIS:
            checker.check_apk(self.apk(f"{abi}.apk", (abi,)), (abi,))
        checker.check_apk(self.apk("universal.apk", checker.ABIS), checker.ABIS)

    def test_mislabeled_universal_apk_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "expected ABIs"):
            checker.check_apk(self.apk("arm64.apk", checker.ABIS), ("arm64-v8a",))

    def test_universal_missing_an_abi_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "expected ABIs"):
            checker.check_apk(self.apk("universal.apk", ("arm64-v8a",)), checker.ABIS)

    def test_missing_or_empty_sync_library_is_rejected(self):
        entry = "lib/arm64-v8a/libaw_sync.so"
        for kwargs in ({"omit": (entry,)}, {"empty": (entry,)}):
            with self.subTest(kwargs=kwargs), self.assertRaisesRegex(ValueError, "libaw_sync.so"):
                checker.check_apk(self.apk("arm64.apk", ("arm64-v8a",), **kwargs), ("arm64-v8a",))

    def test_config_only_split_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "classes.dex"):
            checker.check_apk(self.apk("config.apk", ("arm64-v8a",), omit=("classes.dex",)), ("arm64-v8a",))

    def test_published_release_requires_all_five_downloads(self):
        self.apk("aw-android.apk", checker.ABIS)
        for abi in checker.ABIS:
            self.apk(f"aw-android-{abi}.apk", (abi,))
        with contextlib.redirect_stdout(io.StringIO()):
            checker.check_release(self.directory, published=True)
            (self.directory / "aw-android-x86.apk").unlink()
            with self.assertRaises(FileNotFoundError):
                checker.check_release(self.directory, published=True)


if __name__ == "__main__":
    unittest.main()
