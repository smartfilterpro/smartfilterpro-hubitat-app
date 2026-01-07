metadata {
    definition(name: "SmartFilterPro Status Sensor", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "Sensor"
        capability "Refresh"
        attribute "lastUpdated", "STRING"
        attribute "minutesActive", "NUMBER"
        attribute "deviceName", "STRING"
        attribute "filterHealth", "NUMBER"
    }
}
def refresh() { parent?.pollNow() }
