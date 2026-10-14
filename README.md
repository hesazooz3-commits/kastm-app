# Kastm App

Android project for Bluetooth pairing retry automation.

## What it does
- Searches for available Bluetooth devices.
- Lets you select a target device.
- Runs multiple pairing attempts automatically.
- Cancels the pairing prompt as soon as it appears, then retries.
- Shows counters for attempts, success, failures, and cancellations.
- Provides a stop button and a cancel attempt button.
- Runs the pairing process in a foreground service.

## Important note
This project is intended for testing on devices you own or have explicit permission to test. On modern Android versions, pairing behavior is heavily controlled by the system and may require user interaction or a privileged/system-level implementation.

## Build instructions
1. Open the project in Android Studio.
2. Let Gradle sync complete.
3. Choose Build > Build APK.
4. Install the generated APK on a real Android device.

## Permissions used
- BLUETOOTH
- BLUETOOTH_ADMIN
- BLUETOOTH_SCAN
- BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION

## Package name
`com.example.btpairpro`
