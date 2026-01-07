metadata {
    definition(name: "SmartFilterPro Reset Button", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "Actuator"
        capability "PushableButton"
        attribute "lastReset", "STRING"
    }
}

def installed() {
    log.info "SmartFilterPro Reset Button installed"
    sendEvent(name: "numberOfButtons", value: 1)
}

def updated() {
    log.info "SmartFilterPro Reset Button updated"
    sendEvent(name: "numberOfButtons", value: 1)
}

// Hubitat will call this for Dashboard/Button and Rule Machine
def push(Integer buttonNumber = 1) {
    log.info "Reset button pushed - calling parent.resetNow()"

    def result = parent?.resetNow()

    if (result) {
        log.info "✅ Filter reset successful"
        sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
        sendEvent(name: "lastReset", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone))
    } else {
        log.warn "⚠️ Filter reset may have failed - check parent app logs"
        sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
    }
}

// Allow use as Switch tile (Dashboard compatibility)
def on()  { push(1) }
def off() { }
