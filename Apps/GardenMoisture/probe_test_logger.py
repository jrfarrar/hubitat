"""Probe removal signature test - detached poller.

Polls the SOURCE sensor (component child 3243 of gateway 3117 on hub .38), not the
Hub Mesh mirror on .40, so the mesh hop is not a variable.

Records the hub's own per-attribute change timestamps, so the 10 s poll interval does
not limit timestamp precision - it only risks missing a change if two land in the
same window, which the sensor's report rate makes very unlikely.

Stop by creating the file STOP in this directory.
"""
import urllib.request, json, os, time, datetime

BASE = r"C:\CLAUDE\Hubitat\GardenMoisture"
OUT = os.path.join(BASE, "data", "probe_test_%s.csv" % datetime.date.today().isoformat())
STOP = os.path.join(BASE, "STOP")
URL = "http://<hub-b>/device/fullJson/3243"
INTERVAL = 10

COLS = ["pollIso", "soilAD", "soilAD_date", "humidity", "humidity_date",
        "battery", "batteryOrg", "changed", "err"]


def snap():
    d = json.loads(urllib.request.urlopen(URL, timeout=8).read())
    cs = d["device"]["currentStates"]

    def g(name, field="value"):
        return (cs.get(name) or {}).get(field, "")
    return {
        "soilAD": g("soilAD"), "soilAD_date": g("soilAD", "date"),
        "humidity": g("humidity"), "humidity_date": g("humidity", "date"),
        "battery": g("battery"), "batteryOrg": g("batteryOrg"),
    }


os.makedirs(os.path.dirname(OUT), exist_ok=True)
new = not os.path.exists(OUT)
f = open(OUT, "a", newline="")
if new:
    f.write(",".join(COLS) + "\n")
    f.flush()

prev = None
while not os.path.exists(STOP):
    iso = datetime.datetime.now().isoformat(timespec="seconds")
    try:
        s = snap()
        key = (s["soilAD_date"], s["humidity_date"])
        row = [iso, s["soilAD"], s["soilAD_date"], s["humidity"], s["humidity_date"],
               s["battery"], s["batteryOrg"], "1" if key != prev else "0", ""]
        prev = key
    except Exception as e:
        row = [iso, "", "", "", "", "", "", "", str(e)[:80].replace(",", ";")]
    f.write(",".join(str(x) for x in row) + "\n")
    f.flush()
    time.sleep(INTERVAL)

f.close()
