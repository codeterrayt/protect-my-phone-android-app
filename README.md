<h1 align="center">Protect My Phone</h1>

<p align="center"><strong>An owner-controlled Android anti-theft companion, using authenticated SMS between your own paired phones.</strong></p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%208%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8 and later">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
  <img src="https://img.shields.io/badge/minimum%20SDK-26-1D6F72?style=for-the-badge" alt="Minimum Android SDK 26">
  <img src="https://img.shields.io/badge/License-Apache--2.0-2F6F9F?style=for-the-badge" alt="Apache 2.0 license">
  <img src="https://img.shields.io/badge/Status-Prototype-C77716?style=for-the-badge" alt="Prototype">
</p>

Protect My Phone pairs devices you own so you can request status, location, an audible alarm, or a screen lock directly over authenticated SMS. Pair multiple phones, keep device information on your devices, and manage protection through a simple Android interface. No app account or command server is required.

> [!WARNING]
> **Consent and safety:** Install and pair this app only on devices you own or where every user has given informed, explicit consent. Never use it for covert monitoring, stalking, harassment, or tracking someone who has not acknowledged location sharing. Do not hide the app or its permissions from a device user. Location and device status are sensitive data. Follow local law and stop sharing when consent is withdrawn.

<p align="center"><strong>Video preview</strong><br><sub>MP4 upload pending</sub></p>

<!-- Upload docs/media/protect-my-phone-demo.mp4 as a GitHub attachment, then replace the placeholder above with the generated attachment URL on its own line. -->

## Screenshots

<p align="center">
  <img src="docs/images/protect-demo-home.png" alt="Protect My Phone demo home screen showing two fictional paired devices and their sample status" width="320">
  &nbsp;&nbsp;
  <img src="docs/images/protect-demo-device.png" alt="Protect My Phone demo device details with a loaded open-source map and sample location markers" width="320">
</p>

<p align="center"><sub>Real app screenshots from the Android emulator · all displayed device data is fictional sample data</sub></p>

<p align="center">
  <img src="docs/images/protect-demo-pairing.png" alt="Running app showing QR fingerprint verification for a fictional demo phone" width="320">
  &nbsp;&nbsp;
  <img src="docs/images/protect-demo-status.png" alt="Running app showing movement update settings and sample battery, mobile network, Wi-Fi and last response" width="320">
</p>

<p align="center"><sub>QR verification · movement updates · combined device status</sub></p>

## Features

- Pair multiple devices through an explicit QR-assisted SMS handshake. Commands are accepted only after authentication with the paired device key; pairing requests require owner approval.
- Send one `STATUS` request and receive a combined reply with available battery, mobile network, Wi-Fi network, last reported location, and response time.
- Request a one-time location or enable/disable movement updates. With background location access enabled, the target phone checks its own location and sends an SMS after moving about 50 metres. Choose the update interval in the app.
- Start or stop an audible find-phone alarm and see the result reported by the paired device.
- Request a remote screen lock after the device owner enables Android Device Admin. Display custom recovery text on the lock screen with Device Owner enrollment.
- View the last known location and its timestamp, place paired-device markers on the in-app map, or open a reported point in an external maps app.

## Requirements

- Android 8.0 (API 26) or later; the current app targets API 36.
- A powered-on phone with a working cellular/SMS plan and a SIM in each phone used for SMS pairing and commands. Carrier charges may apply, including multiple segments for longer messages.
- Internet access for loading map tiles.
- ADB Platform Tools for USB / wireless ADB installation, or a signed APK for direct installation.
- JDK 17 and Android SDK platform 37 for local builds.

## Install an APK directly

