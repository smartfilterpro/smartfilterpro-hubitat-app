# SmartFilterPro Thermostat Bridge (Hubitat)

This is a custom Hubitat app that tracks HVAC runtime based on actual thermostat state and sends session data to the SmartFilterPro backend. Ideal for use cases where HVAC filter replacement is automated based on real system usage rather than calendar intervals.

## 🌟 Features

- Tracks runtime using `thermostatOperatingState` (heating, cooling, fan only)
- Sign in with SmartFilterPro: Apple, Google or email; the hub never sees your password
- Sends runtime to SmartFilterPro, queued and retried so nothing is lost while the hub is offline
- Reports runtime while equipment runs: every 15 minutes the app checks the thermostat on the hub and posts the runtime since its last report (a "checkpoint"), and each stop or mode change posts only what is left. Long runs (a fan left on for days) count on every day they cover.
- Only counts runtime the hub can confirm: after a hub reboot, or an hour with the thermostat unreachable, an open run is closed at its last confirmation and a new one starts when the thermostat is seen running again
- Compatible with Ecobee, Sensi, and most Hubitat-connected thermostats

---

## ✅ Before you start

- A SmartFilterPro account, with your **Hubitat thermostat added in the SmartFilterPro app**. The hub can only link a thermostat that's already on your account.
- Hubitat platform **2.3.0** or newer, with the thermostat working in Hubitat.

---

## 🖥️ Install

### Recommended: Hubitat Package Manager

1. Install [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) (HPM) if you don't have it. It appears under **Apps**.
2. In HPM choose **Install → From a URL** and enter:

   ```
   https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/packageManifest.json
   ```

3. HPM installs the app and both drivers, enables OAuth for the app, and offers updates under **Update** whenever a new version is published. The app also shows an "update available" banner and can send a push notification.

**Beta builds (for testers only):** in HPM → *Settings*, turn on **Install beta versions**, then update the SmartFilterPro package. Beta builds come from the `dev` branch and may be unfinished; turn the setting off to go back to the stable release.

### Manual install (alternative)

1. Go to `Apps Code` and click **Add app**:

   <img width="2452" height="1312" alt="image" src="https://github.com/user-attachments/assets/5c2b14e6-ad87-4acc-9b2a-8b6412c26094" />

2. Click **Import**, paste the app's URL, and click **Save**:

   ```
   https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/SmartFilterProHubitatApp.groovy
   ```

3. **Enable OAuth:** on the same page click **OAuth → Enable OAuth in App → Update**. Sign-in needs it: SmartFilterPro sends you back to the app through Hubitat's cloud.
4. *(Optional)* Only if you want the status and reset devices: under `Drivers Code`, import and save the two drivers at the same address (`SmartFilterProResetStatus.groovy` and `SmartFilterProResetButton.groovy`). Don't create devices from them yourself; the app creates and links them when you turn on **Create Status Device** / **Create Reset Button**.

With a manual install you update by importing the same URLs again.

---

## 🔗 Set up

1. Go to `Apps` → `+ Add User App` → **SmartFilterPro Thermostat Bridge**.
2. **Thermostat device on this hub**: choose the Hubitat-connected thermostat to track.
3. Under **Account Link**, tap **Sign in with SmartFilterPro**. A browser tab opens smartfilterpro.com.
4. Sign in the way you normally do in the SmartFilterPro app (**Apple, Google or email**), then tap **Allow**.
5. When the tab says **Signed in to SmartFilterPro**, close it and go back to the app page.
6. If your account has more than one Hubitat thermostat, tap **Choose Thermostat** and pick the one this hub controls. It's saved as soon as you pick it, and **Switch Thermostat** stays on the app page if you want to change it later.
7. Click **Done**. The page shows **Linked ✅** with the thermostat's name.

The sign-in link is good for 15 minutes; if it expires, tap the button again. The hub never sees your password: you sign in on smartfilterpro.com, and SmartFilterPro gives the hub its own sign-in. (The old email and password fields were removed in version 1.0.12; hubs linked that way keep working.)

