ABS CARD TERMINAL SIMULATOR (Android)
=====================================
Test app that pretends to be the card machine (Wiseasy). The kiosk sends the amount to the
app over Wi-Fi; the tester taps what the "card" should do:

  PAYMENT SUCCESSFUL | CARD REJECTED | CARD EXPIRED | INSUFFICIENT BALANCE | CANCEL SALE
  + switch "Simulate terminal OFFLINE" (tests the red CARD TERMINAL status on the kiosk)

1) BUILD THE APK (Android Studio, once)
   - File > Open > select this folder (ABS-Card-Terminal-Simulator). Wait for Gradle sync.
     (Needs internet the first time. If asked, install "Android SDK Platform 34".)
   - Build > Build App Bundle(s) / APK(s) > Build APK(s)
   - APK: app/build/outputs/apk/debug/app-debug.apk
   - Copy it to the phone and install (allow "install unknown apps"), or plug the phone in with
     USB debugging and press Run.

2) RUN ON THE PHONE
   - Phone and kiosk PC must be on the SAME Wi-Fi/LAN (not guest Wi-Fi with client isolation).
   - Open the app. It shows "192.168.x.x : 9100". Keep the app open (screen stays on).

3) POINT THE KIOSK AT THE PHONE
   kiosk_config.json:
     "card": { "mode": "android", "host": "192.168.x.x", "port": 9100, "timeout_seconds": 60 }
   Server config.json (TEST ONLY, because the simulator references start with SIM-):
     "kiosk_allow_simulated_card": true
   Restart the server and START_KIOSK.bat. Kiosk bottom bar must show CARD TERMINAL green.

4) TEST
   Kiosk: vehicle number > CHECK > PAY > CARD > the phone shows the amount > tap a result.
   - PAYMENT SUCCESSFUL      -> kiosk "PAYMENT SUCCESS", vehicle PAID, SIM- reference saved
   - CARD REJECTED / EXPIRED / INSUFFICIENT -> kiosk "Card not approved ... No money was taken"
   - CANCEL (phone or kiosk) -> sale cancelled, nothing recorded
   - OFFLINE switch ON       -> kiosk shows CARD TERMINAL red and blocks card payment

Protocol (for the real Wiseasy app later): GET /status, POST /sale, POST /cancel,
GET /result?reference=... on port 9100. Keep this contract and the kiosk needs no change.
