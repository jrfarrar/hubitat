
Modified for Hubitat

Setting LUX to 0 should uncouple the light sensor from the motion sensor

If connected to zwave the controls on the body don't matter.  

Also don't have the body controls in TEST mode...somewhere in the middle


This DH now has two modes, "manual" and "auto". In manual mode all it does is set the lux to 0. In auto mode it sets the lux to whatever value you set it to on the DH page. You can now create a custom command to set the "auto" or "manual" mode.

The "On Time" or duration will also be the duration that the sensor shows "active" once triggered. So if you set it to 8, it will reset in 8 seconds to inactive. Etc.

If you additionally set the Lux Value to 0. This basically disables the sensor from turning the light on locally. AKA you now have a Illuminance sensor, a motion sensor and a switch that all report back to the hub. No local turn on of the light. See above for "On Time" as that still sets how long the motion sensor stays active.

Once you set the Lux value then that engages the local light turn on. So at that lux value and below with motion the light will turn on and stay on for the duration of "On Time" as listed above. The motion sensor will also show "active" for this same time period.
