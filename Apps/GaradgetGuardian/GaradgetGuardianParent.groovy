/**
 *  Garadget Guardian Parent  -  container for "Garadget Guardian Child", one child per garage door
 *
 *  v1.0.0  2026-10-31
 *
 *  Owns nothing (same pattern as Bathroom Fan Parent): creates/lists/removes children and shows one
 *  status line per door. Every device and setting lives in the child.
 */

definition(
    name: "Garadget Guardian Parent",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Container for one Garadget Guardian Child per garage door. Owns nothing.",
    category: "Safety & Security",
    iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/GaradgetGuardian/GaradgetGuardianParent.groovy",
    singleThreaded: true
)

preferences { page(name: "mainPage") }

String APP_VERSION() { return "1.0.0" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph "<b>Garadget Guardian v${APP_VERSION()}</b> - one child per garage door. " +
                      "Needs the Garadget MQTT driver 1.4.6+ and an outlet powering each Garadget."
            input "thisName", "text", title: "<b>Name for this container</b>", submitOnChange: true, required: true, defaultValue: "Garadget Guardian"
            if (thisName) app.updateLabel(thisName)
        }
        section("<b>Doors</b>") {
            app(name: "doors", appName: "Garadget Guardian Child", namespace: "jrfarrar", title: "<b>Add a door</b>", multiple: true)
        }
        section("<b>Status</b>") {
            def kids = getChildApps()
            if (!kids) paragraph "No doors yet."
            else kids.sort { it.label ?: it.name }.each { k -> paragraph statusLine(k) }
        }
    }
}

def installed() { }
def updated() { }

private String statusLine(k) {
    def st = null
    try { st = k.publicStatus() } catch (ignored) { st = null }
    String h = (st?.health ?: "-") as String
    String colour = (h == "ok") ? "green" : ((h == "-") ? "gray" : "red")
    return "<b>${k.label ?: k.name}</b> &nbsp; v${st?.v ?: '?'} &nbsp; <span style='color:${colour}'>${h}</span>" +
           " &nbsp; door ${st?.door ?: '-'} &nbsp; outlet ${st?.outlet ?: '-'} &nbsp; cycles/24h ${st?.cycles24h ?: 0}" +
           (st?.phase ? " &nbsp; <b>cycling: ${st.phase}</b>" : "") + (st?.mode == "notify" ? " &nbsp; (notify only)" : "")
}
