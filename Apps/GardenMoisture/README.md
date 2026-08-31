# Garden Moisture Logger

Learns when a vegetable garden actually needs watering, from an Ecowitt WH51 soil probe plus WS90
rain and an Open-Meteo forecast.

**v0.1 sends no notifications and controls nothing.** It observes, records, and maintains rolling
estimates. Notifications come later, once the estimates have been watched against reality for a
season.

## Files

| File | What it is |
|---|---|
| `GardenMoistureLogger.groovy` | Parent app. Container only. |
| `GardenMoistureLoggerChild.groovy` | One instance per probe/zone. All the logic. |
| `GardenSimSensor.groovy` | **Test fixture.** Virtual driver standing in for WH51 + WS90 + outdoor temp. |
| `GardenMoistureSim.groovy` | **Test fixture.** Scenario runner that drives the sim device. |
| `season_harness.py` | Off-hub Python replay of the estimator math across synthetic seasons. |

Only the first two belong on a production hub.

## The attribute trap

The Ecowitt RF Sensor driver maps soil moisture onto the **`humidity`** attribute — there is no
`soilMoisture` attribute, and the WH51 child presents as a Relative Humidity Measurement device.
Raw A/D arrives as `soilAD`. From the WS90, `raining` is the **string** `"true"`/`"false"`, not a
boolean.

## Why there is no fixed threshold

The WH51's 0–100% is remapped capacitance, not volumetric water content — the gateway scales raw A/D
against min/max values set in the Ecowitt app, and the defaults are generic. "Water at 30%" is
meaningless until anchored to a particular garden, at a particular probe depth.

So the app derives it:

```
threshold = FC − MAD × (FC − stressPoint)
```

- **FC** (field capacity) — median of the top decile of daily readings over the anchor window.
- **stressPoint** — median of the marks she presses when the garden actually looks thirsty.
- **MAD** — management allowed depletion, default 0.5. Standard guidance for vegetables is to water
  when about half the plant-available water is gone. Crop dependent: salad greens ~0.3, established
  tomatoes ~0.6.

Both anchors are rolling estimates that keep refining for the life of the app. Notifications stay
gated off until a confidence bar is met — the app decides when it is ready, rather than a date
deciding for it.

> **Field capacity was originally taken only from large wetting events (a rise of ≥10 points,
> sampled 24 h later). Replaying synthetic seasons showed that converges in only ~19% of seasons** —
> a garden watered before it dries out rarely swings that hard, so the observations never
> accumulate. The top-decile-of-daily approach converged in 100% of the same seasons with roughly a
> quarter of the error. Rise-based observations are still recorded, as a fallback and because they
> are directly informative. See `season_harness.py`.

## Design principle

Costs are asymmetric. A missed notification costs nothing — that is the status quo. A false "go
water it" costs credibility, and enough of those get the notifications muted, which ends the
project. **When uncertain, say nothing.** Confidence widens the dead band; low confidence means the
reading must sit clearly below the threshold before anything would fire.

## Setup

1. Mesh the WH51 child (and the WS90 child) to the hub running this app.
2. Set, and **write down**, the A/D calibration and the probe's depth and location. Every number the
   app produces is relative to that choice. Vegetable root zone is roughly 6–12"; a probe pushed in
   2" measures evaporation, not plant-available water.
3. Install the parent, then add one child per zone.
4. Create two virtual switches for her markers — "needed water" and "just watered" — and a third for
   "garden season active". **The stress anchor cannot be learned without the markers**; they are the
   highest-value input in the whole app.

Note on the season switch: turning it **on** clears the field-capacity observations, on the
assumption the probe has been re-seated somewhere slightly different after winter. It ignores a
repeat `on` and refuses to clear if the season started under 30 days ago, so a dashboard double-tap
cannot cost a season of anchors.

## Testing

**On-hub:** create a virtual device on the `Garden Sim Sensor` driver and select it as the soil
probe, the rain source *and* the temperature source — it satisfies all three. Install
`Garden Moisture Sim`, set the child app's **Testing → simulation speed-up divisor** above 1 (500 is
a reasonable start), pick a scenario and press Run. Each scenario logs what to expect when it
starts, so the log reads as a pass/fail checklist.

Thirteen scenarios cover core paths (dry-down, rain, manual watering, shallow watering, marks
accumulating until a threshold appears), edge cases (probe pulled, season cycle, season double-tap,
freeze, rain-on-top-of-watering) and abuse (rapid-fire events, sensor silent mid-event,
sub-threshold noise).

**Afterwards: set the divisor back to 1 and use Maintenance → Clear learned data**, or the app
carries simulation garbage into the real season.

**Off-hub:** `python3 season_harness.py`. The hub sim cannot replay a synthetic *year* — the app
reads the wall clock and that cannot be moved — so multi-season convergence is tested by
reimplementing the estimator math in Python. It validates the **math only**: no Groovy, no Hubitat
API.

## Two Hubitat traps worth knowing

- **`runIn` overwrite is keyed on the handler method name**, not the job. `runIn(5, "sampleTick")`
  cancels a `runEvery15Minutes("sampleTick")` scheduled moments earlier. The kickstarts use
  separately-named wrappers.
- **`runIn` does not survive a reboot**, and the wetting-event follow-ups are scheduled up to 24 h
  out. `backfillFollowUps()` sweeps for overdue ones, but accepts a reading only within 3 h of
  target and marks the rest `missed` — a three-day-late sample recorded as a "+24 h settled value"
  would poison the field-capacity anchor.
