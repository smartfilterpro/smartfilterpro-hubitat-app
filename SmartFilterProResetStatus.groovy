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

def installed() {
    log.info "SmartFilterPro Status Sensor installed"
    refresh()
}

def updated() {
    log.info "SmartFilterPro Status Sensor updated"
}

def refresh() {
    log.info "Refresh triggered - calling parent.pollBubbleStatus()"
    parent?.pollBubbleStatus()
}

def updateStatus(Map status) {
    log.info "updateStatus called with: ${status}"

    if (status == null) {
        log.warn "updateStatus called with null status"
        return
    }

    if (status.filterHealth != null) {
        log.debug "Setting filterHealth: ${status.filterHealth}"
        sendEvent(name: "filterHealth", value: status.filterHealth, unit: "%")
    }

    if (status.minutesActive != null) {
        def hours = (status.minutesActive / 60).round(2)
        log.debug "Setting minutesActive: ${status.minutesActive}, hoursActive: ${hours}"
        sendEvent(name: "minutesActive", value: status.minutesActive, unit: "min")
        sendEvent(name: "hoursActive", value: hours, unit: "hr")
    }

    if (status.deviceName != null) {
        log.debug "Setting deviceName: ${status.deviceName}"
        sendEvent(name: "deviceName", value: status.deviceName)
    }

    if (status.lastUpdated != null) {
        log.debug "Setting lastUpdated: ${status.lastUpdated}"
        sendEvent(name: "lastUpdated", value: status.lastUpdated)
    }

    def refreshTime = new Date().toInstant().toString()
    log.debug "Setting lastRefresh: ${refreshTime}"
    sendEvent(name: "lastRefresh", value: refreshTime)

    log.info "updateStatus completed - all events sent"
}