1. Build a signed release APK using the instructions below and copy it to your phone.
2. Open **Settings → Apps → Special app access → Install unknown apps**, select the browser or file manager opening the APK, and enable **Allow from this source**. Menu names vary by phone. See [Android's installation guidance](https://support.google.com/android/answer/9457058?hl=en).
3. Open the APK and complete installation. Open Protect My Phone, review the disclosure, and grant the access needed for your chosen features.
4. Repeat on the other phone and complete the QR-assisted pairing flow.

### If Google Play Protect blocks installation

Turning off Play Protect scanning **may allow installation when its scan is the blocker**. Treat this as a device-specific troubleshooting option: successful installation also depends on APK compatibility, signing, and device policies.

For an APK you built or whose source and signing identity you have reviewed:

1. Open **Google Play Store → profile picture → Play Protect → Settings**.
2. Turn off **Scan apps with Play Protect**, confirm your choice, and retry opening the APK.
3. Re-enable scanning afterward. Play Protect may warn about, disable, or remove an app it classifies as harmful during later scans.

Disabling scanning reduces protection for the whole phone. Google recommends keeping it enabled. See [Google's Play Protect settings and guidance](https://support.google.com/googleplay/answer/2812853?hl=en).

## Install on a phone with ADB

Build the APK from this source tree, then install it with Android Platform Tools.

1. Install Android Platform Tools and make sure `adb` is available in your terminal.
2. On the phone, enable **Developer options → USB debugging**. Connect it by USB and approve the computer’s debugging prompt. Wireless debugging can also be used after pairing the computer in Android settings.
3. From the project root, build and install a local debug APK:

   **Windows PowerShell**

   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb devices -l
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```

   **macOS / Linux**

   ```sh
   ./gradlew :app:assembleDebug
   adb devices -l
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   For the first install, `adb install <apk-path>` also works. `-r` updates an existing install only when the APK is signed with the same key; a signature mismatch needs the original signing key. Uninstalling can erase that device’s local pairings and state.

4. Repeat installation on each phone, open Protect My Phone, review the disclosure, and enable SMS monitoring. Pair devices by scanning the displayed QR and confirming the request on the receiving device. Verify that both users understand what the pairing enables.

For wireless ADB, open **Developer options → Wireless debugging → Pair device with pairing code** and use the one-time address and code shown on that phone:

```sh
adb pair PHONE_IP:PAIRING_PORT
adb connect PHONE_IP:ADB_PORT
adb devices -l
```

Enter the one-time code when `adb pair` prompts. The pairing port and connection port are different values shown in Android’s Wireless debugging screen. Do not publish a pairing code or leave wireless debugging enabled on an untrusted network.

### Build a signed release APK

Release signing is intentionally not configured with a key in this repository. Create and safeguard your own Android signing key. Set these values in your **user-level** Gradle properties or a secure build environment, never in the project or source control:

```properties
PROTECT_MY_PHONE_RELEASE_STORE_FILE=/absolute/path/to/your-upload-key.jks
PROTECT_MY_PHONE_RELEASE_STORE_PASSWORD=your-store-password
PROTECT_MY_PHONE_RELEASE_KEY_ALIAS=your-key-alias
PROTECT_MY_PHONE_RELEASE_KEY_PASSWORD=your-key-password
```

Then build and install:

```powershell
.\gradlew.bat :app:assembleRelease
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

Keep encrypted backups of the signing key and credentials. Future APK updates must use the same signing identity.

## Permissions

The app explains permissions in its setup flow. Grant only what you choose to use.

| Access | Used for | Required? |
| --- | --- | --- |
| Receive and send SMS | Receive paired commands and send encrypted replies | Required for the SMS command channel |
| Foreground location | Get a device’s location | Optional; required for location features |
| Background location | Answer location commands while the app is not visible and send opted-in movement updates | Optional; Android asks separately, and the user must enable it for these background features |
| Notifications | Display an active location foreground service on Android versions that require it | Needed when running background location service |
| Device Admin | Lock this device when an authenticated lock request arrives | Optional; explicitly enabled by the device owner |
| Device Owner enrollment | Display custom organization/owner text on the lock screen | Optional advanced managed-device setup, normally provisioned during device setup |

Review [Android's background-location setup](https://developer.android.com/develop/sensors-and-location/location/permissions/background) and [Device Owner management guidance](https://developer.android.com/work/dpc/device-management.html) when enabling advanced features.

## Privacy and security model

- Pairing state, local preferences, and saved locations remain on each phone. There is no application account, telemetry service, or command server configured by this project.
- SMS content is authenticated and encrypted at the application layer after pairing. The carrier handles the sender and recipient numbers, delivery time, and encrypted SMS payload.
- The map renderer uses MapLibre with OpenFreeMap tiles. Loading a map needs internet and sends ordinary tile requests to the tile provider, which can reveal the map area being viewed. The SMS transport and location replies do not go through that map service. Map data attribution is shown in the app and noted in [third-party notices](THIRD_PARTY_NOTICES.md).
- Use strong device screen locks. Protect the devices, pairing QR, backups, and signing key. If you suspect an account or device compromise, revoke pairing locally and rotate credentials where supported.
- Independent security reviews and protocol audits are welcome; share reproducible findings with sensitive values removed.

## Development

Open the project in Android Studio, or use the Gradle wrapper:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

### Screenshot demo build

The separate demo variant is a UI preview with two fictional devices, sample status and location values, and a sample pairing QR. It uses the app's existing interface. Use the regular app build for SMS pairing and device protection.

```powershell
.\gradlew.bat :app:assembleDemo
adb install -r .\app\build\outputs\apk\demo\app-demo.apk
```

It installs as **Protect My Phone Demo** alongside the regular app. Demo-only sources are in `app/src/demo/`; the screenshots in `docs/images/` were captured directly from this APK on an Android emulator. All displayed phone numbers, names, locations, and QR verification data are sample values.

## License

Original source and project assets are licensed under the [Apache License 2.0](LICENSE). Third-party software, map data, and tile services retain their own licenses and terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Product and platform names are used for identification and remain the property of their respective owners.

## Contributing

Contributions are welcome. You can help by reporting reproducible bugs, documenting Android or device-specific behavior, improving accessibility and the interface, or submitting focused pull requests. Please describe the Android version and device model when relevant, and remove phone numbers, exact locations, SMS contents, pairing QR codes, keys, and other personal data from reports and screenshots. Test only with devices you own or with the informed consent of their users. Contributions are made under the project’s Apache-2.0 license.
