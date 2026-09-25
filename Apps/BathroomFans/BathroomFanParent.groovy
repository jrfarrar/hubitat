/**
 *  Bathroom Fan Parent  -  parent container for "Bathroom Fan Child" children
 *
 *  v1.1.0  2026-09-25  definition renamed "Bathroom Fans" -> "Bathroom Fan Parent" and the child
 *                      "Bathroom Fan" -> "Bathroom Fan Child" (J.R.: the two names side by side on the
 *                      Apps code page were confusing). Instance labels unchanged.
 *  v1.0.0  2026-09-24
 *
 *  THIS PARENT OWNS NOTHING. J.R., 2026-09-24: "I don't think the parent should be anything more
 *  than the parent. I don't see the need for the parent to own the outside and house references.
 *  Just as easy to put them each into the children and avoid the extra confusion."
 *
 *  That matches everything the project measured: every rule that survived constrains what ONE fan
 *  can do in ONE room; every idea that modelled the house died (NextGen\README.md 2.9h). A parent
 *  that holds "the" house reference is house-modelling smuggled in through architecture. The
 *  reference is a per-room choice, so it lives in the child. Sharing a basis between rooms is done
 *  by pointing both children at the same device - by convention, not enforcement.
 *
 *  What this app does, and all it does:
 *    - creates / lists / removes "Bathroom Fan" children
 *    - shows one status line per child, read from the child's own published state.st map
 *    - has a rename field, per the standing rule that every app must
 *  No settings flow down. No sensors. No logic.
 */

definition(
    name: "Bathroom Fan Parent",
    namespace: "jrfarrar",
    author: "J.R. Farrar",
    description: "Container for one Bathroom Fan Child per bathroom. Owns nothing.",
    category: "Convenience",
    iconUrl: "", iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jrfarrar/hubitat/master/Apps/BathroomFans/BathroomFanParent.groovy",
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

String APP_VERSION() { return "1.1.0" }

def mainPage() {
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph "<b>Bathroom Fans v${APP_VERSION()}</b> - one child per bathroom. " +
                      "Every sensor, reference and setting lives in the child."
            input "thisName", "text", title: "<b>Name for this container</b>",
                  submitOnChange: true, required: true, defaultValue: "Bathroom Fans"
        }
        section("<b>Bathrooms</b>") {
            app(name: "rooms", appName: "Bathroom Fan Child", namespace: "jrfarrar",
                title: "<b>Add a bathroom</b>", multiple: true)
        }
        section("<b>Status</b>") {
            def kids = getChildApps()
            if (!kids) {
                paragraph "No bathrooms yet."
            } else {
                kids.sort { it.label ?: it.name }.each { k ->
                    paragraph statusLine(k)
                }
            }
        }
    }
}

/** One line per child, from the child's own published status map (child method publicStatus()).
 *  A parent cannot read a child's `state`; the child hands over exactly the map it already
 *  publishes as its public API. Anything missing renders as "-" rather than throwing. */
private String statusLine(k) {
    def st = null
    try { st = k.publicStatus() } catch (ignored) { st = null }
    String mode = (st?.mode ?: "-") as String
    String v    = (st?.v ?: "?") as String
    String why  = ((st?.reason ?: "") as String).take(70)
    String quiet = (st?.deviceQuietMin != null) ? "${st.deviceQuietMin} min" : "-"
    String colour = (mode == "RUNNING_AUTO") ? "green" : ((mode == "IDLE") ? "gray" : "orange")
    return "<b>${k.label ?: k.name}</b> &nbsp; v${v} &nbsp; " +
           "<span style='color:${colour}'>${mode}</span> &nbsp; sensor quiet ${quiet}<br>" +
           "<small>${why}</small>"
}

def installed()   { initialize() }
def updated()     { initialize() }
def uninstalled() { }   // children are removed by the platform with the parent

def initialize() {
    if (thisName) app.updateLabel(thisName)
}
