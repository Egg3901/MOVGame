"""Check App Store metadata before spending time on a signed archive."""
import plistlib
from pathlib import Path

root = Path(__file__).resolve().parent.parent
info = plistlib.loads((root / "apps/native/iosApp/Info.plist").read_bytes())
required = {
    "UIInterfaceOrientationPortrait",
    "UIInterfaceOrientationPortraitUpsideDown",
    "UIInterfaceOrientationLandscapeLeft",
    "UIInterfaceOrientationLandscapeRight",
}
orientations = set(info.get("UISupportedInterfaceOrientations~ipad", info.get("UISupportedInterfaceOrientations", [])))
missing = required - orientations
if missing:
    raise SystemExit("Apple 90474: missing iPad multitasking orientations: " + ", ".join(sorted(missing)))
if info.get("ITSAppUsesNonExemptEncryption") is not False:
    raise SystemExit("The app's standard platform-only encryption declaration is missing")
print("iPad multitasking and encryption metadata verified")
