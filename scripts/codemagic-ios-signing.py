"""Install manual signing for the KMP iOS app on a Codemagic builder.

Reads IOS_MOBILE_PROVISION (base64), IOS_CERTIFICATE (base64 p12) and
IOS_CERTIFICATE_PASSWORD from the mov-signing env group, verifies the
profile belongs to MOV_APPLE_TEAM, installs both, and pins the Xcode
target configs to manual signing. Same shape as AHDNative's
prepare-ios-signing.py, minus the Tauri workaround.
"""
import base64
import hashlib
import os
import plistlib
import re
import subprocess
import sys
import tempfile
from pathlib import Path


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"mov-signing: {message}")


def pin_project_signing(text: str, team: str, fingerprint: str, profile_uuid: str) -> str:
    """Set signing in the two app target buildSettings dictionaries."""
    blocks = re.split(r"(^\t\t[0-9A-F]{24} = \{\n)", text, flags=re.M)
    out = [blocks[0]]
    patched = 0
    settings = (
        f"\t\t\t\tDEVELOPMENT_TEAM = {team};\n"
        f"\t\t\t\tCODE_SIGN_IDENTITY = \"{fingerprint}\";\n"
        f"\t\t\t\t\"CODE_SIGN_IDENTITY[sdk=iphoneos*]\" = \"{fingerprint}\";\n"
        f"\t\t\t\tPROVISIONING_PROFILE_SPECIFIER = \"{profile_uuid}\";\n"
        f"\t\t\t\t\"PROVISIONING_PROFILE_SPECIFIER[sdk=iphoneos*]\" = \"{profile_uuid}\";\n"
    )
    for i in range(1, len(blocks), 2):
        header, body = blocks[i], blocks[i + 1]
        if "PRODUCT_BUNDLE_IDENTIFIER = com.lakesidegames.electioneer;" in body and "isa = XCBuildConfiguration;" in body:
            if not re.search(r"^\t\t\t\tCODE_SIGN_STYLE = Automatic;$", body, flags=re.M):
                fail("expected automatic signing in app target")
            body = re.sub(r"^(\t\t\t\tCODE_SIGN_STYLE = )Automatic;$", r"\1Manual;", body, flags=re.M)
            marker = "\n\t\t\t};\n\t\t\tname = "
            if body.count(marker) != 1:
                fail("expected one app target buildSettings block")
            body = body.replace(marker, "\n" + settings.rstrip("\n") + marker, 1)
            patched += 1
        out += [header, body]
    if patched != 2:
        fail(f"expected to patch 2 target configs, patched {patched}")
    return "".join(out)


def main() -> None:
    team = os.environ.get("MOV_APPLE_TEAM", "")
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        fail("MOV_APPLE_TEAM must be the 10-char team id")
    profile_b64 = os.environ.get("IOS_MOBILE_PROVISION", "")
    cert_b64 = os.environ.get("IOS_CERTIFICATE", "")
    cert_password = os.environ.get("IOS_CERTIFICATE_PASSWORD", "")
    if not profile_b64 or not cert_b64 or not cert_password:
        fail("IOS_MOBILE_PROVISION / IOS_CERTIFICATE / IOS_CERTIFICATE_PASSWORD required")

    profile_bytes = base64.b64decode(profile_b64, validate=True)
    with tempfile.NamedTemporaryFile(suffix=".mobileprovision") as profile_file:
        profile_file.write(profile_bytes)
        profile_file.flush()
        decoded = subprocess.run(
            ["security", "cms", "-D", "-i", profile_file.name],
            capture_output=True,
            check=True,
        )
    profile = plistlib.loads(decoded.stdout)
    if profile.get("TeamIdentifier") != [team]:
        fail("provisioning profile must match MOV_APPLE_TEAM")
    app_id = profile.get("Entitlements", {}).get("application-identifier", "")
    if app_id != f"{team}.com.lakesidegames.electioneer":
        fail(f"profile is for {app_id}, expected {team}.com.lakesidegames.electioneer")
    certificates = profile.get("DeveloperCertificates", [])
    if len(certificates) != 1:
        fail("expected a single-certificate profile")
    fingerprint = hashlib.sha1(certificates[0]).hexdigest().upper()
    profile_uuid = profile["UUID"]
    if not re.fullmatch(r"[A-Fa-f0-9-]{36}", profile_uuid):
        fail("expected a provisioning profile UUID")

    # Install the distribution certificate into a fresh keychain.
    cert_path = Path(tempfile.gettempdir()) / "mov-dist.p12"
    cert_path.write_bytes(base64.b64decode(cert_b64, validate=True))
    cert_path.chmod(0o600)
    keychain = Path.home() / "Library/Keychains/mov-build.keychain-db"
    subprocess.run(["security", "create-keychain", "-p", "mov", str(keychain)], check=True)
    subprocess.run(["security", "set-keychain-settings", str(keychain)], check=True)
    subprocess.run(
        [
            "security", "import", str(cert_path),
            "-k", str(keychain), "-P", cert_password,
            "-T", "/usr/bin/codesign", "-T", "/usr/bin/security",
        ],
        check=True,
    )
    subprocess.run(
        ["security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-s",
         "-k", "mov", str(keychain)],
        check=True,
    )
    subprocess.run(
        ["security", "list-keychains", "-d", "user", "-s", str(keychain),
         str(Path.home() / "Library/Keychains/login.keychain-db")],
        check=True,
    )
    subprocess.run(["security", "unlock-keychain", "-p", "mov", str(keychain)], check=True)

    # Install the provisioning profile where Xcode expects it.
    profiles_dir = Path.home() / "Library/MobileDevice/Provisioning Profiles"
    profiles_dir.mkdir(parents=True, exist_ok=True)
    (profiles_dir / f"{profile_uuid}.mobileprovision").write_bytes(profile_bytes)

    # Pin the app target configs (the ones carrying PRODUCT_BUNDLE_IDENTIFIER)
    # to manual signing with this identity and profile.
    pbx_path = Path("iosApp/MOVGameiOS.xcodeproj/project.pbxproj")
    pbx_path.write_text(pin_project_signing(pbx_path.read_text(), team, fingerprint, profile_uuid))
    print(f"signing pinned: team {team} profile {profile_uuid}")


if __name__ == "__main__":
    sys.exit(main())
