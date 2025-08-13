# SmartFilterPro Thermostat Bridge (Hubitat)

This is a custom Hubitat app that tracks HVAC runtime based on actual thermostat state and sends session data to the SmartFilterPro backend. Ideal for use cases where HVAC filter replacement is automated based on real system usage rather than calendar intervals.

## 🌟 Features

- Tracks runtime using `thermostatOperatingState` (heating, cooling, fan only)
- Sends real-time updates to Bubble.io and/or Railway
- Posts runtime session data (in seconds) on each stop event
- Compatible with Ecobee, Sensi, and most Hubitat-connected thermostats

---

## 🖥️ Installation Guide

### 1. Add App Code in Hubitat

Go to `Apps Code` in your Hubitat admin panel.

Click the green **Add app** button:

<img width="2452" height="1312" alt="image" src="https://github.com/user-attachments/assets/5c2b14e6-ad87-4acc-9b2a-8b6412c26094" />


Paste the [Groovy app code](https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/refs/heads/main/SmartFilterProHubitatApp.groovy) into the editor and click **Save**.

---

### 2. Add the User App

Go to `Apps` → `+ Add User App` → Select **SmartFilterPro Thermostat Bridge**.

### 3. Fill Out App Configuration

You'll be prompted to configure the app:

![App config screen](https://github.com/smartfilterpro/smartfilterpro-hubitat-app/assets/your-upload-id/125cb7bf-5409-4251-914f-39b6231592dd.png)

Required fields:
- **Select Thermostat**: Choose your Hubitat-connected thermostat
- **User ID**: Your Bubble app user ID
- **Thermostat ID**: Unique identifier for this thermostat
- **Bubble API Endpoint**: e.g. `https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat`

Other optional fields:
- Enable debug logging for verbose logging
- Session statistics and cleanup control

---

### 4. Optimize Ecobee Polling (If Applicable)

If you’re using the **Ecobee Integration**, you may need to reduce its poll rate to ensure runtime is tracked with high fidelity.

Update the polling interval to **1 minute**:

![Ecobee integration](https://github.com/smartfilterpro/smartfilterpro-hubitat-app/assets/your-upload-id/67dd2d0e-a0e5-4330-8ad1-a7ea8d7a0b75.png)

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
