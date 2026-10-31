# Garadget MQTT driver for Hubitat

Community thread: https://community.hubitat.com/t/release-garadget-mqtt-device-handler/43377
Install with Hubitat Package Manager: **Garadget MQTT Driver** (repository `jrfarrar`).

Talks to a [Garadget](https://www.garadget.com/) garage door controller directly over your own MQTT
broker - no cloud. Commands go to `garadget/<DoorName>/command`, status and config come back on
`garadget/<DoorName>/status` and `.../config`. Set the Garadget's MQTT broker and topic name in the
Garadget app/web page first, and use a **door name without spaces** (e.g. `Door1`).

## Health reporting (1.4.6+)

A Garadget can be wrong without being silent. In October 2026 one unit's optical sensor froze: for seven
days it stayed on WiFi and MQTT, answered every poll, and kept reporting **closed** - including a night
the door was actually open. A connection watchdog cannot see that. 1.4.6 adds a `health` attribute that can:

| `health` | Meaning |
|---|---|
| `ok` | all checks passing |
| `sensorFrozen` | the reflection reading has not changed **at all** for N hours (default 36) while the unit is still answering. A healthy unit's reading jitters by 1-3 points dozens of times a day even when the door never moves (measured on three doors over eight weeks: longest healthy gap 19.5 h; the frozen unit went 176 h) |
| `commandFailed` | an open/close was not confirmed by the reflection reading crossing the unit's threshold in the right direction within travel time + margin. A `closed` status string alone is **not** accepted as proof |
| `offline` | no answer for twice the stale window |

`healthReason` carries a plain-English explanation. Also new: `lastBoot` (the unit restarted, e.g. after a
power cycle - detected from the unit's own uptime counter), `lastCommandResult`, and the command
`resetHealth`. Health checks run every 15 minutes and can be switched off in preferences.

**Pair it with [Garadget Guardian](../../Apps/GaradgetGuardian/)** (separate HPM package) to have each
Garadget power-cycled automatically through its own smart outlet when `health` is not `ok`, with a
notification telling you what the unit sees afterwards - above all whether the door is actually open.

### Tested how
The driver source is run unmodified on a simulated hub (virtual clock, a modelled Garadget, an MQTT broker
that replays retained messages) - see `Apps/GaradgetGuardian/test/`. Eight weeks of real event history
from three doors were replayed through it: `sensorFrozen` fired only on the real 7-day freeze, and reboot
detection found exactly the real power cycles. Then end-to-end on real hardware.

## garadget-orig.groovy

Original ported SmartThings driver (namespace `fuzzysb`, Stuart Buchanan), kept for provenance.

> This is the originally ported ST code that is currently not working with the particle.io site as of 4/2020.
> The Authentication needs re-written
