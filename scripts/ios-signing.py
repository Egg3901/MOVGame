"""Install ephemeral Apple signing inputs and configure only the app target."""
import base64
import datetime
import hashlib
import os
import plistlib
import re
import secrets
import sys
import subprocess
import tempfile
from pathlib import Path


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True)
    if result.returncode:
        # Mask installed identity metadata before invoking this helper.
        raise RuntimeError(f"{args[0]} failed: {result.stderr[-4000:]}")
    return result.stdout


def mask(value):
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print("::add-mask::" + str(value), flush=True)


def main():
    names = ["APPLE_TEAM_ID", "IOS_CERTIFICATE", "IOS_CERTIFICATE_PASSWORD",
             "IOS_MOBILE_PROVISION", "IOS_BUNDLE_ID", "IOS_PROJECT", "IOS_APP_TARGET"]
    missing = [name for name in names if not os.environ.get(name)]
    if missing:
        raise SystemExit("Missing signing inputs: " + ", ".join(missing))
    team = os.environ["APPLE_TEAM_ID"]
    bundle = os.environ["IOS_BUNDLE_ID"]
    if not re.fullmatch(r"[A-Z0-9]{10}", team):
        raise SystemExit("Invalid Apple team identifier")
    temp = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
    profile_file = temp / "app.mobileprovision"
    profile_file.write_bytes(base64.b64decode(os.environ["IOS_MOBILE_PROVISION"], validate=True))
    profile_file.chmod(0o600)
    profile = plistlib.loads(run("security", "cms", "-D", "-i", str(profile_file)).encode())
    entitlement = profile.get("Entitlements", {})
    if profile.get("TeamIdentifier") != [team] or entitlement.get("application-identifier") != f"{team}.{bundle}":
        raise SystemExit("Profile does not match this team and bundle")
    if profile.get("ProvisionedDevices") or profile.get("ProvisionsAllDevices") or entitlement.get("get-task-allow"):
        raise SystemExit("An App Store distribution profile is required")
    if profile["ExpirationDate"].replace(tzinfo=datetime.timezone.utc) <= datetime.datetime.now(datetime.timezone.utc):
        raise SystemExit("Profile expired")
    certificates = profile.get("DeveloperCertificates", [])
    if len(certificates) != 1:
        raise SystemExit("Expected a profile with one distribution certificate")
    fingerprint = hashlib.sha1(certificates[0]).hexdigest().upper()
    uuid = profile["UUID"]
    for value in [team, fingerprint, uuid, profile.get("Name", ""), profile.get("TeamName", "")]:
        if value:
            mask(value)
    cert = temp / "distribution.p12"
    cert.write_bytes(base64.b64decode(os.environ["IOS_CERTIFICATE"], validate=True))
    cert.chmod(0o600)
    keychain = temp / "app-signing.keychain-db"
    password = secrets.token_urlsafe(32)
    mask(password)
    run("security", "create-keychain", "-p", password, str(keychain))
    run("security", "set-keychain-settings", "-lut", "21600", str(keychain))
    run("security", "unlock-keychain", "-p", password, str(keychain))
    run("security", "import", str(cert), "-k", str(keychain), "-P", os.environ["IOS_CERTIFICATE_PASSWORD"], "-T", "/usr/bin/codesign", "-T", "/usr/bin/security")
    run("security", "set-key-partition-list", "-S", "apple-tool:,apple:,codesign:", "-s", "-k", password, str(keychain))
    run("security", "list-keychains", "-d", "user", "-s", str(keychain), str(Path.home() / "Library/Keychains/login.keychain-db"))
    identities = run("security", "find-identity", "-v", "-p", "codesigning", str(keychain))
    for name in re.findall(r'"([^"]+)"', identities):
        mask(name)
    if fingerprint not in identities:
        raise SystemExit("Certificate/private key does not match the profile")
    # Xcode 16+ uses the UserData location; retain the legacy location too.
    for relative in ["Library/MobileDevice/Provisioning Profiles", "Library/Developer/Xcode/UserData/Provisioning Profiles"]:
        folder = Path.home() / relative
        folder.mkdir(parents=True, exist_ok=True)
        destination = folder / (uuid + ".mobileprovision")
        destination.write_bytes(profile_file.read_bytes())
        destination.chmod(0o600)
    os.environ.update(SIGNING_FINGERPRINT=fingerprint, SIGNING_PROFILE_UUID=uuid)
    ruby = r'''
require 'xcodeproj'
project = Xcodeproj::Project.open(ENV.fetch('IOS_PROJECT'))
target = project.targets.find { |t| t.name == ENV.fetch('IOS_APP_TARGET') }
abort 'App target not found' unless target
abort 'Unexpected bundle identifier' unless target.build_configurations.all? { |c| c.build_settings['PRODUCT_BUNDLE_IDENTIFIER'] == ENV.fetch('IOS_BUNDLE_ID') }
target.build_configurations.each do |config|
  settings = config.build_settings
  settings['CODE_SIGN_STYLE'] = 'Manual'
  settings['DEVELOPMENT_TEAM'] = ENV.fetch('APPLE_TEAM_ID')
  settings['CODE_SIGN_IDENTITY'] = ENV.fetch('SIGNING_FINGERPRINT')
  settings['CODE_SIGN_IDENTITY[sdk=iphoneos*]'] = ENV.fetch('SIGNING_FINGERPRINT')
  settings['PROVISIONING_PROFILE_SPECIFIER'] = ENV.fetch('SIGNING_PROFILE_UUID')
  settings['PROVISIONING_PROFILE_SPECIFIER[sdk=iphoneos*]'] = ENV.fetch('SIGNING_PROFILE_UUID')
end
project.save
'''
    run("ruby", "-e", ruby)
    options = {"method": "app-store-connect", "teamID": team,
               "signingStyle": "manual", "signingCertificate": fingerprint,
               "provisioningProfiles": {bundle: uuid}, "testFlightInternalTestingOnly": True}
    (temp / "ExportOptions.plist").write_bytes(plistlib.dumps(options))
    cert.unlink()
    profile_file.unlink()
    print("App Store profile and matching private key installed")


if __name__ == "__main__":
    if "--cleanup" in sys.argv:
        temp = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir()))
        keychain = temp / "app-signing.keychain-db"
        if keychain.exists():
            subprocess.run(["security", "delete-keychain", str(keychain)], capture_output=True)
        options = temp / "ExportOptions.plist"
        if options.exists():
            for uuid in plistlib.loads(options.read_bytes())["provisioningProfiles"].values():
                for relative in ["Library/MobileDevice/Provisioning Profiles", "Library/Developer/Xcode/UserData/Provisioning Profiles"]:
                    (Path.home() / relative / (uuid + ".mobileprovision")).unlink(missing_ok=True)
        for name in ["app.mobileprovision", "distribution.p12", "ExportOptions.plist"]:
            (temp / name).unlink(missing_ok=True)
    else:
        main()
