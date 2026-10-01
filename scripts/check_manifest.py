"""Consistency checks for the Hubitat Package Manager manifest.

Hubitat Package Manager (HPM) reads packageManifest.json from the main
branch: its "version" is what tells a hub an update exists, and each
"location" is where the code is fetched from. The version also lives in the
app (APP_VERSION) and in each driver (DRIVER_VERSION), and the locations are
pinned to the release tag v<version>. Nothing in HPM checks that these agree,
so this script does, in CI and locally:

    python scripts/check_manifest.py

Exit code 0 means consistent.
"""
import json
import pathlib
import re
import sys
from datetime import date

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = "smartfilterpro/smartfilterpro-hubitat-app"
RAW = f"https://raw.githubusercontent.com/{REPO}"
SEMVER = re.compile(r"^\d+\.\d+\.\d+$")

failures = 0


def check(name, cond, detail=""):
    global failures
    if cond:
        print(f"  PASS  {name}")
    else:
        failures += 1
        print(f"  FAIL  {name}{(': ' + detail) if detail else ''}")


def groovy_constant(path, name):
    text = (ROOT / path).read_text()
    m = re.search(rf'@Field\s+static\s+final\s+String\s+{name}\s*=\s*"([^"]+)"', text)
    return m.group(1) if m else None


def semver_tuple(v):
    return tuple(int(x) for x in v.split("."))


manifest = json.loads((ROOT / "packageManifest.json").read_text())
version = manifest.get("version", "")
check("manifest version is semantic (X.Y.Z)", bool(SEMVER.match(version)), repr(version))

check("dateReleased is an ISO date no later than today", (lambda d: bool(d) and date.fromisoformat(d) <= date.today())(manifest.get("dateReleased", "")))
check("releaseNotes present", bool(manifest.get("releaseNotes", "").strip()))

app_version = groovy_constant("SmartFilterProHubitatApp.groovy", "APP_VERSION")
check("APP_VERSION in the app equals the manifest version", app_version == version, f"app={app_version} manifest={version}")

check_url = groovy_constant("SmartFilterProHubitatApp.groovy", "VERSION_CHECK_URL")
check("the in-app update check reads the manifest on main",
      check_url == f"{RAW}/main/packageManifest.json", repr(check_url))

entries = manifest.get("apps", []) + manifest.get("drivers", [])
check("manifest lists the app and both drivers", len(entries) == 3, str(len(entries)))

for entry in entries:
    loc = entry.get("location", "")
    file = loc.rsplit("/", 1)[-1]
    check(f"{entry.get('name')}: location is pinned to tag v{version}",
          loc == f"{RAW}/v{version}/{file}", loc)
    check(f"{entry.get('name')}: {file} exists in the repository", (ROOT / file).is_file())
    beta = entry.get("betaLocation", "")
    check(f"{entry.get('name')}: betaLocation tracks the dev branch", beta == f"{RAW}/dev/{file}", beta)
    if file != "SmartFilterProHubitatApp.groovy":
        dv = groovy_constant(file, "DRIVER_VERSION")
        check(f"{entry.get('name')}: DRIVER_VERSION equals the manifest version", dv == version, f"driver={dv} manifest={version}")
    text = (ROOT / file).read_text() if (ROOT / file).is_file() else ""
    check(f"{entry.get('name')}: importUrl points at the file on main",
          f'importUrl: "{RAW}/main/{file}"' in text)

beta_version = manifest.get("betaVersion")
if beta_version is not None:
    check("betaVersion is semantic", bool(SEMVER.match(beta_version)), repr(beta_version))
    check("betaVersion is not older than version",
          bool(SEMVER.match(beta_version)) and semver_tuple(beta_version) >= semver_tuple(version),
          f"beta={beta_version} version={version}")

ids = [e.get("id") for e in entries]
check("app/driver ids are unique", len(ids) == len(set(ids)))

print("\nAll checks passed" if failures == 0 else f"\n{failures} check(s) failed")
sys.exit(0 if failures == 0 else 1)
