metadata {
    definition(name: "SmartFilterPro Status Sensor", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "Sensor"
        capability "Refresh"

        // Attributes matching Bubble API response
        attribute "filterHealth", "NUMBER"          // Filter health percentage (0-100)
        attribute "minutesActive", "NUMBER"         // Total minutes HVAC has been active
        attribute "deviceName", "STRING"            // Name of the thermostat
        attribute "lastUpdated", "STRING"           // Last update timestamp from Bubble

        // Additional useful attributes
        attribute "hoursActive", "NUMBER"           // Total hours (minutesActive / 60)
        attribute "lastRefresh", "STRING"           // When Hubitat last polled
    }
}

def refresh() {
    parent?.pollBubbleStatus()
}

def updateStatus(Map status) {
    if (status == null) {
        log.warn "updateStatus called with null status"
        return
    }

    if (status.filterHealth != null) {
        sendEvent(name: "filterHealth", value: status.filterHealth, unit: "%")
    }

    if (status.minutesActive != null) {
        sendEvent(name: "minutesActive", value: status.minutesActive, unit: "min")
        sendEvent(name: "hoursActive", value: (status.minutesActive / 60).round(2), unit: "hr")
    }

    if (status.deviceName != null) {
        sendEvent(name: "deviceName", value: status.deviceName)
    }

    if (status.lastUpdated != null) {
        sendEvent(name: "lastUpdated", value: status.lastUpdated)
    }

    sendEvent(name: "lastRefresh", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone))
}
