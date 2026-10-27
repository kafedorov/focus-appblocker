import base64
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import ci_release as ci


class ReleaseChecks(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "app").mkdir()
        self.gradle = self.root / "app/build.gradle"
        self.gradle.write_text("android {\n defaultConfig {\n applicationId 'ru.focusguard'\n versionCode 7\n versionName '0.1.6'\n }\n}\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_manual_build_accepts_current_version(self):
        self.assertEqual(ci.metadata(self.root, "branch", "main"), ("0.1.6", 7))

    def test_matching_release_tag(self):
        self.assertEqual(ci.metadata(self.root, "tag", "v0.1.6"), ("0.1.6", 7))

    def test_wrong_or_malicious_tag_rejected(self):
        for tag in ("v0.1.5", "0.1.6", "v0.1.6\nversion=evil", "v0.1.6-beta"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                ci.metadata(self.root, "tag", tag)

    def test_invalid_package_and_version_rejected(self):
        for before, after in (("ru.focusguard", "other.app"), ("versionCode 7", "versionCode 0"),
                              ("0.1.6", "../../evil"), ("versionName", "otherName")):
            old = self.gradle.read_text(encoding="utf-8")
            self.gradle.write_text(old.replace(before, after), encoding="utf-8")
            with self.subTest(value=after), self.assertRaises(ValueError):
                ci.metadata(self.root)
            self.gradle.write_text(old, encoding="utf-8")

    def test_expected_certificate(self):
        colon = ":".join(ci.EXPECTED_CERT_SHA256[i:i+2].upper() for i in range(0, 64, 2))
        self.assertEqual(ci.key_fingerprint("SHA256: " + colon), ci.EXPECTED_CERT_SHA256)
        ci.verify_signers("Signer #1 certificate SHA-256 digest: " + ci.EXPECTED_CERT_SHA256)

    def test_wrong_missing_or_multiple_signers(self):
        for text in ("", "Signer #1 certificate SHA-256 digest: " + "a" * 64,
                     ("Signer #1 certificate SHA-256 digest: " + ci.EXPECTED_CERT_SHA256 + "\n") * 2):
            with self.subTest(text=text[:40]), self.assertRaises(ValueError):
                ci.verify_signers(text)
        with self.assertRaises(ValueError):
            ci.key_fingerprint("SHA256: " + ":".join(["AA"] * 32))

    def test_apk_version_must_match_source(self):
        ci.verify_badging("package: name='ru.focusguard' versionCode='7' versionName='0.1.6'", "0.1.6", 7)
        for name, code, version in (("other.app", 7, "0.1.6"), ("ru.focusguard", 6, "0.1.6"), ("ru.focusguard", 7, "0.1.5")):
            with self.subTest(version=version, code=code), self.assertRaises(ValueError):
                ci.verify_badging(f"package: name='{name}' versionCode='{code}' versionName='{version}'", "0.1.6", 7)

    def test_missing_or_invalid_secret_does_not_create_key(self):
        path = self.root / "key/debug.keystore"
        for secret in ("", "not valid base64!", base64.b64encode(b"").decode()):
            with self.subTest(secret=secret), self.assertRaises(ValueError):
                ci.restore_key(secret, path)
            self.assertFalse(path.exists())

    def test_rejected_key_is_removed(self):
        path = self.root / "key/debug.keystore"
        with patch.object(ci, "check_keystore", side_effect=ValueError("Wrong certificate")):
            with self.assertRaises(ValueError):
                ci.restore_key(base64.b64encode(b"sample key bytes").decode(), path)
        self.assertFalse(path.exists())

    def test_local_cli_cannot_remove_the_user_key(self):
        with patch.dict(ci.os.environ, {}, clear=True), patch.object(ci.sys, "argv", ["ci_release.py", "cleanup-key"]):
            with self.assertRaises(ValueError):
                ci.main()

    def test_validated_key_is_retained(self):
        path = self.root / "key/debug.keystore"
        with patch.object(ci, "check_keystore", return_value=ci.EXPECTED_CERT_SHA256) as verify:
            ci.restore_key(base64.b64encode(b"sample key bytes").decode(), path)
        self.assertEqual(path.read_bytes(), b"sample key bytes")
        verify.assert_called_once_with(path)


if __name__ == "__main__":
    unittest.main()
