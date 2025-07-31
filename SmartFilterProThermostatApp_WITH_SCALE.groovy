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
    def rawTemp = thermostat.currentTemperature
    def scale = location.temperatureScale
    def tempF = (scale == "C") ? (rawTemp * 9 / 5) + 32 : rawTemp

    def state = thermostat.currentThermostatOperatingState
    def mode = thermostat.currentThermostatMode
    def isActive = (state != "idle")
    def deviceName = thermostat.displayName
    def vendor = thermostat.getDataValue("manufacturer")
    def timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ssXXX", location.timeZone)

    if (!userId || !thermostatId) {
        log.warn "⚠️ Missing userId or thermostatId. Skipping webhook post."
        return
    }

    // Post to Railway
    def railwayParams = [
        uri: "https://smartfilterpro-hubitat-railway-hubitat.up.railway.app/webhook",
        contentType: "application/json",
        requestContentType: "application/json",
        body: [
            userId      : userId,
            thermostatId: thermostatId,
            isActive    : isActive,
            timestamp   : timestamp
        ]
    ]

    log.debug "📤 Sending to Railway with body: ${railwayParams.body}"

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
        requestContentType: "application/json",
        body: [
            userId             : userId,
            thermostatId       : thermostatId,
            currentTemperature : tempF,
            isActive           : isActive,
            deviceName         : deviceName,
            vendor             : vendor,
            thermostatMode     : mode,
            temperatureScale   : scale
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