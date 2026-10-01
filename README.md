# SmartFilterPro Thermostat Bridge (Hubitat)

This is a custom Hubitat app that tracks HVAC runtime based on actual thermostat state and sends session data to the SmartFilterPro backend. Ideal for use cases where HVAC filter replacement is automated based on real system usage rather than calendar intervals.

## 🌟 Features

- Tracks runtime using `thermostatOperatingState` (heating, cooling, fan only)
- Sends real-time updates to Bubble.io and/or Railway
- Posts runtime session data (in seconds) on each stop event
- Compatible with Ecobee, Sensi, and most Hubitat-connected thermostats

---

## 🖥️ Installation Guide

### Recommended: Hubitat Package Manager

1. Install [Hubitat Package Manager](https://hubitatpackagemanager.hubitatcommunity.com/) (HPM) if you don't have it.
2. In HPM choose **Install → From a URL** and enter:

   ```
   https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/packageManifest.json
   ```

3. HPM installs the app and both drivers, and offers updates whenever a new version is published. The app also shows an "update available" banner and can send a push notification.

**Beta builds (for testers only):** in HPM → *Settings*, turn on **Install beta versions**, then update the SmartFilterPro package. Beta builds come from the `dev` branch and may be unfinished; turn the setting off to go back to the stable release.

**Test environment (for testers only):** the app's *Options* section has **Use Test Environment**. It routes both the SmartFilterPro app calls and the runtime posts to the development environment. Leave it off unless SmartFilterPro support asked you to use it.

### Manual install (alternative)

#### 1. Add App Code in Hubitat

Go to `Apps Code` in your Hubitat admin panel.

Click the green **Add app** button:

<img width="2452" height="1312" alt="image" src="https://github.com/user-attachments/assets/5c2b14e6-ad87-4acc-9b2a-8b6412c26094" />


Click **Import**, paste the app's URL, and click **Save**:

```
https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/SmartFilterProHubitatApp.groovy
```

Do the same under `Drivers Code` for the two drivers (`SmartFilterProResetStatus.groovy` and `SmartFilterProResetButton.groovy` at the same address).

---

#### 2. Add the User App

Go to `Apps` → `+ Add User App` → Select **SmartFilterPro Thermostat Bridge**.

#### 3. Fill Out App Configuration

You'll be prompted to configure the app:

<img width="2460" height="1312" alt="image" src="https://github.com/user-attachments/assets/b502f6f0-0014-4af6-bca5-c67944b9ad95" />


Required fields:
- **Select Thermostat**: Choose your Hubitat-connected thermostat
- **User ID**: Your Bubble app user ID
- **Thermostat ID**: Unique identifier for this thermostat
- **Bubble API Endpoint**: e.g. `https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat`

Other optional fields:
- Enable debug logging for verbose logging
- Session statistics and cleanup control

---

#### 4. Optimize Ecobee Polling (If Applicable)

If you’re using the **Ecobee Integration**, you may need to reduce its poll rate to ensure runtime is tracked with high fidelity.

Update the polling interval to **1 minute**:

<img width="1228" height="659" alt="image" src="https://github.com/user-attachments/assets/75a7c71f-0c79-4701-be60-a9d6c85a0f73" />



> ⚠️ Lower polling intervals may lead to rate limits from Ecobee’s API.

---

## 🔄 Data Format Sent to Bubble

The app posts JSON to the Bubble endpoint with the following:

### On any update:
```json
{
  "userId": "xxx",
  "thermostatId": "yyy",
  "isActive": true,
  "currentTemperature": 72,
  "timestampMillis": 1754192478840
}
```

---

## For maintainers: branches and releases

- `dev` is where changes land first. `main` only receives merges from `dev` once they have been tested on a hub with **Install beta versions** on.
- HPM reads `packageManifest.json` from `main`, and every code `location` in it is pinned to the tag `v<version>`. So a merge to `main` reaches users only when that tag exists, and the *Release* workflow creates it: on every push to `main` it checks that the manifest, `APP_VERSION` in the app and `DRIVER_VERSION` in both drivers agree, then tags the commit and publishes a GitHub Release with the manifest's release notes. If the tag already exists and `main` has drifted from it, the workflow fails: bump the version.
- To release: on `dev`, set the same `X.Y.Z` in `packageManifest.json` (`version`), `SmartFilterProHubitatApp.groovy` (`APP_VERSION`) and both drivers (`DRIVER_VERSION`); point every `location` at `v<X.Y.Z>`; update `dateReleased` and `releaseNotes`; run `python scripts/check_manifest.py`; merge `dev` into `main`.
- Beta channel: the `betaLocation` entries track `dev`. Set `betaVersion` in the manifest on `main` when you want testers notified of a new beta.
- The *Validate* workflow runs `scripts/check_manifest.py` on every push and pull request.