Other options on the app page:
- **Create Status Device / Create Reset Button**: optional child devices showing filter health and resetting filter tracking.
- **Update Notifications**: devices (e.g. the Hubitat mobile app) to notify when an update is available or the hub's sign-in needs renewing.
- **Enable Debug Logging**: verbose logs.
- **Use Test Environment** (testers only): routes both the SmartFilterPro sign-in and the runtime posts to the development environment. Leave it off unless SmartFilterPro support asked you to use it.

### Optimize Ecobee polling (if applicable)

If you're using Hubitat's **Ecobee Integration**, reduce its poll interval so runtime is tracked accurately. Set it to **1 minute**:

<img width="1228" height="659" alt="image" src="https://github.com/user-attachments/assets/75a7c71f-0c79-4701-be60-a9d6c85a0f73" />

> ⚠️ Lower polling intervals may lead to rate limits from Ecobee's API.

---

## 🛠️ Troubleshooting

- **Still asked for an email and password?** You're on a version before 1.0.12. Update in HPM (or re-import the app).
- **"Your SmartFilterPro account has no Hubitat thermostat yet"?** Add your Hubitat thermostat in the SmartFilterPro app, then tap **Sign in with SmartFilterPro** again.
- **The sign-in tab says the link expired, or SmartFilterPro didn't accept the sign-in?** Close it and tap **Sign in with SmartFilterPro** again. Each link works once. The app page shows the reason under **Last attempt**.
- **Blank page after tapping Allow (manual install)?** Check OAuth is enabled for the app (`Apps Code` → SmartFilterPro → **OAuth**).
- **"Sign-in expired" on the app page?** Tap **Sign In Again**. Runtime recorded in the meantime is queued and sent once you're signed in.
- **Which version do I have?** The bottom of the app page shows **SmartFilterPro v…**.

---

## 🔄 What the app sends

The app posts thermostat events (mode changes, runtime checkpoints, telemetry) to SmartFilterPro's Core service at `/ingest/v1/events:batch`, authenticated with a token SmartFilterPro issues for this hub. Events are queued on the hub and retried, so nothing is lost while the hub is offline or signed out. `tests/golden/checkpoint_event.json` shows the exact payload of a runtime checkpoint.

---

## For maintainers: branches and releases

- `dev` is where changes land first. `main` only receives merges from `dev` once they have been tested on a hub with **Install beta versions** on.
- HPM reads `packageManifest.json` from `main`, and every code `location` in it is pinned to the tag `v<version>`. So a merge to `main` reaches users only when that tag exists, and the *Release* workflow creates it: on every push to `main` it checks that the manifest, `APP_VERSION` in the app and `DRIVER_VERSION` in both drivers agree, then tags the commit and publishes a GitHub Release with the manifest's release notes. If the tag already exists and `main` has drifted from it, the workflow fails: bump the version.
- To release: on `dev`, set the same `X.Y.Z` in `packageManifest.json` (`version`), `SmartFilterProHubitatApp.groovy` (`APP_VERSION`) and both drivers (`DRIVER_VERSION`); point every `location` at `v<X.Y.Z>`; update `dateReleased` and `releaseNotes`; run `python scripts/check_manifest.py`; merge `dev` into `main`.
- Beta channel: the `betaLocation` entries track `dev`. Set `betaVersion` in the manifest on `main` when you want testers notified of a new beta.
- The *Validate* workflow runs `scripts/check_manifest.py` on every push and pull request, and the app's tests.
- Tests: `tests/run_tests.sh` runs `tests/runtime_checkpoints_test.groovy` and `tests/sign_in_test.groovy` (sign-in, PKCE and thermostat choice) against the real app source through a small Hubitat stand-in (`tests/HubitatHarness.groovy`: clock, scheduler, state/atomicState, a fake thermostat, and Core's dedup rules). It needs Java and downloads groovy-all 2.4.21 (Hubitat runs Groovy 2.4) on first use. `tests/golden/checkpoint_event.json` pins the checkpoint payload sent to Core; rewrite it with `UPDATE_GOLDEN=1` only when the payload is meant to change.
