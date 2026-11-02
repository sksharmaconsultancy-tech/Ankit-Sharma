# Tally Data Viewer (Android APK)

Apne phone se **apne computer par chal rahe Tally (TallyPrime / Tally.ERP 9)** ka pura data live dekhein —
ledgers, trial balance, debtors/creditors, cash & bank, stock items, day book aur har ledger ka statement.

App sirf **read-only** hai: Tally me kuch bhi change nahi karta.

## APK kahan se milega

Har push par GitHub Actions APK build karta hai:

1. Repo me **Releases** section kholein → sabse naya *Tally Data Viewer (build N)* → `TallyDataViewer.apk` download karein.
   (Ya **Actions → Build APK → latest run → Artifacts → TallyDataViewer-apk**.)
2. Phone par APK kholein. "Unknown sources / Install unknown apps" allow karna padega.

## Tally PC par setup (ek baar)

1. TallyPrime kholein aur company open (load) karein.
2. Tally ka XML server ON karein:
   - **TallyPrime:** `F1 (Help) > Settings > Connectivity > Client/Server configuration`
     → *TallyPrime acts as* = **Both**, *Port* = **9000**
   - **Tally.ERP 9:** `F12 > Advanced Configuration` → *acting as* = **Both**, *Port* = **9000**
   - Save karke Tally restart karein. Check: PC ke browser me `http://localhost:9000` kholne par
     "TallyPrime Server is Running" dikhna chahiye.
3. PC ka IP: Command Prompt me `ipconfig` → **IPv4 Address** (jaise `192.168.1.5`).
4. Windows Firewall me inbound **TCP port 9000** allow karein
   (`Windows Defender Firewall > Advanced settings > Inbound Rules > New Rule > Port > TCP 9000 > Allow`).
5. Phone aur PC **ek hi Wi-Fi / LAN** par hon. App me IP + port daal kar *Connect* dabayein.

### Office ke bahar se access

Dono (PC + phone) par [Tailscale](https://tailscale.com) (free) install karein, same account se login karein,
aur app me PC ka Tailscale IP (`100.x.x.x`) daalein. Router par port 9000 ko internet par **open na karein** —
Tally ke XML server me koi password nahi hota.

## Features

| Screen | Kya dikhata hai |
|---|---|
| Ledgers | Sabhi ledgers, group, closing balance (Dr/Cr), search |
| Trial Balance | Primary groups ke balances; group par tap → sub-groups/ledgers (drill-down) |
| Sundry Debtors / Creditors, Cash & Bank | Seedha in groups ke ledgers |
| Ledger statement | Kisi bhi ledger par tap → period ki sabhi entries, Dr/Cr totals, opening/closing |
| Stock Items | Closing qty, rate, value |
| Day Book / Sales / Purchase / Payment / Receipt | Vouchers; tap karke ledger entries, items, narration |
| Export | Har list ⋮ menu se CSV (Excel) me save |

Period (From/To) company screen par badal sakte hain.

## Kaise kaam karta hai (technical)

Tally apne port (default 9000) par XML-over-HTTP server chalata hai. App us port par XML requests bhejta hai:
TDL *Collection* exports (Company, Ledger, Group, StockItem) aur *Day Book* data export (vouchers), aur
response ko phone par parse karta hai. Tally ki data files proprietary format me hoti hain, isliye unhe seedha
padhne ke bajaye running Tally se data liya jaata hai — matlab app use karte waqt **Tally PC par khula hona chahiye**.

## Khud build karna

Android Studio me project kholein, ya:

```bash
./gradlew assembleRelease   # APK: app/build/outputs/apk/release/app-release.apk
```

Requirements: JDK 17, Android SDK (compileSdk 34). Code me koi third-party library nahi hai.

> Signing: `app/tally-viewer.keystore` repo me hai taaki har build same signature se bane aur purane app ke upar
> update ho sake. Play Store ya public distribution ke liye apna private keystore banayein.
