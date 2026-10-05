# ABS Card Terminal Simulator – Android APK

This Android app is a local card-terminal simulator for testing the ABS Smart Parking kiosk/payment flow before a bank SDK/MID/TID is available.

## Build APK on GitHub

1. Create a GitHub repository and upload this project.
2. Open **Actions** → **Build ABS Card Terminal Simulator APK**.
3. Click **Run workflow**.
4. When the workflow finishes, open the workflow run and download the artifact **ABS-Card-Terminal-Simulator-debug**.
5. Extract the ZIP and install `app-debug.apk` on the Android device.

## Simulator API

Default TCP/HTTP terminal service:

- Port: `9100`
- `GET /status`
- `POST /sale` with JSON such as `{ "amount": 500, "reference": "PARK-123" }`
- `POST /cancel`
- `GET /result?reference=PARK-123`

The app displays the device's LAN IPv4 address so the parking kiosk can point to it.

## Important

This is a **test simulator**, not a real bank payment terminal. It must not be used to process real customer card payments. Real payments will later use the acquiring bank/payment provider's approved Android SDK/API and credentials.
