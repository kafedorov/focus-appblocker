"""CI release preparation. No network calls or publication; GitHub CLI publishes separately."""
import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_CERT_SHA256 = "ce10316317e5a582e6a80e8af2d08e82dffb20a41ed44de6b1853cdc575734c4"
APPLICATION_ID = "ru.focusguard"
BUILD_TOOLS = "35.0.0"


def metadata(project=ROOT, ref_type=None, ref_name=None):
    source = (project / "app/build.gradle").read_text(encoding="utf-8")
    block = re.search(r"\bdefaultConfig\s*\{(.*?)\n\s*\}", source, re.S)
    if not block:
        raise ValueError("Cannot find defaultConfig in app/build.gradle")
    version = re.search(r"""\bversionName\s+['"]([0-9]+\.[0-9]+\.[0-9]+)['"]""", block[1])
    code = re.search(r"\bversionCode\s+([0-9]+)\b", block[1])
    app_id = re.search(r"""\bapplicationId\s+['"]([^'"]+)['"]""", block[1])
    if not version or not code or int(code[1]) < 1 or not app_id or app_id[1] != APPLICATION_ID:
        raise ValueError("Expected ru.focusguard, a numeric versionCode and versionName X.Y.Z")
    if ref_type == "tag" and ref_name != "v" + version[1]:
        raise ValueError("Tag must exactly match app versionName: v" + version[1])
    return version[1], int(code[1])


def command(args):
    result = subprocess.run([str(value) for value in args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace")
    if result.returncode:
        # Do not echo environment variables or key contents into workflow logs.
        raise RuntimeError(Path(str(args[0])).name + " failed; exit code " + str(result.returncode))
    return result.stdout + result.stderr


def java_tool(name):
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / (name + suffix) if java_home else None
    if candidate and candidate.is_file():
        return candidate
    found = shutil.which(name)
    if not found:
        raise ValueError("Java tool not found: " + name)
    return Path(found)


def key_fingerprint(output):
    match = re.search(r"SHA256:\s*([A-Fa-f0-9:]{64,95})", output)
    if not match:
        raise ValueError("Cannot read signing certificate fingerprint")
    fingerprint = match[1].replace(":", "").lower()
    if fingerprint != EXPECTED_CERT_SHA256:
        raise ValueError("Signing certificate differs from the installed FocusGuard app")
    return fingerprint


def check_keystore(path):
    output = command([java_tool("keytool"), "-J-Duser.language=en", "-J-Duser.country=US",
                      "-list", "-v", "-keystore", path, "-storepass", "android",
                      "-alias", "androiddebugkey"])
    return key_fingerprint(output)


def restore_key(secret, path):
    if not secret:
        raise ValueError("Add the FOCUSGUARD_KEYSTORE_BASE64 repository secret first; see docs/RELEASES.md")
    try:
        data = base64.b64decode(secret.strip(), validate=True)
    except (binascii.Error, ValueError):
        raise ValueError("Signing secret must contain valid Base64") from None
    if not data or len(data) > 32_000:
        raise ValueError("Signing secret has an unexpected size")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    path.chmod(0o600)
    try:
        check_keystore(path)
    except Exception:
        path.unlink(missing_ok=True)
        raise


def verify_signers(output):
    fingerprints = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", output)
    if len(fingerprints) != 1 or fingerprints[0].lower() != EXPECTED_CERT_SHA256:
        raise ValueError("APK must have the existing FocusGuard signing certificate")


def verify_badging(output, version, code):
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", output)
    if not match or (match[1], int(match[2]), match[3]) != (APPLICATION_ID, code, version):
        raise ValueError("Built APK package/version does not match app/build.gradle")


def package(apk, sdk, project=ROOT, destination=None, ref_type=None, ref_name=None):
    version, code = metadata(project, ref_type, ref_name)
    if not sdk:
        raise ValueError("ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK")
    tools = Path(sdk) / "build-tools" / BUILD_TOOLS
    signer = tools / "lib/apksigner.jar"
    aapt = tools / ("aapt2.exe" if os.name == "nt" else "aapt2")
    if not apk.is_file() or not signer.is_file() or not aapt.is_file():
        raise ValueError("APK or Android Build Tools 35.0.0 are missing")
    verify_signers(command([java_tool("java"), "-jar", signer, "verify", "--print-certs", apk]))
    verify_badging(command([aapt, "dump", "badging", apk]), version, code)
    with zipfile.ZipFile(apk) as archive:
        if archive.read("assets/nsfw-domains.txt") != (project / "app/src/main/assets/nsfw-domains.txt").read_bytes():
            raise ValueError("Packaged domain list differs from source")
    report = project / "app/build/reports/lint-results-debug.xml"
    if not report.is_file():
        raise ValueError("Run lintDebug before packaging")
    if any(issue.get("severity") in {"Error", "Fatal"} for issue in ET.parse(report).findall(".//issue")):
        raise ValueError("Android lint reported errors")
    destination = destination or project / "dist"
    destination.mkdir(parents=True, exist_ok=True)
    target = destination / ("FocusGuard-" + version + "-debug.apk")
    if apk.resolve() != target.resolve():
        shutil.copyfile(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (destination / (target.name + ".sha256")).write_text(digest + "  " + target.name + "\n", encoding="utf-8")
    notes = ("# Фокус " + version + "\n\n"
             "APK для личного использования, вариант debug. versionCode: " + str(code) + ".\n\n"
             "Сборка прошла assembleDebug, lintDebug и coreCheck. "
             "Проверены подпись, идентификатор приложения, версия и встроенная база доменов. "
             "Это не заменяет проверку на физическом телефоне.\n\n"
             "Устанавливайте поверх предыдущей версии, чтобы сохранить настройки.\n")
    changelog = project / "CHANGELOG.md"
    if changelog.is_file():
        text = changelog.read_text(encoding="utf-8")
        if text.startswith("# " + version + "\n"):
            notes += "\n" + text[:32_000]
    (destination / "RELEASE_NOTES.md").write_text(notes, encoding="utf-8")
    print("Verified APK: " + target.name)
    print("SHA-256: " + digest)
    return target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("step", choices=["metadata", "restore-key", "cleanup-key", "package"])
    parser.add_argument("--apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--destination", type=Path)
    args = parser.parse_args()
    ref_type, ref_name = os.environ.get("GITHUB_REF_TYPE"), os.environ.get("GITHUB_REF_NAME")
    if args.step in {"restore-key", "cleanup-key"} and os.environ.get("GITHUB_ACTIONS") != "true":
        raise ValueError("Key restoration/cleanup runs only on the temporary GitHub runner")
    key = Path.home() / ".android/debug.keystore"
    if args.step == "metadata":
        version, code = metadata(ROOT, ref_type, ref_name)
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                stream.write("version=" + version + "\n")
        print("Version " + version + "; versionCode " + str(code))
    elif args.step == "restore-key":
        restore_key(os.environ.pop("FOCUSGUARD_KEYSTORE_BASE64", ""), key)
        print("Existing signing certificate verified")
    elif args.step == "cleanup-key":
        key.unlink(missing_ok=True)
    else:
        package(args.apk, args.sdk, destination=args.destination, ref_type=ref_type, ref_name=ref_name)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, zipfile.BadZipFile, ET.ParseError) as error:
        print("Release preparation failed: " + str(error), file=sys.stderr)
        raise SystemExit(1)
