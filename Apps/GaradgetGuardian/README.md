# Garadget Guardian (Hubitat app)

Community thread: https://community.hubitat.com/t/release-garadget-mqtt-device-handler/43377
Install with Hubitat Package Manager: **Garadget Guardian** (repository `jrfarrar`).

Keeps an eye on your Garadget garage door controllers and fixes the commonest failure - a unit that has
locked up - by power-cycling it through its own smart outlet. Then it tells you what the unit sees after
the restart, and above all whether the door is actually **open**.

## Why
A Garadget can fail *quietly*: in October 2026 one unit's optical sensor froze for seven days while it
stayed online and kept reporting "closed", including a night the door was open. Pulling its power fixed
it. Garadget MQTT driver 1.4.6+ detects that (and failed commands, and offline units) in its `health`
attribute; Guardian acts on it.

## Requirements
- **Garadget MQTT Driver 1.4.6 or later** (same HPM repository). The app reads its `health`,
  `healthReason` and `lastBoot` attributes.
- A switched smart outlet (Zigbee/Z-Wave/Matter - anything with the Switch capability; Hub Mesh
  devices work) powering **only the Garadget**. Never the opener.
- Optional: notification devices for "it needs you" and "it fixed itself" (they can be the same device).

## Setup
1. Install via HPM, then *Apps -> Add user app -> Garadget Guardian Parent*.
2. Inside it, **Add a door** once per Garadget: pick the Garadget, its outlet, and your notifiers.
3. Action: *Power-cycle and notify* (default) or *Notify only* if you just want to be told.

## What it does
When a door's `health` turns `offline`, `sensorFrozen` or `commandFailed`:
1. Waits until the door has been still for 5 minutes (never acts while a door is opening or closing).
2. Switches the outlet off for 15 s, then on.
3. Waits for proof the unit restarted (the driver's `lastBoot`; it asks the unit once more if needed).
4. Notifies what it now reports: **ALERT if the door is open**, INFO otherwise; "back to normal" when
   `health` returns to `ok`.

It never moves a door - it only switches the Garadget's power. A Garadget reboot does not operate the door.

## Safety limits
- At most 2 power cycles in any 24 hours **and** 2 per problem; then one "NEEDS YOU" alert and it stops
  until the door is healthy again - a broken unit is not rebooted forever.
- After a successful reboot it gives a frozen reading up to 12 hours to start moving before trying again.
- A failed command where the door reports `stopped` **and the reading moved** is a physical problem
  (obstruction, safety eyes) - it is not power-cycled; your door-stopped alarm covers that.
- The outlet is never left off: power is restored on hub start, by a timer, and by a 30-minute check
  (which also tells you if something else switched the outlet off).

All limits are adjustable under *Limits*.

## Tested how
`test/` runs the real app and driver source on a simulated hub (`HubSim.groovy`, `Runner.groovy`,
`AppRunner.groovy`; see `test/README.md`), including mutation checks that every safety rule has a test that
fails without it. Then end-to-end on three real doors: open/close on each, a power cycle on each, and a
Guardian-driven recovery of an unpowered unit.
