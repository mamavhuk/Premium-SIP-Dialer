# Premium All-in-One Android SIP VoIP Dialer

A commercial-grade Android Native SIP Softphone built with **Kotlin, Jetpack Compose (Material 3 Glassmorphism), Official Linphone SDK (`org.linphone:linphone-sdk-android`), Room Database, and Android Keystore AES-256 Encryption**.

## Key Features Implemented
1. **Simultaneous Multi-SIP Account Registration:** Register BanglaCall, ICC Talk, Personal SIP, and Office SIP simultaneously with UDP/TCP/TLS and STUN support.
2. **Android Keystore Credential Vault:** SIP passwords are never stored in plain text in SQLite or GitHub; they are encrypted via hardware-backed `EncryptedSharedPreferences` & AES-256-GCM.
3. **Direct SIP Media Stream Call Recording:** Auto and manual call recording in `.wav` format directly from the Linphone RTP audio engine (bypasses Android 10+ third-party mic blocks).
4. **Post-Call Real-Time RTP Analytics Popup:** Displays real Min/Avg/Max Latency (RTT), Jitter, Packet Loss %, MOS Score, Codec, Bitrate, and RTP Upload/Download Bytes after every call.
5. **Proximity Sensor & Smart Screen Lock:** Automatically turns the screen off during earpiece calls and keeps it active on speakerphone.
6. **GitHub Actions Auto-APK Builder:** Every push automatically compiles the Android Studio project and uploads `Premium-SIP-Dialer-APK` under GitHub Actions Artifacts.

---
*This App Is Made With AI*
