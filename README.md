# OT Pocket Android v0.2

This project packages the OT Pocket patient/occupational-therapist interface as a real Android application that runs without a PC or web server.

## Native Android features in v0.2

- Complete interface bundled inside the APK and available offline
- Private app-local WebView storage for the current prototype data model
- Camera and gallery capture through Android's system activities
- Direct Photo action from reminder notifications
- Text, Done, and Later notification actions
- Native reminder scheduling with exact-alarm fallback
- Reminder restoration after phone reboot or app update
- Optional persistent quick-capture notification
- CSV and JSON export to `Downloads/OT Pocket`
- Android back-button, app icon, and standalone app behavior

## Deliberate current boundaries

This is an installable development build, not yet a clinic-deployment release. The following production layers are not represented as complete:

- encrypted relational database
- healthcare-center accounts and permissions
- encrypted synchronization and Canadian hosting
- Activity Recognition, Health Connect, or geofencing
- clinician report approval workflow
- formal privacy/security review and production signing

Do not use the development build as the sole clinical record.

## Build

Requirements:

- JDK 17
- Android SDK platform 35 and build tools 35.0.0
- Gradle 8.11.1

Run:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The development application ID is `ca.otpocket.app.dev`.
