# BandLab

BandLab is a local-first Android training and health companion built around phone sensors and a Xiaomi Smart Band.

## What it does

- connects directly to a compatible Xiaomi Smart Band over Bluetooth LE;
- handles secure Xiaomi session/authentication flows without exposing raw secrets to the user interface;
- synchronizes available Band information such as activity, sleep and battery data;
- uses live heart-rate samples during workouts when the Band provides them;
- supports walking, running, cycling and jump-rope workout flows;
- tracks routes with phone GPS/GNSS and rejects obviously bad location points;
- supports auto-pause, intervals, splits, manual laps and pacing comparisons;
- provides route guidance and local/offline route views;
- stores workout history locally;
- supports personal-record tracking and workout export formats;
- can combine direct Band data with Health Connect where appropriate.

## Engineering focus

The project is designed around honest sensor behavior. Missing heart-rate, GPS or Band data should stay missing rather than being replaced with believable-looking values.

Wearable integration is treated as hardware-dependent: build and emulator checks are useful, but they are not substitutes for testing Bluetooth, reconnection, authentication and live data against a physical Band and phone.

## Privacy

Authentication material, signing keys, Android Keystore data and other secrets are never intended for public source, logs or documentation.
