# Adaptive Brightness (Android TV)

An Android TV app that uses the TV's camera to:

- **Adjust screen brightness to the room light.** Bright room → brighter picture; dark room → dimmer picture. Changes are smoothed so there is no flicker.
- **Spotlight whoever raises a hand.** When someone holds a hand up for about a second, a corner window opens that zooms in on that person and follows them. It closes 10 s after the hand comes down or 3 s after they leave the frame.

Camera frames are analysed on the TV only. Nothing is recorded, saved or sent anywhere.

Built for an Android 14 TV ("taishan", board gk6780v100) with a USB camera and a vendor backlight setting (`Settings.Global picture_backlight`, 0–100). Other TVs that don't use that setting will need a different brightness control.

## How it works

| Piece | File |
|---|---|
| Foreground service: brightness mapping, mode switching, standby handling | `src/.../BacklightService.java` |
| Continuous camera stream (analysis + optional preview), yields to other apps | `src/.../LiveCamera.java` |
| Body tracking: MoveNet MultiPose Lightning via TensorFlow Lite | `src/.../PoseDetector.java` |
| People tracking, raised-hand trigger, zoom-and-follow overlay window | `src/.../SpotlightController.java` |
| Remote-friendly settings screen | `src/.../MainActivity.java` |
| Starts at boot / after updates | `BootReceiver.java`, `BootActivity.java` |
| Background audio playback triggered over adb | `SpeakReceiver.java` |

- **Brightness only:** the camera opens for about 3 s every 10 s, and the app maps the measured light onto a range between the lowest and highest brightness you set.
- **Spotlight on:** the camera streams continuously at 720p and both features share it. Pose detection runs at about 3–8 checks per second on 2 CPU threads.

A hand counts as raised when the wrist is above the head and the elbow is at least at shoulder height, held for 0.8 s.

## Build

No Gradle. The build needs the JDK 17 and Android SDK build-tools 34 / platform 34 installed under `%LOCALAPPDATA%\Android`.

```powershell
.\build.ps1          # -> AdaptiveBrightness.apk (creates debug.keystore on first run)
```

`libs/` holds the TensorFlow Lite 2.16.1 AARs and `assets/` holds the MoveNet model. Both are Apache-2.0 licensed.

## Install

```powershell
adb connect <tv-ip>:5555
adb install -r AdaptiveBrightness.apk
adb shell pm grant com.autobacklight.tv android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.autobacklight.tv SYSTEM_ALERT_WINDOW allow
```

Then open **Adaptive Brightness** on the TV and turn on the two switches. For the best brightness mapping, use **Calibrate: the room is DARK** at night and **Calibrate: the room is BRIGHT** during the day.

### Play audio in the background (optional)

```powershell
adb push message.wav /sdcard/Android/data/com.autobacklight.tv/files/
adb shell am broadcast -n com.autobacklight.tv/.SpeakReceiver --es file message.wav --ei volume 30
```

## Privacy

With the spotlight on, the camera watches the room whenever the TV is on. It pauses in standby and when another app uses the camera. Make sure everyone who uses the room knows.
