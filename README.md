# Run/Walk Timer

An Android interval timer for run/walk marathon training and racing. It keeps running with the screen off.

- **Run/walk intervals** (2:00 run / 0:30 walk by default). Beeps on 5, 4, 3, 2, 1, then a long beep and a spoken "Run" or "Walk".
- **Eating windows.** After a set time it says "Start eating", counts down the eating window, says "Stop eating", then starts the countdown to the next window.
- **Drink reminders** every set number of minutes.
- Cues play over your music and duck it while they play. The notification shows the live countdown and has a Pause button.

## Install

Download `app-release.apk` from the [latest release](https://github.com/johnboniello/run-walk-timer/releases/latest) and open it on your phone. You may need to allow installs from your browser or file manager.

On Samsung phones, set Settings → Apps → Run/Walk Timer → Battery to **Unrestricted** so the cues don't stop with the screen off.

## Build

Requires the Android SDK and JDK 21.

```sh
bash ./gradlew assembleRelease   # APK
bash ./gradlew bundleRelease     # AAB
```

Release signing reads `keystore.properties` (see `keystore.properties.example`). Without it, the release build is unsigned.
