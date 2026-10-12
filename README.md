# SignalShield (OFFLINE-APP)

Android app that watches mobile signal on trains and buses, learns where dead zones are,
warns you before you reach one, and keeps your messages queued until signal returns.

## Version 0.1 includes
- Background monitoring service (keeps running with the screen locked)
- Real signal readings per SIM (dual-SIM), network type, dBm, internet delay
- Live status screen with a 5-minute signal ribbon
- Dead-zone learning (only while you are moving) with distance, direction and usual duration
- Early warning by notification, vibration and voice
- Outbox for messages, with one-tap SMS or WhatsApp when signal is good

## Get the APK
1. Push this project to GitHub (branch `main`).
2. Open the **Actions** tab and wait for **Build APK** to finish (about 5 minutes).
3. Download from **Releases**: `https://github.com/srinivasavadutha55-bot/OFFLINE-APP/releases/latest/download/SignalShield.apk`
4. On your phone, open the file and allow "Install unknown apps" when asked.

## Planned
- Auto-download of map tiles, podcasts and articles before a dead zone (30-minute buffer)
- Automatic SMS sending
- Crowd-sourced best operator per route
