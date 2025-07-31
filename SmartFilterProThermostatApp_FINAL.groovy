definition(
    name: "SmartFilterPro Thermostat Bridge",
    namespace: "smartfilterpro",
    author: "Eric Hanfman",
    description: "Posts thermostat state to SmartFilterPro backend and Bubble",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    section("Thermostat Device") {
        input "thermostat", "capability.thermostat", title: "Select Thermostat", required: true
    }
    section("SmartFilterPro Settings") {
        input "userId", "text", title: "User ID", required: true
        input "thermostatId", "text", title: "Thermostat ID", required: true
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"
    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"
    unsubscribe()
    initialize()
}

def initialize() {
    subscribe(thermostat, "temperature", handleThermostatUpdate)
    subscribe(thermostat, "thermostatOperatingState", handleThermostatUpdate)
}

def handleThermostatUpdate(evt) {
    def temp = thermostat.currentTemperature
    def state = thermostat.currentThermostatOperatingState
    def isActive = (state != "idle")
    def deviceName = thermostat.displayName
    def vendor = thermostat.getDataValue("manufacturer")

    if (!userId || !thermostatId) {
        log.warn "⚠️ Missing userId or thermostatId. Skipping webhook post."
        return
    }

    // Post to Railway
    def railwayParams = [
        uri: "https://smartfilterpro-hubitat-railway-hubitat.up.railway.app/webhook",
        contentType: "application/json",
        body: [
            userId      : userId,
            thermostatId: thermostatId,
            isActive    : isActive
        ]
    ]

    log.debug "📦 Sending to Railway: ${railwayParams.body}"

    try {
        httpPost(railwayParams) { response ->
            log.debug "✅ Posted to Railway: ${response.status}"
        }
    } catch (e) {
        log.error "❌ Error posting to Railway: $e"
    }

    // Post to Bubble
    def bubbleParams = [
        uri: "https://smartfilterpro-scaling.bubbleapps.io/version-test/api/1.1/wf/hubitat",
        contentType: "application/json",
        body: [
            userId             : userId,
            thermostatId       : thermostatId,
            currentTemperature : temp,
            isActive           : isActive,
            deviceName         : deviceName,
            vendor             : vendor,
            state              : state
        ]
    ]

    try {
        httpPost(bubbleParams) { response ->
            log.debug "✅ Posted to Bubble: ${response.status}"
        }
    } catch (e) {
        log.error "❌ Error posting to Bubble: $e"
    }
}
