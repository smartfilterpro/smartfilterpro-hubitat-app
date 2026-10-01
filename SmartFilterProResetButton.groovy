import groovy.transform.Field

// Must equal "version" in packageManifest.json (scripts/check_manifest.py
// enforces it). Recorded on the device as the "driverVersion" data value so
// a hub's installed driver build can be identified.
@Field static final String DRIVER_VERSION = "1.0.8"

metadata {
    definition(name: "SmartFilterPro Reset Button", namespace: "smartfilterpro", author: "Eric Hanfman",
               importUrl: "https://raw.githubusercontent.com/smartfilterpro/smartfilterpro-hubitat-app/main/SmartFilterProResetButton.groovy") {
        capability "Actuator"
        capability "PushableButton"
        attribute "lastReset", "STRING"
    }
}

def installed() {
    log.info "SmartFilterPro Reset Button installed (v${DRIVER_VERSION})"
    updateDataValue("driverVersion", DRIVER_VERSION)
    sendEvent(name: "numberOfButtons", value: 1)
}

def updated() {
    log.info "SmartFilterPro Reset Button updated (v${DRIVER_VERSION})"
    updateDataValue("driverVersion", DRIVER_VERSION)
    sendEvent(name: "numberOfButtons", value: 1)
}

// Hubitat will call this for Dashboard/Button and Rule Machine
// Note: No type on parameter - Hubitat may pass Integer or BigDecimal
def push(buttonNumber = 1) {
    log.info "Reset button pushed (button ${buttonNumber}) - calling parent.resetNow()"

    def result = parent?.resetNow()

    if (result) {
        log.info "✅ Filter reset successful"
        sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
        sendEvent(name: "lastReset", value: new Date().toInstant().toString())
    } else {
        log.warn "⚠️ Filter reset may have failed - check parent app logs"
        sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
    }
}

// Allow use as Switch tile (Dashboard compatibility)
def on()  { push(1) }
def off() { }
