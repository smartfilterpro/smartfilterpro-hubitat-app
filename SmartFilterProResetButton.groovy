metadata {
    definition(name: "SmartFilterPro Reset Button", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "Actuator"
        capability "PushableButton"   // single, standard button capability
        attribute "lastReset", "STRING"
    }
}

def installed() { sendEvent(name: "numberOfButtons", value: 1) }
def updated()   { sendEvent(name: "numberOfButtons", value: 1) }

// Hubitat will call this for Dashboard/Button and Rule Machine
def push(Integer buttonNumber = 1) {
    parent?.resetNow()
    sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
    sendEvent(name: "lastReset", value: new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone))
}

// Optional: keep these if you want to use the Switch tile instead of Button
def on()  { push(1) }
def off() { }   // no-op
