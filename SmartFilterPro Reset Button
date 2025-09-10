metadata {
    definition(name: "SmartFilterPro Reset Button", namespace: "smartfilterpro", author: "Eric Hanfman") {
        capability "PushableButton"
        capability "Momentary"
        command "push", [[name:"buttonNumber*", type:"NUMBER", description:"(ignored)"]]
        attribute "lastReset", "STRING"
    }
}
def installed() { sendEvent(name:"numberOfButtons", value:1) }
def updated()   { sendEvent(name:"numberOfButtons", value:1) }
def push(btn=1) { parent?.resetNow(); sendEvent(name:"lastReset", value:new Date().toString()); }
def on() { push(1) }   // allows use as a tile
def off() { }          // noop
