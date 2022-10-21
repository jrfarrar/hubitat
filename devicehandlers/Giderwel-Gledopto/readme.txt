# Gledopto GL-C-008 RGB+CCT LED Controller 2ID
There are multiple products sold under the GL-C-008 name and they aren't all the same. There is a variation which has been problematic for Hubitat users which presents itself as one device with two endpoint id's (0x0B for RGB and Ox0F for CT). Hubitat doesn't recognize the second endpoint and so it hasn't been possible to fully be able to use this device.  

The approach I took was to create a separate child device driver for each of the two endpoints and treat it like two independent devices. The parent device implements the capabilities which expose on/off commands and attempts to be smart about which child device to turn on. It keeps track of the colorMode so that when you turn it on, it only turns on the appropriate child device. When you turn one of the child devices on, it will turn off the other child device. My reasoning is to limit the total brightness and heat output of an LED strip.

## How to use
Add the three device drivers to your Hubitat device. You can directly import using the following 3 url's to download directly from GitHub.  
* https://raw.githubusercontent.com/mconnew/Hubitat/main/drivers/gledopto/gledopto_2ID_RGB_CCT.groovy
* https://raw.githubusercontent.com/mconnew/Hubitat/main/drivers/gledopto/gledopto_2ID_RGB_child.groovy
* https://raw.githubusercontent.com/mconnew/Hubitat/main/drivers/gledopto/gledopto_2ID_CT_child.groovy

Once installed, configure the device driver for your device to use the main `GLEDOPTO ZigBee RGB+CCT Light GL-C-008 2ID` device. Click the On command, then the off command and it will populate the child devices. Go to the CT child device and run the configure command.

## Found a problem?
I can't provide any support for this, I simply don't have the time. I released these in a state of "it generally works for me" as to wait until it's working perfectly would mean waiting many months before I would release it. The hardware sat on the shelf for 2 1/2 years before I finally got around to making them work (while waiting for someone else to do it first 😁). I welcome pull requests for any fixes or improvements.
