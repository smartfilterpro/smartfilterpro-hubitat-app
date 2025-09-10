metadata {
    definition(name: "SmartFilterPro Status Sensor", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "Sensor"
        capability "Refresh"
        attribute "percentageUsed", "NUMBER"
        attribute "todayMinutes", "NUMBER"
        attribute "totalMinutes", "NUMBER"
        attribute "deviceName", "STRING"
        attribute "lastUpdated", "STRING"
    }
}
def refresh() { parent?.pollNow() }
