# INFOTECH HRMS: reverse-engineering report (Android app + web login)

**App:** INFOTECH HRMS, package `com.infotech.mobileattendancenew`
**Version:** 2.0.261020 (version code 20261022), min SDK 23 (Android 6), target SDK 36
**Source analyzed:** `com.infotech.mobileattendancenew_2.0.261020.xapk` (base APK, ARM64 libraries, xhdpi images), plus the public web login pages (`www.info-tech.com.my` → `www.infotech-cloudhr.com.my` → `login-infotech.com`)
**Base APK SHA-256:** `6399361a8bbf2d180aad341ba6877ec6256144844bdb4350880e0c46ab3ed689`
**Report date:** 2026-11-03 (revision 6, see the change log at the end)
**Tools:** REA (rea-agents 6.8.0) for the APK signature and manifest/resource decode (apktool 3.0.3); JADX 1.5.6 for the Java/Kotlin decompile; `llvm-nm` / `strings` for native library exports; Python scripts for the module, endpoint and response-model indexes; plain HTTPS `GET` requests for the public web pages

> **Scope.**
> - **Android app:** static analysis only. The app was never run, and **no API calls were made** to Info-Tech's servers.
> - **Web:** only the **public pages a visitor's browser receives** were downloaded (HTML, headers, inline scripts). There was no login, no form submission, no `?handler=` call, and no scanning.
> - **Not visible:** server-side rules (geofence radius, face-match threshold, approval routing, payroll calculations). Where those are discussed, they're marked as inferred.
> - **Left out on purpose:** embedded keys, decrypted URLs, licence keys and personal data, and any way to defeat the attendance, location, device-integrity or face and liveness checks.
> - **The security findings (§6) are static observations.** They need confirming by the vendor or through an **authorized** test, and should be reported through responsible disclosure, not published.

---

## Executive summary

**What the app is:** INFOTECH HRMS is Info-Tech Systems' all-in-one employee app for **SG, MY, HK, AU, NZ and India**. It covers:
- **Time attendance:** GPS / Wi-Fi / geofence / face / kiosk clocking, overtime, live field tracking and work-from-home geofencing.
- **Scheduling:** three scheduling engines, shift grab, availability and publishing.
- **Leave and flexi-work.**
- **Claims:** OCR receipts, GST and mileage.
- **Payroll:** payslips, plus IR8A, IR56, EA, TDS and IT-declaration tax documents.
- **HR:** profile changes with approval, onboarding, memos, policies, grievances, training, rooms and rewards.
- **Assistants:** an AI HR chatbot and a voice assistant.

It's a native Android app (2,622 own classes) talking to about **368 API endpoints** over Retrofit. Almost every request body is **AES-encrypted with keys built into the app**.

**How access works:** the server decides every permission. The app reads per-module rights (`EMPLOYEE` vs department access), approver and super-admin flags and licence tiers, and shows or hides screens. Approvals go up to **3 levels + HR**, with backup officers.

**Top findings** (details in §6):

| # | Finding | Severity |
|---|---|---|
| 10 | **Singpass private key shipped inside the APK** (signs client assertions, decrypts ID tokens) | High |
| 21 | **Web login's legacy Singpass flow gives the Singpass private keys to the browser** before login (from page code, not verified) | High |
| 11 | **Exported, unprotected SSO data provider** holding the email, **password (apparently plain text)** and session token, readable or writable by other apps on the phone | High (needs confirming) |
| 15 | **A real-looking employee profile (personal data) bundled in the APK** | Medium (PDPA) |
| 4 | Decrypted request bodies can be copied to Firebase per employee, if the customer flag is on | Medium |
| 1 / 2 / 3 | Plain HTTP allowed, no certificate pinning, app encryption relies on built-in keys | Medium |
| 12 / 16 | Staging URLs, old keys and a hard-coded Luxand licence in the app | Medium |
| 22 / 23 | Weak CSP and version headers on the legacy Cloud HR host; old moment.js | Low–Medium |

**Recommended next steps:**
1. **Responsible disclosure** to Info-Tech (and GovTech Singpass for #10 and #21): remove the client-side Singpass keys and **rotate the keys**, protect or unexport the SSO provider, and remove the bundled profile.
2. **Authorized testing** of server-side access control (§8.6), especially approver and admin actions and the generic `AdvSCH/{endPoint}` route.

---

## Contents

1. At a glance · 2. Feature map · 3. Architecture · 4. Main flows · 5. Data stored on the device · 6. Security and privacy observations · 7. What isn't covered · 8. Roles, permissions and administrator logic · 9. Module-by-module detail (9.1–9.24) · 10. Native code, SDKs, permissions and the SSO framework · 11. Endpoints not covered elsewhere · 12. Inside the large classes · 13. Layouts and translations · 14. Bundled assets · 15. Native face and liveness engines · 16. Web portal (passive) · Appendix A: All endpoints · Appendix B: Response models · Change log

---

## 1. At a glance

| Item | Finding |
|---|---|
| App type | Native Android, Java + Kotlin (8 `classes*.dex` files). Not Flutter or React Native |
| Own code size | 2,622 Java files under `com.infotech.*` (`mobileattendancenew`, `ssoframework`, `customviews`) |
| Networking | Retrofit + OkHttp + Gson. **~368 endpoints** across 10 API interfaces (331 `POST`, 32 `GET`, 4 `PUT`, 2 `DELETE`); response models resolved for 354 of 364 signatures (Appendix B). Bodies are app-encrypted `{"str": …}` |
| Local database | Realm (58 model classes) plus Room for the offline location queue (`offline_location_data`) |
| Push and analytics | Firebase (project `infotech-hrms-1d0c1`): Cloud Messaging, Analytics, Crashlytics, Performance, anonymous Auth, Realtime Database and Firestore logging, Storage, Remote Config. No third-party ad or marketing SDKs |
| Face recognition | Luxand FaceSDK (`libfsdk.so`, ~46 MB, on-device matching), Google ML Kit face detection, server-side `VerifyFace`; **liveness** from MiniVision Silent-Face-Anti-Spoofing (`libengine.so`) + OpenCV blur/brightness checks + MediaPipe hand check |
| Maps and location | Google Maps and Fused Location, Google Time Zone API, OneMap (Singapore) reverse geocoding, OpenStreetMap Nominatim |
| Sign-in | Info-Tech SSO, Singapore **Singpass**, Microsoft Entra (MSAL `msauth://`), Firebase generic IdP, 2FA by OTP or approval on a trusted device, biometric and pattern app lock |
| Regions | Singapore, Malaysia, Hong Kong, Australia, New Zealand (separate server sets per country, plus staging copies), with **India** payroll and tax support (TDS, IT declarations) |
| Languages | English + full Malay, Simplified and Traditional Chinese (TW, HK) and Thai translations |
| Web portal | `www.infotech-cloudhr.com.my` (IIS / ASP.NET 4.0 WebForms) behind the central SSO `login-infotech.com` (ASP.NET Core, Cloudflare); see §16 |
| Signing | One signing certificate across v1, v2 and v3, SHA-256 `041c62b38cc90a03296f417fe087cfde4b00aa8d7ed9c8589964e7f070533287`, plus a Google Play source stamp |

---

## 2. Feature map

Built from the manifest's activities and the Java packages under `com.infotech.mobileattendancenew`.

| Module | Package or screens | What it does |
|---|---|---|
| **Onboarding and login** | `activity.Initial.*` (`SplashScreen`, `Login`, `ForgotPassword`, `BiometricAuthentication`, `PatternAuthentication`, `signpass.SingPassActivity`, `WalkThroughActivity`) | Company code or QR setup, email and password login, SSO, Singpass, 2FA, biometric or pattern app lock |
| **Dashboard** | `AppDashboard`, `AppDashboardNew`, `upgradedDashboard.*`, `fragment.dashboardFragment` | Home tiles, summary graphs (`sliderGraphView`), quick clock-in |
| **Attendance (TMS)** | `activity.attendance.*`, `fragment.attendance.Attendance` | Clock IN and OUT with GPS, Wi-Fi, photo or face, job code and remarks. History, attendance requests and corrections, OT requests and approvals, branch schedule updates |
| **Face registration and verification** | `activity.FaceDetectionML.*`, `antispoofing.*` | Register a face, verify the face at clock-in, liveness checks (eye check in kiosk mode, hand-occlusion detection) |
| **Work from home / flexible work (FWA)** | `WfhFilterActivity`, `FWA/*` endpoints | Apply, cancel, approve and see a summary of flexible-work days. Location pings while working from home |
| **Live and field tracking** | `liveTracking.*`, `service.workatloc.*`, `repository.FieldTrackingAPI` | Background GPS tracking for field staff, work-at-location alarms, offline queue |
| **Scheduling** | `eScheduleNew.*`, `autoSchedule.*` | Rosters and shifts, shift swaps ("change with employee"), availability, pre-OT and OT requests, auto-OT calculation. Separate token-based "Auto Schedule" module |
| **Leave** | `activity.leave.*`, `flexibleLeave.*` | Apply (including drafts), balances, calendar, planner, approvals (group split), off-in-lieu, eligibility |
| **Claims (expenses)** | `activity.claim.*` | Apply, drafts, history, approvals, eligibility and limits, GST, mileage via map route (`MapRouteDirectionActivity`) |
| **Payroll** | `activity.payslip.*`, `activity.incometax.*` | Payslips (PDF via PDFium and pdf.js), income tax forms |
| **Profile / personal particulars** | `activity.profile.*` | Edit address, bank, contact, spouse, children, education and national service details. Changes go through approval |
| **New-hire onboarding** | `activity.onboarding.*` | Pre-employment forms, education, children and tax details, onboarding chatbot |
| **Messages and letters** | `activity.messages.*` | Memos, letterhead documents |
| **Company policies** | `activity.policy.*` | Policy documents |
| **Tickets and feedback** | `activity.TicketingSystems.*`, `grievance.*` | Helpdesk tickets, grievance and feedback tickets with attachments |
| **AI chatbot** | `chatbot.*` | HR assistant chat (`GetBotReply`, `GetChatResponse`, chat history), plus a voice waveform UI (`voicewave.html`, `siriwave.js`) |
| **Training and meeting rooms** | `fragment.trainingManagement`, `meetingRoomModule.*` | Training lists, meeting room booking |
| **Rewards and referral** | `activity.rewards.*`, `activity.ReferandEarn.*` | Redeem rewards, refer-and-earn form and history |
| **Kiosk mode** | `Utility.isKioskModeEnabled` (14 call sites) | A shared device used as a clock-in terminal. Sends `Mode: "Kiosk"` |
| **Voice assistant** | `voice.*` | Spoken commands to open modules and fill in leave applications (§9.19) |
| **Job / project costing** | `projectCost.*` | Job-cost codes, requests and approvals linked to clock-ins (§9.20) |
| **Training management** | `trainingManagement.*` | Company and external course requests, attendance and certificates (§9.22) |
| **Release notes** | `releaseNotes.*` | What's-new posts with star, like and comment (§9.23) |
| **Payroll and tax by country** | `IncomeTax`, `Payslip` handlers | SG IR8A, HK IR56, MY EA, India TDS and IT declarations, AU/NZ payslips (§11.1) |

---

## 3. Architecture

```
UI (Activities / Fragments / ViewModels)
        │
        ▼
common/Post.java  ── builds request models (ClockRequest, LoginRequest, …)
        │            encrypts JSON → VKModel {"str": "<ciphertext>"}
        ▼
common/Api.java   ── Retrofit clients per server, OkHttp interceptor adds headers
        │
        ├── EleaveApi           (leave, claims, payroll, profile, etc.: 193 endpoints)
        ├── AttendanceApi       (clocking, face, tracking: 30)
        ├── FieldTrackingAPI    (schedule, FWA, OT, chatbot: 40)
        ├── EScheduleApiV1 / EScheduleNewFlowApi / AutoScheduleTokenApi
        ├── SSOApiService       (ssoframework: login, 2FA, devices)
        └── OnemapApi / SingPassApi / GoogleInfoTechApi
        │
        ▼
Local: Realm (cached lists, offline clock-ins, face images), Room (offline locations),
       SharedPreferences (tokens, settings, flags)
```

### 3.1 Choosing the server

- The base URLs aren't stored as plain text. Each one comes from a **native method** (for example `Api.ATTENDANCE_BASE_URL()`, `LEAVE_BASE_URL()`, `CLAIM_BASE_URL()`, `TICKETS_URL()`, and SG, MA, HK, AUS and NZ variants) and is then decrypted in Java with **AES** (`Utility.getBase`). The key and IV come from further native methods (`Constants.EncryptionKey()`, `EncryptionInitVector()`, `aes()`, `chiper()`).
- `Api.getEmnageURLCountry()` and `getAttendanceURLCountry()` pick the server set from `Utility.getCountryType(context)`, a country code (`"0"`, `"1"`, …) saved at login.
- If no country is set, the app falls back to a URL saved from the **company QR code or company code** (`QR_Code_APi_t`, `sp_base_url_emanage`). Customers on dedicated or self-hosted servers likely work this way.
- Separate servers are used for tickets, schedule (full and "my schedule"), the auto-schedule module and its token service, and face image blob storage.

### 3.2 Request format and headers

- **Request bodies:** almost every body is a single JSON field, `{"str": "<AES-encrypted JSON>"}` (`models/VKModel`). The server decrypts it to get the real request.
- **Headers:** the OkHttp interceptor in `Api.java` adds obfuscated headers, each value encrypted:

| Header | Meaning (from the code) |
|---|---|
| `IToken` | Session token saved after login (`Constants.SP_ITOKEN`) |
| `Authorization: Bearer …` | SSO token, only for SSO countries |
| `4v9d` | An encrypted value from preferences (likely the customer or company identifier) |
| `7u9d` | Device GUID (`Utility.getGUID`) |
| `9p1d4r` | The user's email (re-encrypted) |
| `20y16e` | The constant `"Mobile"` (client type) |
| `isNew` | `"V2"` (API generation) |
| `isVerified` | `false` on the first API call, then `true` |
| `deviceType`, `mappversion`, `mappos`, `uniqueID` | Device and app version info (on some clients) |

- **Timeouts:** the SSO client uses 120-second connect, read and write timeouts.

---

## 4. Main flows

### 4.1 Login

1. **Company setup:** the user enters or scans a company code, which sets the customer ID, the country type and possibly a custom server URL.
2. **Login request** (`LoginRequest`): `CustomerCode`, `UserEmail`, `UserPassword`, `IMEINo` (a device identifier: the stored unique ID or the Android ID), `SsoType`, `plaintext`.
3. **Login endpoints:** the legacy `Login` (EleaveApi), `Authenticate` (SSO), or `Auth/login` (auto-schedule).
4. **Single-device policy:** `LoginTrackIMEI` (`doForceLogin`) suggests the account is tied to one device, and logging in on a new device needs a forced login or approval.
5. **2FA:** `CheckFor2FALoginRequest` → `SendOTPFor2FA` / `GenerateAndValidateOTP`, or approval from an already-trusted device (`LoginRequestApprRej`, `RejectLoginApproval`). Devices are registered with `RegisterDevice` / `RegisterMobileDevice`, and the push token with `DeviceToken`.
6. **Singpass (SG):** OAuth with `id.singpass.gov.sg` (and `stg-id` for staging), using the `/token` exchange.
7. **Microsoft:** MSAL redirect `msauth://com.infotech.mobileattendancenew/…`.
8. **Local unlock:** after login, the app can be protected with **biometrics** or a **pattern lock**.
9. **Module tokens:** scheduling modules swap the main session for their own token (`ModuleSwitch/token` → `Auth/module-entry/mobile` or `Auth/module-entry/sso/infotech`, refreshed with `Auth/refresh`).

### 4.2 Clock-in and clock-out

Code: `fragment/attendance/Attendance.java`, `common/Post.java` (`postClockWithoutPhoto`, `postClockWithPhoto`, `postClockInLuxand`, `markMyClocking`).

**1. Location.** Fused Location is shown on a Google map.
- The app reverse-geocodes the address with Google, or OneMap in Singapore, and refreshes it when the user moves **more than 50 m** (`distanceTo(...) > 50`).
- It lists nearby places within **500 m** for the user to pick a location name.

**2. Choose the clock path.** The company's settings decide which one is used:

| Path | Endpoint | Used when |
|---|---|---|
| No photo | `timelog` (`clockWithoutPhoto`) | Location-only attendance |
| Photo + verify | `UploadPhotoWithVerifyV1` | The selfie is checked on the server |
| Photo + object | `UploadPhotoWithObj` | Photo attached to the record |
| Non-Luxand photo + verify | `UploadPhotoWithVerify` | Alternative face engine |
| Face verify only | `VerifyFace` (Luxand), `VerifyFaceV1` | Face match before clocking |

**3. Payload** (`ClockRequest`, then encrypted into `VKModel`):

| Field | Content |
|---|---|
| `UserId`, `CustomerID`, `CardNoC`, `NRICNo` | Who is clocking |
| `PunchAction` | `IN` / `OUT` (or the schedule-specific action) |
| `clocktimeD`, `timeZoneName` | Time, plus a UTC offset in seconds worked out **from the GPS location** (`TimezoneMapper`), with explicit daylight-saving handling for AU, US, UK and some EU zones |
| `LatN`, `LngN`, `LocationNameC` | GPS coordinates and the chosen place name. If lat equals lng, both are sent as `0` (treated as invalid) |
| `WIFISSID` | Current Wi-Fi network name (and a cached `WifiBssid` Realm model), used for Wi-Fi-based attendance |
| `Deviceid`, `IMEINo` | Android ID and the stored device unique ID |
| `FileAttachBase64`, `MatchPercentage`, `VerifyType`, `isRegistered` | Selfie, face-match score and verification method |
| `isDevOptionEnabled` | Whether Android **Developer Options** are on (`Utility.isDevModeEnabled`) |
| `Temperature` | Optional body temperature (a COVID-era feature) |
| `JobCode`, `remarks`, `IsException` | Job costing, a note, and an exception flag |
| `Mode` | `"Kiosk"` or `"MobileAttendance"` |
| `AppID`, `AppNameC`, `AppLicenseTypeID` | `1` / `"TMS"` for time attendance, or `3` / `"SCHEDULE"` for schedule customers, plus the license type |
| `SessionID`, `language` | Session and UI language |

**4. Where the decision is made.** *(Corrected after a closer look.)* The app doesn't use Android's `GeofencingClient`, but it **does run its own distance check on the phone**:
- **Job or work location:** if the chosen job (`JobResult` in Realm) has a latitude and longitude, `Utility.getDistance(jobLat, jobLng, currentLat, currentLng, radius)` compares the current position against the company-set radius (`sp_geofencing_meter…`). If you're outside it, the app blocks the punch with *"Clocking allowed only when you are in %s."* (`%s` is the job or location name).
- **Wi-Fi restriction:** when `sp_wifi_restricted` is on, the connected network must match one of the assigned BSSIDs (`WifiBssid` Realm, `sp_wifi_bssid_size`). Otherwise the app shows *"Please connect to the assigned wifi network to do clock in"*.
- **Moving device:** the app blocks the punch while the GPS fix is unstable: *"Your device is currently moving. Please wait for stable GPS capture."*
- **Flexi-work override:** a flexi or work-from-home setting can turn off the geofence, work-location and Wi-Fi rules for the day.

The punch is then sent with coordinates, Wi-Fi and device info. The **server can still reject it**, so the phone checks don't replace server-side rules.

**5. Integrity signals sent or checked:**
- **Developer Options flag:** the user is warned *"As the developer mode is enabled, Your Attendance activity will be notified to your HR."*
- **Root check** (`common/RootUtil`).
- **Mock-location detection:** *"You are using fake location spoofing application. Please stop location spoofing and try again"* (`sp_location_spoofing`).
- **Liveness** (`antispoofing.*`).

**6. Offline.** If there's no network, the punch is saved in Realm (`OfflineClockIn`, `ClockInObject`). `AttendanceSyncWorker` / `OfflineDataSyncService` upload it later, triggered by `ConnectivityReceiver` / `NetworkChangeReceiver`.

**7. On success:** `processClockInSuccess()` sets `SP_CLOCK_IN_OUT_SUCCESS` and refreshes the history (`AttendanceHistory/GetAttedanceHistory`).

### 4.3 Face recognition

- **Registration:** `FaceRegisterActivityML` / `FaceRegisterCameraXLActivityML` / `AttendanceFaceRegisterActivity` capture the face, and `RegisterPhoto` (Luxand) or `RegisterPhotoV1` upload it. Registered images can be downloaded again (`DownloadPhotoV1`) and are cached locally (`AttendanceFaceImages` in Realm).
- **Engines:** ML Kit finds the face in the camera frame. Luxand FaceSDK (native) and the server compare it with the registered template. The client sends a `MatchPercentage`, and the server makes the final call with `VerifyFace` / `VerifyFaceV1`. The match threshold isn't visible in the decompiled Java (it's either in native code or on the server).
- **Liveness and anti-spoofing:** `AntiSpoofingFacialRecognition` (camera preview analysis), `KioskEyeCheck` (eye or blink check on kiosks) and `HandOcclusionChecker` (MediaPipe `hand_landmarker.task`, which rejects frames where a hand covers the face).

### 4.4 Background and live tracking

- **Who it applies to:** field staff. `liveTracking.BootReceiver` only acts when `getCountryType == 1` **and** the `FIELDSTAFF` flag is set for the user.
- **Starting:** on `BOOT_COMPLETED` it checks the location permissions (and `FOREGROUND_SERVICE_LOCATION` on Android 14+) and restarts the foreground `LocationService` / `LocationForegroundService`. `PermissionMonitorService` watches for revoked permissions.
- **Frequency:** `LocationRequest.Builder(PRIORITY_HIGH_ACCURACY, interval)` with **60 s by default**. The interval can come from the server's tracking profile (`MobileTracker/GetMobileTrackingDetails`, `GetMobileTrackingDetailsV1` for the end time). Another path uses a 7.5-minute fastest interval (`setFastestInterval(450000)`), and WorkManager uses a 15-minute periodic interval.
- **Upload:** `SaveMobileTrackerV1` sends position batches and `SaveMobileTrackingStatus` sends start and stop status. When offline, records go to Room `offline_location_data` and are synced later.
- **Work-at-location / WFH:** `AlarmReceiverWAT` uses exact alarms (`setExactAndAllowWhileIdle`) and `WorkAtLocationWorker` checks location at set times. Results go to `PushNotificationTimeLogOnline` / `Offline` / `PushNotificationTimeLog` (`outOfLocationCallApi`), and `PushNotificationTimeLogHistory` shows the history.

### 4.5 Leave, claims, payroll, profile

These follow the same pattern: list, apply, then approve. All go through `EleaveApi` (193 endpoints), and lists are cached in Realm (`LeaveStatusObject`, `ClaimObject`, `PayslipObject`, …) so they show offline. Claims support GST, sub-claims, group balances and map-based mileage. Profile edits create approval requests (`ApprovalDetailActivity`) rather than changing data directly. The full list of endpoints is in the appendix.

### 4.6 AI chatbot

- **Two chats:** an employee chat (`GetBotReply`, `GetChatResponse`, `GetChatHistory`, `DeleteChatHistory`, `AddFeedback`) and an onboarding chat (`Onboarding/*`).
- **Source documents:** the server provides them (`GetBlobFiles`, `Onboarding/GetBlobFiles`).
- **Model provider:** no OpenAI, Gemini or Azure model calls were found in the client, so the model runs on Info-Tech's servers.

---

## 5. Data stored on the device

| Store | Contents |
|---|---|
| **Realm** (58 models) | User profile, attendance history, **offline clock-ins**, **face images** (`AttendanceFaceImages`), payslips, income tax, leave and claim lists, memos and letters, policies, grievances, Wi-Fi BSSIDs, tracking records |
| **Room** | `offline_location_data` (queued GPS points) |
| **Room** (grievances) | Cached grievance tickets (`grievance/db`) |
| **SQLite `UserDB`** (SSO framework) | `Users` table with the **email, login password (apparently plain text) and session token**, plus app lists. **Exposed through an exported, unprotected ContentProvider**, see §10.5 / finding #11 |
| **Face biometrics** | Luxand face templates (`Utility.saveTemplate*`) and the face-tracker memory `.dat` file in the app's data folder (§15.2) |
| **Local files** | Voice-command recordings from `MediaRecorder` (§9.19 / §10.3); generated payslip PDFs when sharing |
| **SharedPreferences** | `IToken`, SSO token, email (stored encrypted with the app's own AES), customer ID, country type, base URL, feature flags (`FIELDSTAFF`, `ISWFH_ENABLED`, kiosk, logging flags) |

No Realm `encryptionKey(...)` or `EncryptedSharedPreferences` call was found in the decompiled code, so the local databases appear to be **unencrypted at rest**. They rely on Android's app sandbox, and `allowBackup="false"` keeps them out of cloud and adb backups.

---

## 6. Security and privacy observations

| # | Observation | Evidence | Notes |
|---|---|---|---|
| 1 | **Unencrypted HTTP allowed** | `android:usesCleartextTraffic="true"` | If a customer's server URL is `http://`, traffic (including the encrypted `str` bodies and headers) goes over plain HTTP |
| 2 | **No certificate pinning** | No `CertificatePinner` found | Normal Android trust store. No "trust-all" `X509TrustManager` / `HostnameVerifier` found either, which is good |
| 3 | **App-level encryption with built-in keys** | `Utility.encrypt` / `getBase`, keys from native `Constants.*()` | Hides the data but doesn't truly secure it: anyone with the APK can recover the keys. TLS is the real protection |
| 4 | **Request bodies can be logged to Firebase** | `FileLogger.exportLogToFirebase` (called on **every** API response in the `Api.java` interceptor) | Signs in to Firebase anonymously, then writes the URL and status, and **when the per-customer flag `isCustomerAvailableforReqlog` is on, the decrypted request body** (passwords re-encrypted), to a Firebase Realtime DB under `…/EMP_<userId>/Time_<ts>`. Another logger records device ID, WFH flag, GPS state, location permission, app state and Android version |
| 5 | **Device binding** | `IMEINo`, `LoginTrackIMEI`, `7u9d` GUID header | The account is tied to a device ID |
| 6 | **Background location** | `ACCESS_BACKGROUND_LOCATION`, boot receivers, foreground location service | Only for field-staff users with tracking enabled. High accuracy, every 60 s by default |
| 7 | **Device integrity reporting** | `isDevOptionEnabled`, `RootUtil`, mock-location check | Sent with clock-ins or used to block them |
| 8 | **Wide permissions** | Microphone, camera, NFC, clipboard (`READ_CLIPBOARD`), activity recognition, `AD_ID` and ad-attribution permissions | Microphone is likely for the voice chatbot and NFC possibly for card-based clocking. The `AD_ID` / ad-services permissions come from Google libraries |
| 9 | **Exported components** | `SplashScreen`, `AttendanceMainActivity`, `AttendanceFaceRegisterActivity`, `FaceRegisterCameraXLActivityML`, `MyFirebaseMessagingService`, boot / SMS / connectivity receivers, `ssoframework…AuthorizeCredentials` provider | Worth reviewing in a proper, authorized security assessment (in particular the exported face-registration activities and the `AuthorizeCredentials` provider) |
| 10 | **Singpass private key in the APK** | `Utility.getSingPassJWTPrivateToken("release")` → `ECDSASigner` / `ECDHDecrypter` (§10.1) | The OIDC client key is in every copy of the app; the token exchange should run on a server. **High** |
| 11 | **SSO credential store exposed to other apps** | Exported `AuthorizeCredentials` provider, no permission; `UserDB.Users` has the email, password and token (§10.5) | Any app on the device may be able to read or change them. **High, needs confirming on a test device** |
| 12 | **Staging URLs and old keys shipped** | `STG_*` and `*OLD` JNI functions in `libmyapplication.so` (§10.1) | Exposes staging servers; keeps old keys valid for decryption |
| 13 | **Voice recordings: upload code present** | `UploadingTaskFirebase.uploadFile*` to Firebase Storage under `<userId>`, currently called with `null` (§10.3) | Disabled in this build; recordings are kept locally |
| 14 | **Old Luxand FaceSDK build** | Clang 3.8 / 5.0, libpng 1.6.21 (2016) inside `libfsdk.so` (§10.1) | An old native library that may contain known libpng issues |
| 15 | **Personal data in the APK** | `assets/profile_detail.json`: a full employee profile (addresses, phones, emergency contact), unused (§14) | Ships to every install; possible PDPA issue if real |
| 16 | **Hard-coded Luxand licence key** | `FSDK.ActivateLibrary("…")` in Java (§15.2) | Licence can be extracted and reused |
| 17 | **FileProvider shares all of external storage** | `file_paths.xml` `external-path path="."` (§13.3) | Broader than needed |
| 18 | **Biometric templates stored on the phone** | Luxand templates and tracker `.dat` in app data (§15.2) | Biometric data at rest; no encryption of these files was seen in the code (not fully verified) |
| 19 | **Clock-in checks copied many times** | Geofence check ×11, photo check ×11 in `Attendance.java`, ×25 in `EScheduleAttendance.java` (§12.2) | Paths can drift apart; review each one |
| 20 | **Generic `AdvSCH/{endPoint}` call** | `apiRequestResponseCheck` (§11.7) | Server must authorize every route |
| 21 | **Web login sends Singpass private keys to the browser (legacy flow)** | `login-infotech.com` inline JS: `GetDetails` → `importJWK(iecPrivatekey)`, `SignJWT…sign()` (§16.3) | Code shows the keys are given to unauthenticated visitors; **High**; not verified on purpose |
| 22 | **Weak CSP and version headers on the legacy Cloud HR host** | `infotech-cloudhr.com.my`: `script-src http://* 'unsafe-eval'`, `X-AspNet-Version` (§16.4) | Little protection against cross-site scripting; exposes version details |
| 23 | **Old moment.js on the SSO page** | moment 2.24.0 (§16.4) | Known regular-expression slowdown (CVE-2022-31129) |

---

## 7. What isn't covered

- Server-side logic: geofence radius, Wi-Fi allow-lists, face-match threshold, approval routing, payroll calculation.
- **Native code internals:** §10.1 and §15 identify every library and describe how the app uses the face and liveness engines, but the native code wasn't disassembled and **no secret values were extracted on purpose** (keys, URLs, the Singpass JWK, Firebase references, the Luxand licence).
- **Decompile failures:** the 323 classes JADX couldn't decompile are **all third-party library code**, not Info-Tech's.
- **Every branch of the largest classes:** §12 lists every handler and helper and the structure of `Attendance.java`, but not every branch of its ~7,600 lines was read individually.
- **Actual network traffic and confirmation of the findings:** a dynamic test with your own account, with authorization, would confirm the actual payloads, the server's access-control checks (§8.6) and the exported-provider exposure (§10.5).
- **The iOS app**, and the **web portal past the login page** (§16 covers only the public login pages).
- **Left out on purpose:** any way to get around the geofence, Wi-Fi, mock-location, root or developer-mode checks, or the face and liveness checks.

---

## 8. Roles, permissions and administrator logic

### 8.1 Where permissions come from

The **server sends everything** at login and on each profile refresh. The app only stores the flags and shows or hides screens based on them. There's no role logic baked into the app.

1. **Login response → `UserAuthorization`** (`models/login/leave/UserAuthorization.java`)
   - Identity and tenant: `UserId`, `InfoTechUserId`, `EmpCode`, `CompanyId`, `Customer`, `UserCompanyList`, `CustomerList`, `LoginUserType`, `LoginUserSubType`
   - Licensed modules: `CustomerAppList` (each with a `licenseType`)
   - Per-user rights: **`UserAppRightList`**
   - Role flags:

| Flag | Meaning |
|---|---|
| `EHR_IsSuperAdmin` | HR module super admin |
| `IsETMSPrimaryApprover`, `IsETMSBackupApprover` | Time attendance (ETMS) approver, primary or backup |
| `IsEscheduleApprover`, `IsEscheduleBackUpofficer` | Schedule approver, backup officer |
| `ELEAVE_BackUpOfficer`, `ECLAIM_BackUpOfficer` | Leave or claim backup (delegate) officer |
| `IsEPayrollGrievanceApprover`, `EPayrollGrievanceApproveCodeC` | Payroll grievance approver |
| `IsOnboardingCandidate` | New hire who only sees onboarding |
| `aI_Access` | AI HR chatbot allowed |
| `Enable2FA`, `FaceReconizationRequiredC` | Security policy for this user |

2. **Profile response → `ProfileResult`** (`models/profile/leave/ProfileResult.java`), cached in Realm `UserProfileObject`. Each module has an *approver* and a *super admin* flag, sent as `"YES"` / `"NO"` strings:

| Module | Approver flag | Super admin flag |
|---|---|---|
| Mobile attendance | `MOBILEATTISAPPROVER` | `MOBILEATTSUPERADMIN` |
| Time attendance (ETMS/TMS) | `TMSISAPPROVER` | `ETMSSUPERADMIN` |
| Leave | `LEAVEISAPPROVER` | `ELEAVESUPERADMIN` |
| Claims | `CLAIMISAPPROVER` | `ECLAIMSUPERADMIN` |
| Payroll | `PAYROLLISAPPROVER` | `EPAYROLLSUPERADMIN` |
| HR | `EHRISAPPROVER` | `EHRSUPERADMIN` |
| Appraisal | `APPRAISALISAPPROVER` | `EAPPRAISALSUPERADMIN` |
| Schedule | (branch approver codes) | `ESCHEDULESUPERADMIN` |

   It also carries policy switches: `AllowLogin`, `IsLicenseValid`, `AllowPastLvCancel`, `AllowEmptoSelOtherDeptAprOfficerC`, `ShowSelApproverByEmpC`, `ShowETMSApproverByEmpC`, `IsDisplayCalFilterForApprover`, `isShowFullSchedule`, `ESCHISDEPT`, `ShowAIHR`, `isShowCompanyPolicy`, `EnableKioskBackCamera`, and the anti-spoofing settings `isSpoofingEnabledAndroid` / `AntiSpoof*ThresholdAndriod`.

### 8.2 App rights (`UserAppRightList`)

Each entry is `{UserAppRightID, UserID, AppID, AppRightID, AppRightCode, IsSuperUserAccess, IsSystem}`. In `common/Fetch.java` (around line 1440), the app reads it like this:

| `AppID` | Module | Preference set | If `AppRightCode` ≠ `"EMPLOYEE"` |
|---|---|---|---|
| 1 | TMS (time attendance) | `sp_apptms`, `IS_ATTENDANCE_CALL`, `TMS_APPRIGHTS_ID` | `TMS_DEPARTMENT_ACCESS = true`, `SP_TMS_APP_RIGHT_CODE = "YES"` |
| 2 | Leave | `sp_appleave` | `LEAVE_DEPARTMENT_ACCESS = true` |
| 3 | (Schedule/other) | — | — |
| 4 | Payroll | `sp_apppayroll` | `PAYROLL_DEPARTMENT_ACCESS = true` |
| 5 | Mobile attendance | `sp_appattendance`, `IS_ATTENDANCE_CALL` | — |
| 7 | HR (EHR) | `sp_apphr` | `EHR_ACCESS_TYPE = true` |
| 10 | (licence or schedule related) | — | — |

So the app has **two levels**: `EMPLOYEE` (self-service only) and anything else, which unlocks **department access**, meaning the user can see and act on other employees in their department. The exact non-employee codes (for example manager or HR admin) are defined on the server.

### 8.3 Licence tiers

`CustomerAppList.licenseType` turns features on per company: **`PROFESSIONAL`**, **`ENTERPRISE`**, **`ENTWITHESCHEDULE`** (enterprise with scheduling) and **`ULTIMATE`**. These are saved per module (`sp_appattendance_professional_LicenceType`, `…_enterprice_…`, `…_ultimate_…`, `SP_TMS_ENTERPRISE`, `SP_TMS_PROFESSIONAL`, `SP_TMS_LICENSE_APP_ID`) and decide which menus and features appear. `appLicenseTypeID` is also sent with every clock-in (`AppLicenseTypeID`).

### 8.4 What the roles unlock in the app (UI gating)

| Preference | Set from | Read in | Effect |
|---|---|---|---|
| `SCHEDULE_SUPER_ADMIN` | `ESCHEDULESUPERADMIN` | 20 screens (`FullScheduleFragment`, `BranchRequest`, `ApprovePreOT`, `TransactionFragment`, `BranchSelectionDialog`, dashboards …) | Full schedule for all branches, branch selection, pre-OT approval, transaction list |
| `SP_EHR_SUPERADMIN_LOGIN` | `EHR_IsSuperAdmin` | `GrievancesMainActivity`, dashboards | Grievance admin views (approve, others' tickets) |
| `SP_EHR_APPROVER`, `SP_EHR_SUPER_ADMIN` | `EHRISAPPROVER` / `EHRSUPERADMIN` | profile approval | Approving employees' profile changes |
| `SP_PAYROLL_APPROVER`, `SP_PAYROLL_SUPER_ADMIN` | payroll flags | payroll and grievance | Payroll grievance approvals |
| `MOBILE_ATTENDANCE_APPROVER` / `_SUPERADMIN` | mobile attendance flags | dashboard | Approver tiles |
| `SP_IS_ETMS_BACKUP_APPROVER` | `IsETMSBackupApprover` | `Utility` | Backup approver acts when the primary isn't available |
| `SP_ATTENDANCE_APPROVER_SELECT_EMP`, `SP_LEAVE_APPROVER_SELECT_EMP`, `SP_LEAVE_APPROVER_SELECT_OTHER_DEPT` | `ShowSelApproverByEmpC`, `AllowEmptoSelOtherDeptAprOfficerC` | `AttendanceRequestApply`, leave forms | The **employee picks their approver** (and possibly one from another department). The chosen ID is sent as `EmployeeSelApproverUserid` |
| `SP_SHOW_OT_REASON_APPROVER` | profile | OT screens | Shows the OT reason to approvers |
| `ShowAIHR` / `aI_Access` | profile, login | `ChatDetailsActivity` | AI HR chatbot visible |

### 8.5 Approval workflow model

- **Up to 3 approval levels**, plus an HR level. The response models carry `FirstLevelApproverUserId` / `SecondLevelApproverUserId` / `ThirdLevelApproverUserId`, matching `…ApproveStatus`, `…ApproverName/Email`, `…ApprovedDateTime`, `approver1Remark…approver3Remark`, `approvedLevel`, `pendingApprover`, `NextLevelApproverId/Name/Email`, and `ApprovedUpto` (how far up the chain a request has gone).
- **Module-specific chains:**
  - Claims: `ECApproveS1CodeC`, `ECApproveS2CodeC`, `ECApproveHRCodeC` (supervisor 1 → supervisor 2 → HR)
  - Schedule: `schapproveS1codec`, `schapproveS2codec`, `schapprovehrcodec` and the branch versions `schBranchApprover{S1,S2,HR}CodeC`, plus `isBranchApprover`
  - Leave: `EmpSelELApproveSupCodeC` (supervisor chosen by the employee)
  - Grievance: `GrievanceApproverUserId`, `GrievanceManagerEmpNameC` / `EmailIdC`, `CanApproverReplyBack`
- **Approve or reject requests send:**
  - `ApproveRejectRequest`: `{ReqId, ApprovedLevel, Remarks, userid, strCustomerGUIID, IsCancelInitiated}`
  - `EclaimApproveRequest`: `{ClaimStatus, companyID, EClaimApproveList, intApplicationType, language, strCustomerGUID, UserId}`
  - `ApproveRequest` / `ApproveDetailRequest`: `{intCompanyID, intApplicationType, strCustomerGUID, strEmpCode, SupervisorUserId, …}`
  - `PreOTApprovalRequest`: `{userId, strCustomerGUIID, RequestList, ApproverRemarks, IsBtnStatus}`
- **Schedule roles** (`roleId`, `roleName`, `RolesList`, `EmpRoleCount`, `filterRole`) are **job roles inside a shift** (for example how many cashiers a session needs), not access rights.

### 8.6 Notes for a security review

- **Hiding a screen isn't security.** All role checks in the app only decide what's *shown*. The real check has to happen on the server.
- **The client sends identities.** Approve and reject calls include the acting user's ID and level (`userid`, `UserId`, `SupervisorUserId`, `ApprovedLevel`) and the customer GUID in the (app-encrypted) body. Whether the server checks these against the session token (`IToken` / Bearer) rather than trusting them **can't be seen from the app**. It's the main thing an **authorized** test should check (broken access control / IDOR: can an `EMPLOYEE` call approver endpoints, or approve as another user or level?).
- **Employee-chosen approvers.** When `ShowSelApproverByEmpC` / `AllowEmptoSelOtherDeptAprOfficerC` are on, the employee's request decides who approves it. The server should limit that choice to valid approvers.
- **Super-user flags.** `IsSuperUserAccess` / `IsSystem` exist on app rights but aren't read in the decompiled client code. They're likely used only by the web admin portal.
- **No admin console in the app.** No user, role or company management screens exist in the app. Administration (assigning roles, approvers, licences, geofences, tracking profiles) is done in Info-Tech's web portal.

---

## 9. Module-by-module detail

How each module was mapped: the screens (Activities, Fragments and bottom sheets) in its packages; the API calls made from those screens, including calls through the shared `Post` / `Fetch` wrapper classes; the request models it fills in; the validation and error messages it shows (from `res/values/strings.xml`); and the settings flags (`SharedPreferences`) it reads. Endpoint names are the Retrofit paths; the full list is in Appendix A. Quoted messages are the app's own English text.

---

### 9.1 Login, SSO and device security

**Screens:** `SplashScreen`, `Login`, `SSOLoginActivity`, `SingPassActivity`, `ForgotPassword`, `ChangePassword`, `OtpDialogFragment` / `MultipleOtpDialogFragment`, `BiometricAuthentication`, `PatternAuthentication`, `DeviceLockActivity`, `SettingsMainActivity`, `ProfileSwitchBottomSheet`, `WifiUpdateActivity`, `AnnouncementActivity` / `AnnouncementDetailActivity`

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Login | `Login`, `Authenticate`, `GetAuthUrl`, Singpass `/token` |
| Profile load | `GetUserProfile`, `validateemployeechange` |
| Device and push | `RegisterDevice`, `RegisterMobileDevice`, `DeviceToken`, `LoginTrackIMEI` |
| 2FA | `SendOTPFor2FA`, `ActivateOTP`, `ActivateNewProfilebyOTP` |
| Password | `FP`, `EmpCP/EmpChangePassword` |
| Session and settings | `Logout`, `NotificationSetting`, `SecurepayslipandIR8A`, `GetAllAppVersions` |
| Badges | `GetApprovalCount`, `GetEmpAssertDataForNotification` |

**Flow:**
1. **Splash:** checks the app version against `GetAllAppVersions` and forces an update when required (*"New Version of the Application is released. Please update to the latest Version."*). It also warns about an upcoming drop in support for old Android versions.
2. **Company setup:** the user scans a company **QR code** (*"Invalid QR-Code"* on failure) or picks a country. This sets `sp_country_type`, `sp_base_url_emanage` / `sp_base_url_attendance`, the customer ID and the company name.
3. **Login:** email and password, plus the device ID. A failed attempt shows *"Invalid Login credentials. Kindly re-check entered email, password or country selection…"*.
4. **Trusted device and 2FA:** if the account is tied to another phone, the user can register this one with an OTP. *"OTP will be sent to registered Email Id / Mobile Number for registration. Your previous trusted device (if any) …"*. Otherwise *"Device is not trusted for 2FA"*. Approval can also come from the already-trusted phone (see the Dashboard module).
5. **Multiple companies:** `ProfileSwitchBottomSheet` switches between the companies or profiles in `UserCompanyList` (*"Do you want to switch …"*).

**App lock:**
- **Methods:** device biometrics, passcode or face (`BiometricAuthentication`), or the app's own **pattern lock**.
- **Relock delay:** **Immediate**, **1 minute** or **30 minutes** (`sp_device_pattern_duration_*`, `sp_device_pattern_timer_status`). There's also an **auto-logout timer** (`sp_autoLogout_time`).
- **Hold login:** `sp_device_hold_*` flags handle "hold" logins (logged in but locked).
- **Linked to Secure Payslip:** turning off the device lock also turns off Secure Payslip (*"Disabling the device lock will also disable Secure Payslip. Are you sure…"*).

**Settings:** notification switches per feature (`sp_leave_notification`, `sp_payslipnotification`, `sp_clock_in_notification`, `sp_wfh_notification`, `sp_company_policy`, `sp_pc_notification*`, …), the Secure Payslip toggle, Wi-Fi updates and language.

**Kiosk:** when the company enables kiosk mode, the app tells the user *"Attendance Kiosk functionality is now enabled. In this mode, you can access only the Attendance…"*.

---

### 9.2 Dashboard and analytics

**Screens:**
- Home: `AppDashboard`, `AppDashboardNew`, `UpgradedAppDashboard`, `UpgradedDashboardMoreActivity`
- Time attendance analytics: `DashboardTmsMainActivity`, `TMSDashboardFragment`, `LatenessFragment` / `LatenessDetailsFragment`, `EarlyOutFragment` / `EarlyOutDetailsFragment`, `AbsenteesFragment` / `AbsenteesDetailsFragment`, `OvertimeDetailsFragment`, `RestChartFragment` / `RestDetailsFragment`, `WorkingFragment`, `YTSChartFragment` / `YTSDetailsFragment` (yet to start)
- Leave: `LeaveFragment`, `ELeaveFragment`, `LeaveCalenderViewPagerFragment`
- Payroll analytics: `PayrollFragment`, `PayrollHeadCountMainActivity`, `PayrollNewJoineeMainActivity`, `PayrollResignedEmployeeMainActivity`, `PayrollRevenueMainActivity`
- Other: `GraphDataMainActivity`, `FeedbackCustomDialogFragment`

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Lateness and leave analytics | `GetLatenessDetailNew`, `getemployeeattendancesummary`, `GetLeaveForCalendar_ByDate`, `GetLeaveTakenDetails_ForEmployee` |
| Payroll analytics | `GetEmployeeDetails`, `GetResDetail` (resignations) |
| Tiles | `GetApprovalCount`, `GetTrainingCalendar`, `MySchedule/GetEmpNotificationShifts`, `CheckUserAssignedRoom` |
| Announcements and feedback | `DontShowAnnouncementPopup`, `SaveFeedback` |
| Other employees' approvals | `LoginRequestApprRej`, `RejectLoginApproval` |

**Logic:**
- **Tiles:** the dashboard shows only the modules licensed to the company **and** granted to the user (§8). Otherwise: *"Your company is not subscribed for this service. Please contact HR for more details."* or *"You do not have access for …"*.
- **Analytics are for managers:** lateness, early-out, absentee, overtime, rest and yet-to-start charts cover the user's department or team. They're shown to users with department access (`TMS_DEPARTMENT_ACCESS`). Payroll head count, new joiners, resignations and revenue are shown to payroll rights holders.
- **Login approvals:** the trusted device receives the "approve this login" prompt and answers *"Access Granted."* / *"Access Denied."*.
- **Location checks:** permission is checked before the dashboard loads. Mock location shows the spoofing warning (`sp_location_spoofing_displayed`).
- **Offline cache:** `sp_lastlocaldatabaseupdateepoch` and `sp_imagesynctime` decide when the Realm cache is refreshed.

**Request models:** `EtmsPiechartDataRequest {CompanyID, CustomerGUID, FromDate, ToDate, UserID}`, `LogoutRequest {UserEmail, deviceToken, strCustomerGUID}`.

---

### 9.3 Attendance and clocking (TMS)

**Screens:** `AttendanceMainActivity`, `Attendance` (the clock screen), `EScheduleAttendance` (clocking for schedule customers), `AttendanceFace`, `AttendanceHistory`, `WorkFromHomeHistory`, `WfhFilterActivity`, `AttendanceRequestApply` / `AttendanceRequestStatus` / `AttendanceRequestApproval`, `AttendanceOTRequestList`, `ApproveDetailOtRequestActivity`, `OTLockedEmployeeDialog`, `EmployeeListDialogFragment`, `BranchUpdateEmpDetailsActivity`, `TmsFragment`, `ScheduleFragment`

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Clocking | `timelog`, `UploadPhotoWithVerifyV1`, `UploadPhotoWithObj`, `UploadPhotoWithVerify`, `GetClockinData`, `SubmitAttendanceDetails`, `AttendanceSchedule/TimeLog` |
| Face | `RegisterPhoto` / `RegisterPhotoV1`, `VerifyFace` / `VerifyFaceV1`, `DownloadPhotoV1` |
| History | `AttendanceHistory/GetAttedanceHistory`, `AttendanceSchedule/FetchAttendanceHistory`, `PushNotificationTimeLogHistory` (WFH) |
| Schedule for the day | `Schedule/GetScheduleOfTheDay`, `GetScheduleOfTheDay`, `AttendanceSchedule/FetchAttendanceSchedule/{date}`, `GetBranchScheduleSingleEmployee7Days` |
| Jobs | `EmpJobMaster/EmpGetJobs`, `EmpJobMaster/EmpGetSearchJobs` |
| Clocking for others | `SearchEmployee`, `SearchEmployeeV1` |
| Corrections and OT | `EmpOTRequestApply`, `EmpOTRequestCalculations`, `GetOTReasonList`, `ETMSAttendanceApproveReject`, `JobCostAttendanceApproveReject`, `ETMSGetApproveUserProfile`, `JobCostGetApproveUserProfile` |
| Other | OneMap `api/public/revgeocode` (SG address), `RegisterTTToken` |

**Clock-in checks, in order, as the app runs them:**
1. **Card number:** *"You do not have card number. Please contact your HR…"*. The card number (`CardNoC`) is the attendance ID.
2. **Permissions:** precise location (*"This app needs the Precise Location permission…"*) and camera, if a photo is needed.
3. **Location fix:** *"Location not found. Please check your location settings."* or *"Your device is currently moving. Please wait for stable GPS capture."*
4. **Mock location:** the spoofing warning. **Developer mode:** the user is warned that HR will be notified.
5. **Wi-Fi restriction** (`sp_wifi_restricted`): *"Please connect to the assigned wifi network to do clock in"*.
6. **Job:** if a job is required (`sp_job_required…`): *"Job is not selected…"*, *"Job is not enabled in web…"* or *"Failed to check in, please check the job settings."* Some code paths are specific to one customer (`brightSonCustomerId`).
7. **Geofence:** distance to the job or work location against the configured radius. *"Clocking allowed only when you are in %s."* `attendance_cofigured_address_info` says *"As per the setting done by your company, you are required to work at your assigned location for the next %s hr…"*.
8. **Photo or face:** if a selfie is required (`sp_selfie_required`): *"Photo is not taken…"*. If face recognition is required (`FaceReconizationRequiredC`): *"Please register your face first…"*, *"Face does not match"*, *"No face found"*.
9. **Optional fields:** remarks (`sp_remarksRequired`), NRIC scan (*"Please scan NRIC"*) and temperature (*"Please enter temperature"*), depending on company settings.
10. **Safe entry / offline:** if "safe entry check-in/out" is on, the punch needs to be online. Otherwise offline punches are queued with *"Thank you for clocking at %s, Since you are in offline attendance will sync later"*.
11. **Success:** *"Thank you for clock in at … / clock out at …"*. The app saves the first clock-in time and location of the day (`first_clock_lat/long`), which the WFH geofence uses (§9.5).

**Clocking on someone else's behalf:**
- **When it applies:** in kiosk mode, or for supervisors.
- **How it works:** settings with an `_on_behalf` suffix (`sp_attendance_id_on_behalf`, `sp_job_required_on_behalf`, `sp_geofencing_meter_on_behalf`, `sp_selfie_required_on_behalf`) apply another employee's rules after `SearchEmployee` finds them.
- **Kiosk extras:** kiosks can change or update a card number (`kiosk_change_empid`) and can be told to use the back camera (`EnableKioskBackCamera`).

**Attendance corrections and OT:**
- **Request:** `ListAttendanceRequest {Req_Id, RequestedTimeInN, RequestedTimeOutN, ShiftCodeC, ReaCodeC, RequestedReaCodeC, RequestedOT10N / 15N / 20N / 30N (OT at 1.0×, 1.5×, 2.0×, 3.0×), RequestedTotalFLatOTN, RequestedTotalOTN, RequestorRemarks, ApprovelStatus, ApproverRole, OfficerComments, IsCancelInitiated, ClockDateD}`.
- **Validation:** *"Requested time in/out should not be zero.."*, *"Reason not selected"*, *"Please choose an approver before applying"* (when the employee picks the approver) and *"Please select Start Time earlier than 23:30"*.
- **Approvers:** they bulk-select OT lines (*"Please, Select at least one OT item from the list"*) and approve or reject them. Locked OT records show `OTLockedEmployeeDialog`.

**Flexi-work and WFH:** `FWASettingForTodayRequest {strCustomerGUID, intApplicationType, strEmpCode}` fetches today's arrangement. When flexi-work takes priority, the app *"will not enforce geofencing, work-from-location or Wi-Fi restrictions…"*.

**Licence gating:** `sp_appattendance_{professional, enterprice, ultimate}_LicenceType` and `sp_apptms` / `sp_appattendance` decide which features (jobs, OT, face, kiosk) appear.

---

### 9.4 Face recognition and anti-spoofing

**Screens:** `FaceRegisterActivityML`, `FaceRegisterCameraXLActivityML`, `AttendanceFaceRegisterActivity`, `AttendaceRegisteredFaceDetailActivity`, `AttendanceFaceDetailActivity`, `AntiSpoofingFacialRecognition`, `LivePreviewPreferenceFragment` / `CameraXLivePreviewPreferenceFragment` (debug camera settings)

**Endpoints:** `RegisterPhoto` (Luxand) or `RegisterPhotoV1`, `VerifyFace` / `VerifyFaceV1`, `DownloadPhotoV1`, `UploadPhotoWithVerify*`

**Capture rules:** the camera guides the user and rejects bad frames:
- *"Place your face inside square box"*, *"Please come closer"*, *"Please come closer to capture the face"*
- *"Multiple faces found"*, *"No face found"*
- *"Your Face captured under Low Light. Please ReCapture again."*
- *"Please wait we are capturing your face."*

**Engines:**
- **Detection:** ML Kit face detection (with a minimum face-size setting).
- **Matching:** Luxand FaceSDK (native) or the server compares against the registered template.
- **Local copies:** registered images are cached in Realm `AttendanceFaceImages`.

**Liveness:** the server sets the parameters in `ProfileResult`:
- **Switch:** `isSpoofingEnabledAndroid`.
- **Thresholds:** `AntiSpoofBrightnessThresholdAndriod` (minimum brightness), `AntiSpoofLaplacianThresholdAndriod` (minimum image sharpness, to reject blurry or screen-replayed images) and `AntiSpoofModelThresholdAndriod` (minimum spoof-model score).
- **Kiosk eye check:** `KioskEyeCheck` checks the eyes on kiosks.
- **Hand check:** `HandOcclusionChecker` uses the MediaPipe hand landmarker to reject a hand covering the face.

---

### 9.5 Live tracking, field staff and work from home (WFH)

**Components:**

| Kind | Classes |
|---|---|
| Services | `LocationService`, `LocationForegroundService`, `LocationUpdatesService`, `GpsLocationTracker`, `PermissionMonitorService` |
| Workers | `LocationWorker`, `WorkAtLocationWorker`, `InfoTechWorker`, `AttendanceSyncWorker`, `OfflineDataSyncService` |
| Receivers | `BootReceiver`, `BootReceiverWAT`, `AlarmReceiverWAT`, `NotificationReceiver`, `LocationUpdateReceiver`, `ConnectivityReceiver`, `NetworkChangeReceiver` |
| Screen | `Service_StopActivity` (stop tracking for the day: *"Do you want to turn off tracking for the day.?"*) |

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Tracking profile | `MobileTracker/GetMobileTrackingDetails`, `GetMobileTrackingDetailsV1` (end time) |
| Uploads | `SaveMobileTrackerV1`, `SaveMobileTrackingStatus`, `UpdateGeo` |
| WFH geofence events | `PushNotificationTimeLog` (out of location), `PushNotificationTimeLogOnline`, `PushNotificationTimeLogOffline` |
| WFH history | `PushNotificationTimeLogHistory` |

**Live tracking (field staff):**
- **Who:** users with country type 1 and the `FIELDSTAFF` flag.
- **Frequency:** high-accuracy GPS every 60 s by default. The server's tracking profile can change the interval and the end time.
- **Payload:** each point is sent as `TrackingDataRequest {CardNoC, CompanyId, CustomerId, EpochActualTime, LatN, LngN}`. Offline points queue in Room (`offline_location_data`) and sync when the network returns.
- **Restarts:** after a reboot (`BOOT_COMPLETED`). It needs `FOREGROUND_SERVICE_LOCATION` on Android 14+.

**WFH / work-at-location geofence:** `LocationUpdatesService` runs when `ISWFH_ENABLED` is on.
1. **Centre:** the **first clock-in location of the day** (`first_clock_lat/long`). If that's missing, it uses the configured WFH location (`WFHLATITUDE` / `WFHLONGITUDE`).
2. **Inside or outside:** `Utility.getDistance(centre, current, radius)` decides, and the app stores the last state (`last_geo_fence_…`).
3. **Events:** going from outside to inside sends **`GEO_ENTER`**. Going from inside to outside, or being outside, sends **`GEO_EXIT`**. They're pushed to the server with coordinates, so HR can see when a remote worker leaves their declared location.
4. **Spot checks:** `AlarmReceiverWAT` (exact alarms) and `WorkAtLocationWorker` check the location at set times during the *"next %s hr"* window.

---

### 9.6 Scheduling (eSchedule, Advanced Schedule, Auto Schedule, Branch schedule)

This is the biggest module (about 480 files). It covers **three scheduling engines**:

| Engine | Code | Base URL | Auth |
|---|---|---|---|
| Branch schedule (legacy) | `fragment/BranchSchedule`, `adaptor/BranchSchedule`, `models/Branch` | eManage server | Session `IToken` |
| eSchedule New / Advanced Schedule (`AdvSCH/*`) | `eScheduleNew.*` | `FULL_SCHEDULE_NEW_BASE_URL`, `MY_SCHEDULE_NEW_BASE_URL` | Session `IToken` |
| Auto Schedule | `autoSchedule.*` | `AUTO_SCHEDULE_MODULE_URL` | Its own JWT: `ModuleSwitch/token` → `Auth/module-entry/mobile` (or `…/sso/infotech`) → access and refresh tokens (`AUTO_SCHEDULE_ACCESS_TOKEN` / `_REFRESH_TOKEN`, refreshed with `Auth/refresh`) |

**Screens:** `MyScheduleFragmentNew`, `FullScheduleFragmentNew`, `PlanScheduleFragment`, `ViewScheduleFragment`, `CreateShiftActivity`, `EditEmpShiftActivity`, `AssignShiftActivity`, `ViewPlannedShift(Activity)`, `GrabScheduleFragmentV1`, `GrabShiftHistoryFragment`, `MasterAvailabilityV1` / `MasterAvailabilitySheet`, `UpdateMyAvailability(V1)`, `MyRequestListFragNew`, `RequestApprovalFragNew`, `RequestApproveScreenNew`, `TransactionFragment`, `BranchFragment`, `BranchCalendarFragment`, `BranchEnterDetailsFragmentNew`, `AttendanceHistoryFragmentNew`, `WeekSelectionCalendar(ForGrab)`, `ViewShiftDetailDialog`, `ViewApproveRemarksDialogV1`

**Features and endpoints:**

| Feature | Endpoints | Notes |
|---|---|---|
| My schedule | `MySchedule/GetMyScheduleData`, `MySchedule/GetEmpNotificationShifts`, `MySchedule/GetChangeScheduleList` | Employee's own shifts |
| Full or branch schedule | `ShiftPlan/GetFullShiftPlanMobile`, `GetBranchScheduleData`, `GetBranchAndShift`, `GetBranchAndCompany`, `AdvSCH/GetBranchList`, `Branch`, `GetAllEmployees`, `Employees/EmployeeList` | Super admin or branch approver (`SCHEDULE_SUPER_ADMIN`) |
| Plan and create shifts | `ShiftSchedule/CreateAddShift`, `ShiftSchedule/AddShift`, `ShiftSchedule/EmployeeWiseShiftUpdate` (PUT), `SaveScheduleDetails`, `AdvSCH/CopySchedule` | Needs shift start and end and break times. *"Are you sure you want to proceed with assigning this shift to …"* |
| Sessions and roles | `AdvSCH/ManageSession`, `AdvSCH/CreateAndMappingEmpSession`, `AdvSCH/EmpSessionMapping`, `AdvSCH/RemoveSession`, `AdvSCH/GetRoleList`, `AdvSCH/GetRoleDetails`, `ShiftPlan/GetRoleListMobile` | Job roles per session (`roleId`, `EmpRoleCount`), for example *"Role list was empty"* |
| Publishing | `PublishSchedule/PublishMobile`, `AdvSCH/PublishedDateForWeek`, `PublishSchedule/InvalidPublishEmployeesExcel` | Publishes a week's schedule to staff |
| Staff from other branches | `AdvSCH/AddOtherBranchStaff` | *"Add staff from another branch"* |
| Shift grab (open shifts) | `GrabSchedule/FetchGrabSchedule`, `GrabSchedule/GetDateWiseGrab`, `GrabSchedule/SendGrabRequest`, `AdvSCH/GrabSchedule`, `GrabSchedule/GetGrabHistory` | Employees claim open shifts |
| Unplanned shifts | `UnplannedShift/FetchUnplannedShift_V2`, `UnplannedShift/UnplannedShiftApproveReject_V2`, `Branch/GetBranchesForUnplannedShift` | Ad-hoc shifts with approval |
| Availability | `Availability/InsertUpdateDailyAvailability`, `Availability/InsertUpdateWeeklyAvailability`, `Availability/FetchDailyAvailability/{EmployeeCode}`, `Availability/fetchWeeklyAvailability`, `Schedule/Get/AddEmpShiftAvailabilityMaster`, `GetEmpAvailabilityData` | Employee availability, by day or week |
| Change, swap and OT requests | `MySchedule/ApplyChangeRequest`, `GetChangeWithEmpList`, `SCHEmpOTRequestApply`, `SCHEmpOTRequestApproveReject`, `EmpPreOTRequestApply`, `ETMSPreOTApproveReject`, `SCHCancelRequestApply`, `GetEmpPendingRequestList`, `GetSCHRequestHistory(Details)`, `GetRequestScheduleList`, `GetRequestAccessList`, `ESCHLeaveApply` | Swap shifts with a colleague, OT before and after, leave from the schedule |
| Auto OT | `GetAutoOTCalculation`, `MySchedule/GetAutoOTcalculationValue` | OT the server calculates from the shift and the clocking |
| Transactions and locking | `Transaction/FetchTransactions`, `Transaction/FetchTransactionFilters`, `AdvSch/GetTransactionFilterVals`, `AdvSCH/GetTransactionDetails`, `Transaction/UpdateTransactionMobile` (PUT), `UpdateTransaction`, `Transaction/UpdateLock` (PUT), `AdvSCH/LockTransactions` | Admins review and **lock** attendance transactions before payroll |
| Sales matrix | `SalesMatrix/AddUpdateSalesMatrix`, `AdvSch/AddUpdateSalesMatrix` | Enter actual sales per branch or day (*"Please enter actual sales"*), probably to staff shifts against sales |
| Settings and comments | `schedule/DisplaySettings`, `Allowance`, `AdvSCH/ReasonList`, `AdvSCH/AddReason`, `GetReasonListnew`, `AdvSCH/GetCommentsList`, `AdvSCH/ReturnHTMLTemplate`, `ShiftSchedule/GetEventsForBranch` | Notes have a maximum length (*"A maximum of %1$s characters are allowed for notes"*) |

**Request models:**
- `GetAllEmployeeRequest {AppId, Branch_Code, CompanyId, CustomerGUID, Date, page_size, search_text, userId}`
- `SaveScheduleRequest {AppId, CompanyId, CustomerGUID, OldReason_Code, ScheduleList, userId}`
- `BranchShiftRequest {Branch_Code, CustomerGUID, userId}`
- `BranchWeekScheduleRequest {… Shift_Code, Reason_Code, FromDate, ToDate …}`

---

### 9.7 Leave and flexi-work (FWA)

**Screens:**
- Leave: `LeaveMainActivity`, `LeaveApply` / `LeaveApplyForm` / `LeaveApplyFormFragment` / `LeaveApplyBaseFragment`, `LeaveFormActivity`, `LeaveCreateDraftActivity`, `LeaveHistory`, `LeaveStatusDetails`, `LeaveSummary`, `LeaveBalanceActivity` / `LeaveBalanceDetailActivity`, `LeaveDetails`, `OffInLieuDetails`, `LeaveCalendar`, `LeavePlannerActivity`, `LeaveApprove`, `LeaveApprovalDetails`, `LeaveApproveGroupSplitActivity`, `LeaveViewProfileImage`, `SickLeaveMainActivity`
- Flexi-work: `FlexiWorkMainActivity`, `FlexiApplyFragment`, `FlexiApproveFragment`, `FlexiHistoryFragment`, `FWAApproveDetailActivity`, `FWACancelActivity`

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Eligibility and balance | `GetLeaveEligibility_ForEmployee`, `GetOffInLieuEligibility_ForEmployee`, `Get_PaternityLeave_Employee`, `GetLeaveTakenDetails_ForEmployee` |
| Apply, update, cancel | `LeaveApply`, `LeaveUpdate` (remarks), `CancelLeave`, `GetDraftLeaves`, `FileUploadOrRemove` |
| Day count | `GetTotalAppliedDays_BasedOnExcludeDay` (excludes rest days and holidays) |
| Status and history | `GetPendingLeaves_BasedonID`, `GetLeaveHistoryforEmployee`, `GetLeaveForCalendar_ByDate`, `GetEmployeeSchedules` |
| Approval | `GetPendingLeavesForApproval`, `LeaveApproveReject`, `GetApproveUserProfile` |
| Flexi-work | `FWA/ApplyFWA`, `FWA/UpdateFWA`, `FWA/CancelFWA`, `FWA/ApproveRejectFWA`, `FWA/GetEmployeeFWAHistory`, `FWA/GetEmployeeFWALimit`, `FWA/GetEmployeeFWASummary`, `FWA/GetEmployeeFWAforApprover`, `FWA/FWASettingsforToday` |

**Applying (`ApplyRequest`):**

| Field | Meaning |
|---|---|
| `ReasonCodeC` | Leave type |
| `LeaveFromDateD`, `LeaveToDateD` | Dates |
| `DayUnitN` | Full or half day |
| `HrLvFromTime`, `HrLvToTime` | **Hourly leave** times |
| `fTotApplied` | Total applied |
| `RemarksC` | Remarks |
| `FileAttachBase64`, `File2AttachBase64`, `File3AttachBase64`, `FileNameC` | Up to **3 attachments** |
| `MLClaimAmtN`, `DentalClaimAmtN` | Medical or dental **claim amount filed with sick leave** |
| `AdditionalEmailIdC` | Extra people to notify (with a maximum) |
| `EmpSelELApproveSupCodeC` | Approver chosen by the employee |
| `IsDraftApply` | Apply from a draft |
| `LeaveStatusC`, `IdN` | Status and ID for updates |

**Rules in the app:**
- **Dates and times:** the end has to come after the start (*"Please select date later than …"*). Hourly leave is **rounded up to the nearest 15 minutes**, and *"Please select Start Time earlier than 23:30"*.
- **Required fields:** remarks and attachments can be required per leave type (*"Please enter remarks before apply."*, *"Attachement is mandatory"*). Claim amounts must be valid numbers.
- **Day count:** the server counts the days, excluding rest days and holidays. If that call fails, the app shows *"Unable to get total days from server. Showing estimated days without exclude days"*.
- **Quota:** *"Leave Quota has reached its limit"*. Approvers can still let it go over the limit (`IsQuotaExcAllowed`).
- **Cancellation (`CancellationRequest`):** one or several dates (`Ismultiplecancel`, `Leavedates`) with mandatory cancellation remarks. Past leave can only be cancelled if `AllowPastLvCancel` is on. Maternity and paternity leave link through `MatnIdN` / `PatnIdN`.

**Approving (`LeaveApprovalRequest`):**
- **Decision:** `blnLeaveApproved` / `blnLeaveCancelled`, plus `OfficerRemarksC`.
- **Adjusted claims:** the approver can change the claim amounts (`ApprovedMLClaimAmtN`, `ApprovedDentalClaimAmtN`).
- **Chain fields:** `S1Code` / `S2Code` / `HRCode` (approval chain), `strELUserLevelC` (the approver's level), `isBackUp` (acting as backup officer, see `sp_back_up_officer`) and `IsQuotaExcAllowed`.
- **Group approval:** approvals can be split or grouped (`LeaveApproveGroupSplitActivity`).

**Flexi-work (FWA):** each employee has a flexi-work quota (`GetEmployeeFWALimit`) and a monthly summary. Applications go through approval. The day's FWA setting can turn off attendance geofencing (§9.3).

---

### 9.8 Claims (expenses)

**Screens:** `ClaimMainActivity`, `ClaimBaseActivity`, `ClaimFragment`, `BaseClaimFragment`, `ClaimApplyFragment` / `ClaimApplyBaseFragment` / `ClaimApplyDetailFragment` / `ClaimApplyDetailActivity`, `SubClaimFragment`, `ClaimDraftFragment` / `ClaimDraftDetailActivity`, `ClaimApproveFragment` / `ClaimApproveDetailActivity`, `ClaimHistoryActivity`, `ClaimGroupSummaryFragment`, `SummaryDetailActivity`, `BaseEligibilityActivity`, `DocumentScanActivity`, `ReceiptcropActivity`, `CroppingActivity`, `GetLocationFromMapActivity`, `MapRouteDirectionActivity` / `MapRouteDirectionFragment`

**Endpoints:**

| Purpose | Endpoints |
|---|---|
| Setup | `GetEClaimSettings`, `GetEClaimEligibility`, `GetClaimLimitForValidate`, `GeClaimtProrateDtls` (pro-rating), `GetCostCenter`, `GetCountryAndCurrency` |
| Currency and GST | `GetClaimExchangeRate(s)`, `GetClaimCurrencyRates`, `GetGstDetail` |
| Apply and drafts | `GetCalculatedClaimAmount`, `ApplyClaim`, `ReapplyClaim`, `CancelClaim`, `GetEClaimDrafts`, `DeleteDraftClaim`, `ECFileUploadOrRemove` |
| Summaries | `GetEClaimSummary`, `GetEmployeeClaimSummary` |
| Approval | `GetEClaims_PendingForApproval`, `ClaimApproveReject` |

**Logic:**
- **Claim structure:** claims belong to **claim groups**, and each group has sub-claims and balances (`ClaimGroupObject`, `SubClaimObject`, `ClaimGroupBalanceObject`). Limits are checked before submission (`GetClaimLimitForValidate`). Entitlement can be pro-rated for part-year staff.
- **Foreign currency:** the user picks the country and currency and the app converts at the server's exchange rate. The amount can also be shown in the actual currency.
- **GST rules,** checked on the phone:
  - *"GST Amount must be less than or equal to the Receipt Amount (Bef. GST)"*
  - *"[Receipt Amount (Bef. GST) + GST] must be equal to Receipt Amount"*
  - *"Claimable Amount must be less than or equal to the [Receipt Amount (Bef. GST) + GST]"*
  - *"Claim amount must be less than Receipt Amount"*
- **Receipt scanning with OCR:** `DocumentScanActivity`, crop, then `fragment/claim/TextScanner` (ML Kit **Text Recognition**). It pulls the receipt amount and date using several methods, for example `extractAmountByRectAlignment` and `GettingAmountFromDetections`. Poor scans show *"Scanned receipt image quality is not clear. Please review and update the details…"*.
- **Mileage claims:** the user picks a from and to address on the map (*"From and To location should not be same"*), the app gets Google directions (*"No routes available."*), and the user must save the chosen route. The distance becomes the claim amount.
- **Bulk actions:** apply, re-apply, cancel (with comments) and approve or reject several claims at once (*"Please select at least one claim before …"*). The app warns when attachments are missing (*"Attachment is missing for few/selected claims…"*).
- **Cost centre:** charged per claim (`sp_claim_cost_center_list`).
- **Approval chain:** `ECApproveS1CodeC` → `ECApproveS2CodeC` → `ECApproveHRCodeC`. Approvers can adjust amounts (`ClaimApprovedAmtN`, `ApprovedGSTAmount`).

---

### 9.9 Payslip and income tax

**Screens:** `PayslipMainActivity`, `PayslipDetailActivity`, `IncomeTaxMainActivity`

**Endpoints:** `GetNewPayslip`, `EmpIR8A/EmpGetIR8AYearType` (Singapore **IR8A** tax form, by year), `SecurepayslipandIR8A` (toggle)

**Logic:**
- **Payslip view:** the server sends payslip data (`PayslipParams`, `PayslipGenerateType`, `ShowPayslipActualCurrency`) and the app shows it with the company logo (`sp_companyLogo`). Viewing is tracked (`IsPayslipViewed`, `PayslipViewedDate`).
- **Secure Payslip:** when it's on (`SecurepayslipIR8A`), opening a payslip or IR8A **needs device authentication** (*"Please use the authentication (fingerprint, passcode or face) feature to enable secure payslip"*).
- **Sharing:** the app renders the payslip to a PDF with **PdfBox-Android** and protects it with a **password the user types in** (*"For your security please enter the payslip password before sharing the payslip."*). It uses a `StandardProtectionPolicy` with the same user and owner password, printing allowed and editing blocked. Errors show *"Error while generating PDF"*.
- **Local cache:** payslips and income-tax records are kept in Realm (`PayslipObject`, `IncomeTaxObject`, `IncomeTaxTypeObject`, `IncomeTaxResponseObject`).

---

### 9.10 Profile / personal particulars

**Screens:**
- View and edit: `PersonalParticularsActivity`, `PersonalDetailActivity`, `PersonDetailActivity`, `AddressDetailActivity`, `ContactDetailActivity`, `BankDetailsActivity`, `SpouseDetailActivity`, `ChildrenDetailActivity`, `ChildrenTaxDetailActivity`, `EducationDetailActivity`, `NationalServiceDetailActivity`, plus a fragment for each
- Approver screens: `ApprovalDetailActivity`, `PersonApproveFragment`, `PersonDetailApproveFragment`, `AddressApproveFragment`, `BankDetailApproveFragment`, `ContactDetailApproveFragment`, `SpouseDetailApproveFragment`, `ChildrenDetailApproveFragment`, `EducationDetailApproveFragment`, `NationalServiceApproveFragment`

**Endpoints:** `GetEmployeeDetails`, `UpdateEmployeeDetails`, `ApprovalProcess`, `GetEmployeeDocList`, `UploadDocAttachment`, `DeleteDocAttachment`

**Tabs:** Profile, **Asset** (company assets assigned to the employee), **Documents** (employee document store), **Emergency Contact**.

**Logic:**
- **Changes go to approval:** edits are saved as a **pending change request**, not written directly (`sp_employee_approval_request`, `sp_personal_particulars_approved`). An HR approver (`EHRISAPPROVER` / `EHRSUPERADMIN`) reviews each section side by side and approves or rejects with comments (*"Please enter comments"*, *"Failed to process approval request."*).
- **Unsaved changes:** the dashboard reminds the user (*"You have unsaved personal particular changes, Please update personal particulars?"*).
- **Validation:** gender and nationality are required, *"Please add Address 1 details"*, *"Address 1 cannot be more than 50 characters"*, *"Please enter a valid postal code"*, *"Please enter spouse name"* and *"No changes in personal particular data?"*.
- **Documents:** up to **5 MB** per file (*"File size exceeds the limit of 5MB"*), from the camera or storage, with a description.
- **Fields by country:** Singapore national service (NS) details; tax details for children (for tax relief).

---

### 9.11 Onboarding (new hires and candidates)

**Screens:** `CandidateActivity`, `CandidateCreationFragment`, `Candidate_creation_list`, `BaseOnboardingFragment`, `OnBoardBasicFragment`, `OnBoardContactFragment`, `OnBoardBankAccountDetails`, `OnBoardSpouseFragment`, `OnBoardChildrenFragment` / `OnboardChildrenActivty`, `OnBoardingChildrenTaxFragment` / `OnBoardChildrenTaxDetailActivity`, `OnBoardEducationFragment` / `OnBoardEducationDetailActivity`, `OnBoardPreEmploymentFragment` / `OnBoardingPreEmploymentActivity`, `OnBoardAdditionalFragment`

**Endpoints:** `GetCandidateProfileDetails`, `SaveCandidateDetails`, `SaveCandidateFileDetails`, `DownloadCandidateFile`, `DeleteCandidateDetails`, `UpdateCandidateStatus`, the onboarding chatbot (`Onboarding/*`), and `ActivateNewProfilebyOTP` (first login)

**Flow:**
1. **Candidate login:** HR creates a candidate, who logs in with a restricted account (`IsOnboardingCandidate`). The candidate sees only the onboarding forms and the onboarding chatbot.
2. **Forms:** the candidate fills in the tabs (basic details, contact, bank, spouse, children, children tax, education, previous employment, additional) and attaches documents per tab. `SaveCandidateFileDetailRequest` has `basicDetailsFilesList`, `basicMyKadICFilesList`, `basicResumeFilesList`, `childDetailsFilesList`, `childTaxExemptDetailsFilesList`, `contactDetailsFilesList`, `educationDetailsFilesList`, `previousEmploymentDetailsFilesList` and `spouseDetailsFilesList`.
3. **Validation:** many required fields:
   - Gender, date of birth, nationality, religion, race and marital status.
   - **Malaysian MyKad / IC:** *"Please enter the 12 digit MYKAD/IC"*, plus the IC document, or a foreign ID number.
   - Resume, bank name, branch ID and account number, and the spouse's details.
4. **Status:** after submission (*"Profile Submitted"*), the candidate waits for HR (*"Your profile is already waiting for approval."*).
   - **Rejected:** *"Your submission has been rejected. Please submit your personal particulars again"*.
   - **Approved:** *"Your Profile has been Approved. You will now be logged out."* The account becomes an employee account.
   - **Deadline passed:** *"Your submission deadline is reached. You will now be logged out."*
5. **HR side:** `Candidate_creation_list` / `CandidateCreationFragment` let HR users create and manage candidates from the app (`SaveCandidateDetails`, `DeleteCandidateDetails`, `UpdateCandidateStatus`).

`SaveCandidateDetailRequest` holds the full personal record: name, date of birth, blood group, IC or foreign ID, foreign address, bank code, name and account, branch, emergency contact, country and comments.

---

### 9.12 Messages, memos and letterheads

**Screens:** `MessagesMainActivity` (tabs **HR Memos** and **Personal Message**), `MessagesDetailActivity`, `LetterHeadDetailActivity`

**Endpoints:** `GetEmployeeMemo`, `GetEmployeeLetters` (plus memo viewed and status updates through `MemoStatusRequest` / `MemoViewedStatusRequest`)

**Logic:**
- **Memos:** company-wide HR memos with attachments, plus **personal letters** on company letterhead (for example offer, confirmation or increment letters). Read status goes back to HR.
- **Gating:** the module needs the HR or payroll licence (`sp_apphr*` / `sp_apppayroll*` with `Professional`, `Enterprise` or `Ultimate`).
- **Local cache:** Realm (`Memo`, `MemoDoc`, `MemoDocModel`, `LetterHead`, `LetterHeadDoc`).

---

### 9.13 Company policies

**Screen:** `CompanyPoliciesMainActivity`. **Endpoint:** `GetCompanyPolicyList`.

**Logic:** a list of policy documents (`PolicyObject` in Realm) that open in the PDF viewer. It's shown when `isShowCompanyPolicy` is on. Push notifications announce new policies (`sp_company_policy`).

---

### 9.14 Helpdesk tickets and grievances (feedback redressal)

**Two separate systems:**

| | Helpdesk tickets (to Info-Tech support) | Grievances / feedback (to the employer) |
|---|---|---|
| Screens | `Tickets`, `My_tickets`, `TicketDetails`, `TicketSubmissionFragment`, `AttachmentViewer` | `GrievancesMainActivity` (*"My Feedback Tickets"*), `SubmitGrievanceFragment`, `GrievanceTicketListFragment`, `TicketDetailsGrievanceFragment`, `ApproverGrievanceTicket` |
| Server | `TICKETS_URL` (a separate server) | eManage |
| Endpoints | `GetTickets`, `CreateTicket`, `GetTicketDetails`, `ReplyTicket`, `GetAttachments` | `GMCreateTicket`, `GMEmpTicketList`, `GMTicketReply`, `GMTicketReopen`, `GMTicketChatHistory`, `GMApproverTicketList`, `GMOtherApproverTicketList`, `GMApproverTicketReply` |
| Request | `CreateTicketsRequest {CustomerCode, UserID, ReportedBy, Subject, TktType, Attachment, FileAttachBase64, File2…, File3…}` | `SubmitGrievanceRequest {SubjectC, DescC, UserId, CompanyId, CustomerGUID, attachmentArray}` |

**Grievance logic:**
- **Purpose:** employees raise feedback or complaints (*"You can make use of this function to raise feedback or complaint related to your …"*).
- **Routing:** each grievance goes to a **grievance manager or approver** (`GrievanceApproverUserId`, `GrievanceManagerEmpNameC`).
- **Approver actions:** approvers reply and change the status (`GMApproverTicketReplyRequest {TicketNo, userId, StatusID, …}`). Whether the employee can reply back is controlled by `CanApproverReplyBack`.
- **Employee actions:** reopen a closed ticket (`GMTicketReopen`) and close a ticket (*"Are you sure you want to close the ticket?"*).
- **Admin views:** HR super admins (`SP_EHR_SUPERADMIN_LOGIN`) also see *other approvers'* tickets (`GMOtherApproverTicketList`).
- **Payroll grievances:** routed through `IsEPayrollGrievanceApprover`.
- **Local cache:** grievances are kept in a Room database (`grievance/db`).

---

### 9.15 AI HR chatbot

**Screens:** `ChatScreenNew`, `ChatDetailsActivity`, `RecentChatsActivity`, `FeedbackActivity`. The UI includes a voice waveform (`assets/voicewave.html`, `siriwave.js`) and uses the `RECORD_AUDIO` permission.

**Endpoints:**

| Bot | Endpoints |
|---|---|
| Employee bot | `GetBotReply`, `GetChatResponse`, `GetChatHistory`, `DeleteChatHistory`, `AddFeedback`, `GetBlobFiles` |
| Onboarding bot | `Onboarding/GetBotReply`, `Onboarding/GetChatResponse`, `Onboarding/GetChatHistory`, `Onboarding/DeleteChatHistory`, `Onboarding/GetBlobFiles` |

**Logic:**
- **Access:** only shown when `ShowAIHR` / `aI_Access` is on.
- **Chat:** questions go to Info-Tech's server, which runs the model. `GetBlobFiles` lists the source documents, most likely company HR policies used to ground the answers. Answers can be copied (*"Text copied to clipboard!"*). Chats are kept as history and can be deleted.
- **Feedback:** users rate answers and must pick a reason or write a comment (*"Please select at least one reason or enter the comments"*).
- **Schedule calls:** the chat code also calls schedule endpoints (`MySchedule/GetMyScheduleData`, `GetEmpPendingRequestList`), which suggests the bot can answer questions like "what's my shift" or "what's pending" from live data.

---

### 9.16 Training calendar and meeting rooms

**Screens:** `CalendarTrainingFragment`, `CalendarEmployeeDetailsFragment`, `MeetingRoomMainActivity` (tabs *Availability Search*, *My Booking*, *Calendar*, *Book Meeting Room*), `AvailabilitySearchFragment`, `BookRoomCalendarFragment`, `BookMeetingRoomDetailFragment`, `MyRoomBookingFragment`, `MyRoomBookDetailFragment`

**Endpoints:**
- Training: `GetTrainingCalendar`, `GetTrainingCalendarEmpDetails`
- Meeting rooms: `GetMeetingRoomList`, `GetAvailableMeetingRooms`, `GetMeetingCalendar`, `GetMeetingyearList`, `GetMeetingassignedyears`, `UpdateRoomMeeting`, `CheckUserAssignedRoom`

**Logic:**
- **Training calendar:** scheduled courses and who's attending (`TrainingListRealmObject`).
- **Room booking:** search for free rooms by date and time, then book, change or cancel with `UpdateRoomBookingRequest {UserId, strCustomerGUID, RoomCodeC, BookingReason, BookingDateD, BookingStartTimeC, BookingEndTimeC, BookingMode (create / update / cancel), BookingId}`.

---

### 9.17 Rewards and referral

**Screens:** `RewardsActivity`, `RewardsDetailsActivity`, `RedeemRewardActivity`, `QRScanActivity`, `InstructionFragment`, `ReferAndEarnForm`, `ReferEarnFragment`, `ReferNearnHistory`

**Endpoints:**
- Rewards: `Rewards/GetRewards`, `Rewards/GetRewardDetails`, `Rewards/RedeemReward`, `Rewards/GetIndustryList`
- Referrals: `GetReferandEarn`, `SaveReferandEarn`

**Logic:**
- **Rewards:** a vouchers and perks catalogue, licensed separately (`sp_reward_licence`). To redeem, the user **scans a merchant QR code** (*"QR code is invalid. Please scan valid voucher code."*) and needs **location turned on** (*"Your Location has disabled. Please enable your location."*). The location probably confirms the redemption happens at the merchant.
- **Refer and earn:** this is **Info-Tech's own sales referral programme**. An employee refers *another company* as a possible Info-Tech customer, using `ReferEarnRequest {CustName, ContactPerson, ContEmail, ContactMobileNo, rfrName, rfrEmail, rfrMobileNo, rfrCustName, ReferedSource, …}`. Referrals can be shared through WhatsApp.

---

### 9.18 Settings flags that change behaviour (quick reference)

| Area | Flag (local key or server field) | Effect |
|---|---|---|
| Attendance | `sp_selfie_required(_on_behalf)` | Selfie needed to clock in |
| Attendance | `FaceReconizationRequiredC` / `sp_Attendance_Face_Recognition_System_Parameter` | Face match needed |
| Attendance | `sp_wifi_restricted`, `WifiBssid` list | Clock in only on assigned Wi-Fi |
| Attendance | `sp_geofencing_meter(_on_behalf)` + job lat/lng | Geofence radius |
| Attendance | `sp_job_required(_on_behalf)` | Job code needed |
| Attendance | `sp_remarksRequired` | Remarks needed |
| Attendance | Safe-entry check-in/out | Online-only clocking |
| Attendance | `EnableKioskBackCamera`, `SP_DISABLE_KIOSK_MODE` | Kiosk camera and on/off |
| WFH | `ISWFH_ENABLED`, `WFHLATITUDE` / `WFHLONGITUDE` | WFH geofence and enter / exit events |
| Tracking | `FIELDSTAFF` (+ country type 1) | Background live tracking |
| Face | `isSpoofingEnabledAndroid`, `AntiSpoof*ThresholdAndriod` | Liveness check thresholds |
| Leave | `AllowPastLvCancel`, `ShowSelApproverByEmpC`, `AllowEmptoSelOtherDeptAprOfficerC` | Cancel past leave; employee picks approver; approver from another department |
| Calendar | `IsCalendarEnabled`, `IsDisplayCalFilterForApprover` | Leave calendar and approver filter |
| Payslip | `SecurepayslipIR8A`, `ShowPayslipActualCurrency` | Device auth for payslip and IR8A; show the actual currency |
| Security | `Enable2FA`, `AllowLogin`, `IsLicenseValid` | 2FA, block login, licence check |
| AI | `ShowAIHR`, `aI_Access` | Chatbot visible |
| Schedule | `isShowFullSchedule`, `IsAdvSCHNew`, `ESCHISDEPT` | Full schedule view, new advanced-schedule engine, department-based schedule |

---

### 9.19 Voice assistant (`voice` package)

**Screens:** `DashboardBottomSheetCommandsFrag` (opened from `AppDashboard`, `AppDashboardNew` and `UpgradedAppDashboard`) and `LeaveBottomSheetCommandsFrag` (opened from `LeaveFormActivity` and `LeaveApplyBaseFragment`), coordinated by `VoiceModule`.

**How it works:**
- **Speech to text:** Android `SpeechRecognizer` (on-device or Google), which is why the app needs `RECORD_AUDIO`. It greets the user with *"Hello How can I help You"*.
- **No AI model:** the text is matched against **hard-coded keyword lists** with `contains(...)`. The lists **allow for common recognition mistakes**: "apply climb", "climate" and "clime" count as *claim*; "apply live" and "approve live" as *leave*; "draught" as *draft*; "argent leave" as *urgent leave*; "asserts" as *assets*.
- **Dashboard commands** open a module: attendance (history, schedule, submit, approve, exception, WFH), leave (apply, approve, urgent leave, early leave, balance, calendar), claims (apply, approve, draft, recent, summary), payslip ("PDF"), IR8A, memos, company policy, assets, availability, book a room and training. Each command still respects the user's licences and rights (`sp_appleave`, `sp_apptms`, `sp_apppayroll`, `sp_appSchedule`, `sp_claim`, `sp_secure_payslip`, `sp_pattern_login`).
- **Leave commands fill in the leave form by voice:** the leave type (matched against `LeaveReason` and leave groups, `getLeaveGroupCode` / `getPayTypeCode`), the start and end dates (spoken day, month and year in numbers or words: "eighteen", "august" / "aug"; format `dd/MM/yyyy`), the time for hourly leave ("a.m.", ":", "Hour") and the reason (after "because" / "cause"). It then checks for missing fields (`CheckUnfieldData`).
- **Endpoints it relies on:** `GetLeaveEligibility_ForEmployee`, `GetOffInLieuEligibility_ForEmployee`, `GetReasonListnew` / `AdvSCH/ReasonList`, `GetClaims_ForEmployee`, `EmpIR8A/EmpGetIR8AYearType`.
- **Messages:** *"Invalid Keyword"*, *"Incorrect date"*, *"Incorrect month Please Tell from Hint"*, *"Incorrect year Please Tell from Hint"*, *"No speech input"*, *"No match"*, *"RecognitionService busy"*, *"Lets try again"*.

---

### 9.20 Job / project costing (`projectCost`)

**Screens:** `JobListActivity`, `JobRequestFragment` (the employee's job-cost requests), `ApproverJobRequestFragment` (approver queue), `JobDetailsBottomSheet`

**Endpoints:** `GetJobCostCodeList`, `GetJobCostReasonList`, `EmpJobCostRequestApply`, `GetEmployeeJobCostRequests`, `GetJobCostEmployeeAppliedClaims`, `DownloadjobCostAttachFile`, `JobCostGetApproveUserProfile`, `JobCostAttendanceApproveReject`

**How it works:**
- **Cost tracking:** time is booked to **job or project cost codes**. The `JobCode` sent with every clock-in (§4.2) links attendance hours to a job, and the job can carry its own location for the geofence (§9.3).
- **Corrections:** employees can apply to change or add job-cost entries, with a reason and an attachment. Approvers approve or reject them in bulk (*"Please select any job to approve/reject"*).
- **Job-related claims** (`GetJobCostEmployeeAppliedClaims`) let expenses be charged to a project.
- **Who approves:** `JobCostGetApproveUserProfile` looks up the approvers, and `SP_ATTENDANCE_APPROVER_SELECT_EMP` lets the employee pick one.

---

### 9.21 Legacy schedule module (`schedule` package)

The older scheduling screens, still used for customers on the earlier schedule engine (alongside §9.6).

**Screens:** `ScheduleMainFragment`, `MyScheduleFragment`, `FullScheduleFragment`, `FullScheduleDetailsApplyActivity`, `GraphScheduleFragment`, `MyAvailabilityFragment`, `AddAvailabilityScreen`, `EmployeeRequestActivity`, `RequestListFragment`, `RequestApproveScreen`, `RequestApprovalOtApproval`, `ApproveScheduleFragment`, `AttendanceAndApproveDetails`, `SubmitPreOTFragment`, `PreOTApprovalListFragment`, `PreOTApprovalDetailsActivity`, `ApprovePreOT`, `HistoryPreOtListFragment`

**Extra endpoints not covered in §9.6:** `GetEScheduleDataGrabRequest` and `GetEScheduleDataGrabRequestSave` (legacy shift grab), plus the same OT, pre-OT, availability, transaction-lock and sales-matrix calls.

**Rules in the app:**
- *"You are already assigned to 3 shifts on this day…"*: a **maximum of 3 shifts per employee per day**.
- *"Oops! Past shifts cannot be selected. Please choose an available shift"*.
- *"No schedule assigned to the selected date. Please choose a different date."*
- *"Please make any change to update"*: the app blocks submissions that change nothing.

**Pre-OT:** employees ask for overtime **before** working it (`SubmitPreOTFragment` → `EmpPreOTRequestApply`). Approvers handle it through `ETMSPreOTApproveReject` (`PreOTApprovalRequest {userId, strCustomerGUIID, RequestList, ApproverRemarks, IsBtnStatus}`), and `ApprovedUpto` tracks how far up the approval chain it has gone.

---

### 9.22 Training management (requests and attendance)

This expands §9.16. **Screens:** `TrainingMainActivity` (tabs *My Training*, *Available Training*, *Request Training*, *Request Training List*, *My Calendar*, *All Calendar*), `TrainingListFragment`, `TrainingDetailsFragment`, `CorporateTrainingFragments`, `CustomTrainingFragment`, `CustomTrainingHistory`, `RequestTrainingFragment`

**Endpoints:** `GetTrainingDetails`, `GetCorporateTrainingDetails`, `RegisterCorporateTraining`, `GetCustomTrainingDetails`, `CustomTraningRequest`, `SubmitAttendanceDetails` (training attendance), `GetTrainingCalendar`, `GetTrainingCalendarEmpDetails`

**Flows:**
- **Corporate training:** the employee browses courses the company offers and registers (`RegisterCorporateTrainingRequest {EmpcodeC, TrainingCodeC, strCustomerGUID, UserId}`).
- **Custom (external) training request:** `CustomTrainingRequest {CourseNameC, InstitutionNameC, TrainingLocationC, URL, CourseStartDateD, CourseEndDateD, CourseStartTimeN, CourseEndTimeN, CourseFeeN, Grant, CertificateNameC, JustificationC, FileByte, FileName}`. It goes to the training approver(s) (*"Your request will be submitted to your Training approver(s)."*), and the employee can track `ApprovedStatus`, `ApprovedUpto` and `PendingWith`.
- **Recording attendance after a course:** `SubmitTrainingAttendanceRequest {TrainingCodeC, CourseCodeC, AttendedC, AttendedResultC, CertificateExpiryDateD, CertificateFileNameC, UploadFilebyteArray, …}`. The rules: *"You cannot submit attendance before training ends."* and *"Certificate Expiry date cannot be before training end date."*

---

### 9.23 Release notes / what's new (`releaseNotes`)

**Screens:** `Announcement_List_Activity`, `Announcement_List_Detail_Activity`, plus the `AnnouncementActivity` popup from §9.1

**Endpoints:** `GetReleaseNotes`, `ReleaseNotesDetail`, `UpdateLikeStatus`, `UpdateStartedNotes`, `DontShowAnnouncementPopup`

**How it works:**
- **Content:** product release notes from Info-Tech, tagged per module (`getModuleName`) and filterable (*"Do you want to reset the filter settings?…"*).
- **Interactions:** users can **star** notes (`IsStared`), **like or dislike** them (`IsLike` / `IsDisLike`) and add comments (`IsComments`). The app tracks read status, and the server gets this feedback.
- **Popup:** shown once at login, with a *"don't show again"* option (`DontShowAnnouncementPopup`).

---

### 9.24 Push notifications (`firebase/MyFirebaseMessagingService`)

**Delivery:** Firebase Cloud Messaging. The device token is registered with `DeviceToken` / `RegisterDevice`.

**Routing is done by matching keywords in the notification's *title and body text*,** not by a structured "type" field:

| Text contains | Opens | Extra |
|---|---|---|
| "leave" + "applied" / "approval" / "approved" / "reject" / "cancel" | Dashboard → leave status or approval | `leave`, `leaveReject`, `leaveCancel` |
| "claim" + approval words | Dashboard → claims | `claim` |
| "payslip" | Dashboard → payslip | `payslip` |
| "absent" | Dashboard → attendance | `absent` |
| "training scheduled" / "training" + "course" | Dashboard → training | `courseScheduled`, `training` |
| Grievance or feedback | `GrievancesMainActivity` | — |
| OTP or login approval | `SplashScreen` | `OTPAccess` (approve a login from another device) |
| Schedule customer | Schedule screens | `scheduleCustomer` |
| "onboarding" / "offboarding" | Onboarding flow / exit notice | `onboarding`, `offboarding` |

- **Dashboard choice:** SSO countries open `AppDashboardNew`, the others `AppDashboard`.
- **Payload:** every intent carries `body`, `notificationTitle`, `notificationCountry`, `emailormobile` and `FBNotification` / `notificationFromFirebase`.
- **Badges:** the launcher badge count (Samsung, HTC, Sony, Huawei, Oppo and other launcher badge permissions) comes from `badge`.
- **What can go wrong:** because routing depends on the wording, a notification phrased differently, or in another language, can open the wrong screen or just the dashboard.

---

## 10. Technical deep-dive: native code, SDKs, permissions, SSO framework

### 10.1 Native libraries (ARM64)

The table lists what each library exports and what it is. **No secret values were extracted or decrypted.**

| Library | Size | What it is |
|---|---|---|
| `libmyapplication.so` | 344 KB | **Info-Tech's secrets store.** It only exports JNI getters (listed below) and depends only on libc, libdl, liblog and libm |
| `libengine.so` | 1.2 MB | **`com.mv.engine`**: face detection plus **liveness ("Live") detection** on OpenCV. Models in `assets/detection/` (`detection.param/.bin`) and `assets/live/` (`model_1`, `model_2` + `config.json`, an ncnn-style 2-model liveness ensemble) |
| `libfsdk.so` | 46 MB | **Luxand FaceSDK.** Exports `com_luxand_FSDK_*` (`ActivateLibrary`, `DetectFace`, `DetectEyes`, `CreateTracker`, …). Embedded build strings point to an **old build** (Android clang 3.8 and 5.0, bundled libpng 1.6.21 from **January 2016**). `ActivateLibrary` needs a licence key |
| `libface_detector_v2_jni.so`, `libmlkitcommonpipeline.so`, `libimage_processing_util_jni.so` | — | Google ML Kit (face detection, CameraX helpers) |
| `libmediapipe_tasks_jni.so` | 10 MB | MediaPipe Tasks (hand landmarker, used to catch a hand covering the face) |
| `libopencv_*.so` | ~32 MB | OpenCV 4 (used by `libengine` and the app) |
| `librealm-jni.so` | 8 MB | Realm database |
| `libmodpdfium.so`, `libjniPdfium.so`, `libmodft2.so`, `libmodpng.so` | — | PDFium PDF rendering |
| `libdatastore_shared_counter.so`, `libsurface_util_jni.so`, `libc++_shared.so` | — | AndroidX and runtime |

**What `libmyapplication.so` exports.** These are the names of JNI functions; their return values weren't read:

| Group | Functions |
|---|---|
| Production server URLs | `Api.{ATTENDANCE, LEAVE, CLAIM, TICKETS, FACE_BLOBE, MAFACE_BLOBE, MA_/HK_/AUS_/NZ_ ATTENDANCE & LEAVE, FULL_SCHEDULE_NEW, MY_SCHEDULE_NEW, AUTO_SCHEDULE_MODULE, AUTO_SCHEDULE_TOKEN, SINGPASS}_BASE_URL` |
| **Staging server URLs** | A matching **`STG_*`** function for each of the above. **No Java code calls the `STG_*` functions** in this build, so the staging addresses ship in the production app without being used |
| Encryption material | `Constants.{key, initVector, EncryptionKey, EncryptionInitVector, VKkey, VKInitVector, initKeyTemp, initVectorTemp, aes, alg, chiper}` and **old versions kept after a key change**: `keyOLD`, `initVectorOLD`, `VKkeyOLD`, `VKInitVectorOLD` |
| Firebase and Google | `Constants.{Api_tracking, Api_request_tracking, firebase_settings, southeast_db_ref, appId, apiKey, directions_url, places_base, secondary_url}`, `Utility.getGoogleApiKey`, `Utility.getGoogleGeoCodingApiKey` |
| **Singpass** | `Utility.getSingPassClientID`, `Utility.getSingPassJWTPublicToken`, **`Utility.getSingPassJWTPrivateToken`** |
| Other | `Utility.stringFromJNI`, `stringFromJNIV` |

**Finding: the Singpass private key ships inside the app.**
- **How it's used:** `SingPassActivity` / `SingPassViewModel` call `Utility.getSingPassJWTPrivateToken("release")`, which returns an **EC private key as a JWK**. The Nimbus JOSE library then uses it to **sign the client assertion** (`ECDSASigner`) for Singpass's token endpoint and to **decrypt the returned ID token** (`ECDHDecrypter`).
- **Why it matters:** in Singpass / OpenID Connect, that key proves the app *is* the registered client, and it's the key that unlocks the encrypted identity token. Because it's inside every copy of the APK, anyone who extracts it could pose as Info-Tech's Singpass client or decrypt ID tokens meant for it.
- **The usual approach:** the token exchange and decryption run on a **backend server**, not in the mobile app.
- **Caveat:** this is a static finding. The key value was **not** extracted. It should go to Info-Tech (and possibly GovTech Singpass) through responsible disclosure.

**Finding: keys only hidden in native code.** Putting AES keys and URLs in a native library makes them harder to find but not secret: anyone can call the JNI functions or read the library. Keeping the `*OLD` keys means data encrypted with the earlier keys can still be decrypted.

### 10.2 Third-party SDK inventory

Counts are decompiled `.java` files per namespace, as an indication of size.

| Area | SDKs |
|---|---|
| Google / Firebase | Analytics + Measurement, **Crashlytics**, **Performance Monitoring**, Cloud Messaging, Realtime Database, **Firestore**, **Storage**, Auth (anonymous + generic IdP), Remote Config, Play Core (in-app updates), ML Kit (face, text recognition, object detection), Maps / Location, `gms.ads.identifier` (advertising ID helper) |
| Microsoft | **MSAL / Microsoft Identity** (932 files, Entra ID login), with **OpenTelemetry** (MSAL telemetry) and **YubiKit** (YubiKey security keys over NFC and USB, used by MSAL for certificate or passkey sign-in) |
| Crypto / auth | Bouncy Castle (3,161 files), Nimbus JOSE+JWT, AppAuth (`net.openid`) |
| Networking | Retrofit, OkHttp, gRPC (for Firestore), Apache HttpClient fork (`cz.msebera`), RxJava |
| Data | Realm (+ BSON), Room |
| Documents and images | PdfBox-Android (`tom_roush`), PDFium, Glide (`bumptech`), uCrop (`yalantis`), Lottie (`airbnb`), photo view and gesture image (`alexvasilkov`) |
| UI and utilities | Dexter permissions (`karumi`), country-code picker (`hbb20`), ZXing / journeyapps (QR scanning), calendar views (`kizitonwose`), ShortcutBadger (`me.leolin`), Butterknife, Dagger, TapTargetView (`getkeepsafe`), SLF4J |
| Face / vision | Luxand FaceSDK, `com.mv.engine` liveness, OpenCV, MediaPipe |

**What's absent:** no third-party ad networks or marketing trackers (no Facebook, AppsFlyer, Adjust or Branch SDKs). Analytics are Firebase only.

### 10.3 Where the app sends diagnostic data (Firebase)

| Destination | What's written | Path / key |
|---|---|---|
| Realtime DB (`Api_tracking`) | API URL + HTTP status for every call (when the customer flag is on) | `…/EMP_<userId>/Time_<ts>` |
| Realtime DB (`Api_request_tracking`) | **Decrypted request bodies** (passwords re-encrypted), plus device ID, WFH flag, GPS state, location permission, app state, Android version (when `isCustomerAvailableforReqlog` is on) | `…/EMP_<userId>/Time_<ts>` |
| **Firestore** `ApiFailureLogs` | Failed API calls | `ApiFailureLogs/…/EMP_<userId>/Time_<ts>` |
| Realtime DB (`firebase_settings`) → `Play` | **Read-only remote flags** fetched for Singapore users (country type 0) on the login and face screens: `Morning`, `Evening`, `isDevCheckRequired` | `Play` |
| Firebase Analytics | Screen and event logs (`FBAnalytics.setFireBaseLog…`), a `kios_mode` event, live-tracking and **"unauthorised"** events (from `PermissionMonitorService`, `SyncOfflineDataWorker`, `Post`, `Fetch`), plus `setUserId` | — |
| Crashlytics | Crashes and non-fatal errors from dashboards, attendance, leave and grievances | — |
| **Firebase Storage** | **Voice-command recordings:** `Leave Apply Audio/Android/<userId>/<timestamp>` and `Dashboard Audio/Android/<userId>/<timestamp>` | See below |

**About the voice uploads:** in this build both calls pass `null` as the file (`uploadFileDashboard(null, …)`), and `uploadFile` returns without doing anything when the file is `null`. So **no audio is uploaded in 2.0.261020**. The code is still there, though: `MediaRecorder.setOutputFile(...)` records each voice command to a local file, and a one-line change in a future version would start uploading recordings tagged with the employee's user ID.

### 10.4 Permissions: what each one is actually for

| Permission | Used by | Verdict |
|---|---|---|
| `NFC` | **Yubico YubiKit** (`NfcYubiKeyDevice`, `NfcSmartCardConnection`), through MSAL | Not used by Info-Tech's own code. Only for YubiKey sign-in with Microsoft accounts |
| `READ_CLIPBOARD` | No `getPrimaryClip()` in app code. Only the AndroidX text-field classes | Most likely left over or added by a library |
| `RECORD_AUDIO` | Voice assistant (§9.19) and the chatbot voice UI | Used |
| `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION` | Live tracking, WFH geofence | Used for field staff and WFH |
| `ACTIVITY_RECOGNITION` | No app usage found | Probably left over or from a library |
| `AD_ID`, `ACCESS_ADSERVICES_*` | Firebase Analytics / `gms.ads.identifier` | Added by the Google SDKs. No ads shown |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | App lock, Secure Payslip | Used |
| `CAMERA` | Face, selfie, claim receipts, QR scanning, document upload | Used |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Work-at-location checks (`AlarmReceiverWAT`) | Used |
| Launcher badge permissions (Samsung, HTC, Sony, Huawei, Oppo, …) | ShortcutBadger | Unread-count badges |
| `READ/WRITE_EXTERNAL_STORAGE` | Attachments, PDF sharing | Old storage model (target SDK 36 ignores most of it) |
| `SmsBroadcastReceiver` (exported) | **SMS Retriever API** in `Login`, `SSOLoginActivity` and `SettingsMainActivity`, for **auto-reading OTPs** | No `READ_SMS` needed. Uses Google's SMS Retriever |

### 10.5 SSO framework (`com.infotech.ssoframework`)

**Purpose:** a shared login library used by Info-Tech's apps (HRMS, **InfoFace** and **Digi Accounting**, matching the `com.infoface.permission.WRITE_DATA` and `com.infotech.accounting.permission.WRITE_DATA` permissions). One sign-in is shared across all of them.

**Parts:**
- `SSOHandler` / `SSOApi` / `SSOApiService`: login, 2FA, devices, password calls (§9.1).
- `EncryptDecrypt`: the framework's own encryption helper.
- `providers/ShareCredentials`: reads and writes the shared credentials.
- `providers/AuthorizeCredentials`: a **ContentProvider** with authority `com.infotech.provider.loginuser`, plus routes for `com.infoface.provider.loginuser` and `com.accounting.provider.loginuser`. It's backed by a SQLite database, **`UserDB`**, with three tables:

| Table | Columns |
|---|---|
| `Users` | `name` (email), **`userpassword`**, **`usertoken`**, `defaultcountry`, `expirydate`, `istwofaenabled`, `isdeviceregistered`, `uniqueid`, `mobileNo`, `countryCode`, `changedPassword`, `logoutStatus`, … |
| `Applist` / `InActiveApplist` | `appid`, `appname`, `email`, `mobileno`, `uaid`, `idn`, `customername`, `customercode`, `appcountry`, `isdefault`, `internal` |

**Finding: the shared login store is exposed without protection.**
- **The provider is open to every app:** the manifest declares it `android:exported="true"` with `grantUriPermissions="true"` and **no `android:permission`, `readPermission` or `writePermission`**. The app *defines* a signature-level permission (`com.infotech.mobileattendancenew.permission.WRITE_DATA`), which would limit access to Info-Tech's own apps, but **it isn't applied to the provider**.
- **No caller checks:** `query`, `insert`, `update` and `delete` run directly on `UserDB` without checking who is calling (no `getCallingPackage()` / `checkCallingPermission()` / signature check).
- **What gets stored:** after an SSO login, `SSOHandler` → `ShareCredentials.saveData(...)` saves the user's email, **password** (the value typed at login) and **session token** in `Users`. The password goes back into new logins through `ssoGetUserPassword()` → `getSavedPAssword()` → `getDataFromContentProvider(...)` with **no decryption step found**, so it appears to be **stored in plain text**.
- **The risk:** on a phone where the HRMS app is installed, **any other installed app could read the employee's email, password and session token, or change or delete them**, without asking for any permission.
- **Caveat:** this is a static finding. It should be **confirmed on your own test device, with authorization, and then reported to Info-Tech** (responsible disclosure). The fix is to apply the signature-level permission to the provider (or set `exported="false"`), check callers, and never store the raw password.

### 10.6 Smaller packages

| Package | Contents |
|---|---|
| `db` (4 files) | `RealmManager` plus Realm models for attendance jobs (`AttendanceJob`) and location tracking (`LocationTracking`) |
| `uihelpers` (32) | An image cropper (`cropper`) and a custom calendar widget (`customcalender`) |
| `customviews` (separate module) | File picker (camera, gallery, documents), image viewer, tabbed filter, `SharedPreferences` wrapper (`com.infotech.customviews.utils.SharedPreferences`, used by the whole app) |
| `com.EncryptionTest` | An empty `main()` class left over from development, with no effect |
| `common/generator` (16) | **Offline time-zone lookup** (`TimeZoneMapperConverter`, `Polygon`, `BoundingBox`, `Parser`): finds the IANA time zone for a GPS position, which is how clock-ins get their `timeZoneName` (§4.2) |

---

## 11. Endpoints not covered elsewhere

These are the 71 endpoints that weren't explained in §1–10 (the earlier estimate of ~120 was too high). Each was matched to its Java caller name and response model (Appendix B).

### 11.1 Payroll and tax, by country

| Endpoint | Country | What it does |
|---|---|---|
| `EmpPayslip/EmpGetPayPeriod` | All | Lists the payslip periods: `Month`, `MonthName`, `PayType` / `PayTypeID`, `PublishedDate`, `IsPayslipViewed`, `PayslipViewedDate` |
| `UpdatePaySlipViewStatus` | All | **Read receipt:** tells HR when the employee opened a payslip |
| `GetEmpLastSalary` | SG | Last month's pay: `GrossWagesN`, `CPFWagesN`, `NettWagesN`, `EmployerCPFN`, `EmployeeCPFN`, `TotalCPFN` (CPF = Singapore pension) |
| `EmpIR8A/EmpGetIR8AYearTypeViewStatus` | SG | IR8A tax forms per year and type, with `PublishedDateD`, `IsViewed`, `ViewedDateD` |
| `UpdateTaxViewStatus`, `IR8A/UpdateTaxViewStatus` | SG / HK | Read receipt for tax forms |
| `IR56/EmpGetIR56YearType` | **Hong Kong** | IR56 employer tax returns by year |
| `EmpGetEAYear` | **Malaysia** | EA form (annual statement of pay) years |
| `GetTDS` | **India** | TDS (tax deducted at source) details |
| `IncomeTax/GetITDeclarationDetails` | **India** | **Investment declarations:** `Investments80C`, `HRA`, `LetOut` (rental property), `OtherInvestmentsandExemptions`, `RegimeType` (old or new tax regime), `NetTaxPayable` |
| `GetDashBoardDetail` | All (payroll rights) | Payroll dashboard: `HeadCountChartTable`, `JoinVsTermintionTable`, `PayrollRevenueTable {GroupName, PayElementList, TotalCostN}`, previous-month comparison |

So the app also supports **India** (TDS, 80C and HRA declarations, tax regime) and region-specific tax forms (SG IR8A, HK IR56, MY EA, AU/NZ payslips with KiwiSaver), beyond the five regions in §1.

### 11.2 Time-attendance dashboard drill-downs (managers)

`GetAbsenteesDetail`, `GetEarlyOutDetail`, `GetYTSDetail` (yet to start), `GetWorkingDetail`, `GetLeaveDetail`, `GetEmployeeOverTimeDetail` return per-employee rows for a department: `EmpCodeC`, `EmpNameC`, `DeptCodeC/NameC`, `ClockDateD`, `TimeInN` / `TimeOutN`, `ShiftDetails`, `ReaCodeC`, `LatenessN`, `UnderN` (under-time), `OT10N…OT30N`, `SFlatN`, `TotOTN`. These are behind the dashboard charts in §9.2 and are only shown with department access.

### 11.3 Overtime / attendance claims

| Endpoint | Purpose |
|---|---|
| `GetAllEmployeeClaims` | Employee's own OT requests (`ScheduleData {ClockDateD, RequestedTotalOT, RequestedReaCodeC, RequestorRemarks, IsCancelInitiated, AttachmentName…}`) |
| `GetEmployeeAppliedClaims_ToApprove` | Approver's queue, with the 3-level approval fields (`ThirdLevelApprovedDateTime`, `ThirdLevelApproverEmail`, …) and `OtTypeN` |
| `EmpOTCancelRequest` | Cancel an OT request |

### 11.4 Leave and claim drafts, eligibility

| Endpoint | Purpose |
|---|---|
| `LeaveDraftApplywithParam_insert`, `LeaveDraft_Update`, `LeaveDraft_Delete` | Save, edit and delete leave drafts |
| `SaveClaimAsDraft`, `UpdateClaimAsDraft` | Claim drafts. The response gives the updated claim summary (`ClaimableAmount`, `ClaimApprovedAmtN`, monthly and yearly used amounts) |
| `GetClaimEligibilityDetails` | **Approver view of an employee's claim entitlement:** `ClaimLimitExceeded`, `M_Balance` / `Y_Balance` (monthly and yearly balance), `Y_AmtUsed` per sub-claim |
| `GetClaimExchangeRates` (`getCurrencyRatesMAL`) | Malaysia-specific currency rate list (`CurrencyRateN`, `EffectiveDate`, foreign-language currency names) |

### 11.5 Profile, documents, read receipts

| Endpoint | Purpose |
|---|---|
| `GetAdditionalDetails` | Dropdown lists for profile forms: `BankList`, `bloodGroup`, `ChildCategory` (tax relief %), etc. |
| `GetApprovalRequest` | One profile-change request with **`currentDetail` vs `newDetail`** for the approver's side-by-side view |
| `Profile/SaveProfilePhoto` | Upload a profile photo |
| `Employeeprofileimage` | Fetch an employee's photo (`StaffImage`); used in approval screens |
| `UpdateMemoViewStatus`, `GetEmployeeviewedMemo`, `UpdateLetterViewStatus` | **Read receipts** for HR memos and letters |
| `GetCompanyPolicyViewList`, `UpdateUserPolicyViewStatus` | Policy list with `IsUserReviewed`; records that the employee **read and acknowledged** a policy |
| `GMFileDownload` | Download a grievance attachment |
| `GetTrainingCertificate`, `GetTrainingCustomCertificate` | Download course certificates (`FileBytearray`, `FileName`) |

So HR can see **who opened which payslip, tax form, memo, letter and policy, and when**.

### 11.6 Device and time

| Endpoint | Purpose |
|---|---|
| `SaveWifiBSSID` | Sends the currently connected Wi-Fi BSSID to the server, used to set up or update the Wi-Fi allow-list behind Wi-Fi-restricted clocking (§9.3; `WifiUpdateActivity`) |
| `GetServerUTCTime` | Gets the **server's time**, so clock-ins can use server time rather than the phone's clock |

### 11.7 Scheduling (remaining)

| Endpoint | Purpose |
|---|---|
| `GetScheduleData`, `Schedule/GetEScheduleData` | Schedule grid (`ScheduleData {EmpCodeC, ShiftName, ScheduleDate, StartTime…, GraceTimeN}`, `DateWiseNotes`) |
| `Schedule/GetAttedanceHistory` | Clock history for schedule customers, including `ImagePathC` (selfie) and `LocationNameC` |
| `GetAllScheduleDataList`, `GetAssginScheduleList`, `GetBranchDataList`, `GetEmpPendingRequestDetails`, `GetSCHRequestHistoryDetails` | Branch and day grids that combine clocking (`ClockIn/Out`, `BreakIn/Out`), attendance requests, change requests and claim status in one `AllScheduleDataGrabList` |
| `DownloadScheduleConflicts` | Downloads a file of scheduling conflicts |
| Auto Schedule: `ShiftSchedule/GetEmployeeShiftMobile`, `ShiftSchedule/GetEmployeeWiseShiftMobile`, `ShiftPlan/GetShiftPlanMobile`, `ShiftSchedule/GetRolesListCount`, `ShiftSchedule/GetReasonListMobile` | Read shifts, master plan, role counts and reasons |
| `ShiftSchedule/RemoveShift` (DELETE), `ShiftSchedule/DayStatus` (PUT) | Remove a shift; mark a day's status (for example a leave day) |
| `CopySchedule/CopyShift` | Copy last week's schedule |
| `PublishSchedule/ValidatePublish`, `PublishSchedule/UnPublishMobile` (DELETE) | **Check before publishing** (`publishValidations`, `errorType`) and unpublish |
| `ApproveReject/WaitingForApprovels`, `ApproveReject/ApproveRejectRequest`, `MySchedule/GetSCHRequestHistory`, `MySchedule/ViewAttachment` | Request approval queue and history |
| Advanced Schedule: `AdvSCH/GetSessionDetails`, `AdvSCH/GetEmpEditSessionDetails`, `AdvSCH/GetEmpAssignedSessionDetails`, `AdvSCH/GetStaffList` | Sessions (shifts) with roles, colours and breaks; staff with `OpenSlot`; weekly OT hours and the **manpower list**; auto-publish flag |
| `Schedule/GetEmpShiftAvailabilityMaster`, `Schedule/AddEmpShiftAvailabilityMaster` | Up to **3 availability windows per day** (`FromTime_1..3` / `ToTime_1..3`) |
| **`AdvSCH/{endPoint}`** (`apiRequestResponseCheck`) | A **catch-all endpoint**: the app fills in the path at runtime and gets a raw response. It's flexible, but it means the client can call *any* `AdvSCH/…` server route. The server must enforce authorization on every route, not depend on the app only calling the ones it should |

---

## 12. Inside the large classes

### 12.1 `common/Fetch.java` (8,917 lines) and `common/Post.java` (8,341 lines): the API layer

These two classes hold almost every network call. Each **inner class is a feature handler** that builds the request, encrypts it into `VKModel`, calls Retrofit, saves results to Realm or `SharedPreferences`, and reports back through a listener. **Almost every handler calls `Logout` on HTTP 401**, so an expired session logs the user out.

**`Fetch` (48 handlers):** reads and settings

| Handler | Endpoints | Writes settings |
|---|---|---|
| `Authentication` | `LoginTrackIMEI`, `ActivateOTP`, `FP`, `GetAuthUrl`, `RegisterMobileDevice`, `NotificationSetting`, `SecurepayslipandIR8A`, `GetApprovalCount`, `PushNotificationTimeLogHistory` | **Login bootstrap:** `UserAppRightList` → module flags (§8.2), licence tiers, `AllowFlexiPlace`, `AllowFlexiTime`, `ALLOWFLEXIPLACEHALFDAY`, clock notifications, auto-schedule tokens, kiosk |
| `Authentication2FA` | `CheckFor2FALoginRequest`, `LoginRequestApprRej` | — |
| `LeaveUserProfile` | `validateemployeechange` | `ProfileResult` → approver and admin flags (§8.1), `CLOCK_IN/OUT_REMAINDER_ENABLED`, `LOCAL_CLOCK_NOTIFICATION_REQUIRED`, **`TERMINATION_DATE`** |
| `AttendanceUserProfile` | `validateemployeechange`, `SearchEmployee(V1)`, `DownloadPhotoV1` | Attendance policy: `sp_attendance_id`, **`sp_geofencing_meter`**, `sp_job_required`, `sp_remarksRequired`, `sp_selfie_required`, `sp_change_employee_id` |
| `AttendanceTracking` | `MobileTracker/GetMobileTrackingDetails`, `GetMobileTrackingDetailsV1`, `SaveMobileTrackingStatus` | Tracking profile |
| `TMS` | Attendance schedule, OT apply, cancel, calculate and approve | — |
| `Leave*` (8 handlers) | Eligibility, off-in-lieu, reasons, calendar, status, claim limits, day count | — |
| `Claim*` (4) and `exchangeRate` | Eligibility, limits, pro-rating, summary, currency | — |
| `PersonalParticular` | Employee details, additional details, approval request, assets, documents | — |
| `CompanyPolicy`, `MemoDetail`, `LetterHeadDetails` | Lists plus **read receipts** | — |
| `Payroll` | `GetDashBoardDetail`, `GetEmpLastSalary` | — |
| `ProjectCostApiHandler` | Job-cost endpoints (§9.20) | — |
| `ReleaseNotes`, `JobsAnnouncement`, `ServerUTCTime`, `TokenRegistration`, `OneMap`, `eSchedule` | As named | — |

**`Post` (44 handlers):** writes and actions

| Handler | Endpoints |
|---|---|
| `AttendanceClockMyTime` | `timelog`, `UploadPhotoWithVerify(V1)`, `UploadPhotoWithObj`, `AttendanceSchedule/TimeLog`, `GetScheduleOfTheDay` (`postClockWithPhoto`, `postClockWithoutPhoto`, `postClockTiming`, `markMyClocking`, `postClockInLuxand`) |
| `AttendanceFaceVerify`, `Attendance`, `AttendanceJob`, `AttendanceTracking` | Face verify and register, history, jobs, `SaveMobileTrackerV1` |
| `NotifyOutOfLocationWorkFromHome` | WFH geofence events (§9.5) |
| `a` | `UpdateGeo` |
| `Leave*` (10 handlers) | Apply, approve, cancel, update, drafts (save, edit, delete, list), paternity, `LeaveAssign` (schedule leave + conflicts) |
| `EClaim` | Apply, cancel, approve, drafts, files, calculations, eligibility |
| `Payslip`, `IncomeTax` | Payslip, TDS, IR8A, IR56, EA, IT declarations, read receipts |
| `UpdatePersonalParticular`, `Profile` | Profile change requests, documents, photo |
| `SaveCandidateDetail` | Onboarding (§9.11) |
| `BranchSchedule` | Legacy branch scheduling |
| `GrievanceManagement`, `CreateTicket`, `GetTickets*`, `ReplyCloseTicket` | §9.14 |
| `TrainingManagement`, `MeetingRoomBooking`, `Reward*`, `ReferAndEarn`, `FeedbackForm`, `EtmsPieChart`, `WifiUpdate`, `PushNotificationRegistration` | As named |

### 12.2 `fragment/attendance/Attendance.java` (7,600 lines, 641 members)

The clock screen. It's obfuscated (single-letter methods), but the structure is:

- **Lifecycle and map:** Google Map, Fused Location callbacks, reverse geocoding (`t()` → `getFromLocation`, shows *"Getting your GPS location…"*), the 50 m address refresh, and listing places within 500 m (§4.2).
- **Clock button handlers:** one for each **clock path** (IN, OUT, kiosk, on behalf, job, schedule/eSchedule, Luxand vs ML face). Each runs the **check sequence from §9.3**.
- **The checks are copied, not shared:** the geofence check (`clockig_validation_message`) appears **11 times** in `Attendance.java`, the photo check 11 times and the Wi-Fi check 4 times. `EScheduleAttendance.java` has another **25** copies of the geofence and Wi-Fi checks. Because each path has its own copy, they can drift apart, so a fix or rule change might not reach every path. This affects reliability, and it's worth checking in a security review.
- **Customer-specific branches:** `brightSonCustomerId(...)` (one customer's job logic), `checkLuxandCustomerId` (`sp_Luxand_Enable`, Luxand vs ML Kit per customer), `getOldCameraCustomer`, `isV4Customer`.
- **Submitting:** `postClockTiming` / `postClockWithPhoto` / `postClockWithoutPhoto` → `Post.AttendanceClockMyTime`. Offline punches go to Realm with the offline thank-you message.

### 12.3 `common/Utility.java` (3,511 lines): shared helpers

| Group | Helpers |
|---|---|
| Crypto and config | `encrypt` / `decrypt` / **`decryptOLD`**, `vkEncrypt` / `vkDecryptStandard`, `getBase` (URL decryption), `getFormatedData`, `getGUID`, native getters (§10.1) |
| **Device integrity** | `isDevModeEnabled`, **`isTimeAutomatic`**, **`isTimezoneAutomatic`** (checks that the phone's time and time zone are set automatically), **`isMockSettingsON`**, **`checkMockPermissionApps`** (finds installed apps that hold mock-location permission), `isGpsEnabled`, `isDeviceSecure`, `isBioMetricSecure`, `isHuawei` / `isAsus` (device-specific fixes) |
| Location | `getDistance`, `getDistanceValueOnly`, `convertMeterToKm`, `getLocationText`, `hasLocationPermission`, `getTrackingMinutes`, `getWFLTrackingHours`, `isWFLEnaled`, `isWorkFromHomeEnabled` |
| Face | `isFaceRecognitionRequired`, `getAntiSpoofingStatus`, `getExceptionAntiSpoofingStatus`, `saveAntiSpoofingThresholdValues`, `getAntiSpoofing{Blur,Laplacian,Model}Threshold`, **`saveTemplate` / `saveTemplateFromByteArray` / `getTemplateFile`** (face templates stored on the phone), `deleteFace` |
| Licences and roles | `checkAttendanceLicense`, `checkLeaveLicense`, `checkPayrollLicense`, `checkWorkFromHomeLicence`, `isScheduleCustomer`, `scheduleAppLicenseTypeID`, `tmsAppLicenseTypeID`, `getIsETMSBackupApprover`, `isscheduleapprovecheck`, `iseschedulebackupofficercheck`, `isSSOCountries` |
| Device info | `getDeviceId`, `getDeviceName`, `getDeviceDetails`, `getFullAndroidVersion`, **`getBatteryPercentage`**, `getNetworkType` / `getConnectionType`, `getApplicationState` |
| Read receipts | `sendMemoReadStatusToServer`, `sendLetterReadStatusToServer`, `sendPolicyReadStatusToServer` |
| Privacy display | `shrinkEmailWithSymbols`, `shrinkMobile` (mask the email or phone number in OTP screens) |

---

## 13. Layouts and translations

### 13.1 Translations

- **Real translations:** only five languages are translated for the app itself:

| Language | Strings |
|---|---|
| Malay (`ms`) | 3,291 |
| Chinese, Simplified (`zh-rCN`) | 3,298 |
| Chinese, Traditional TW (`zh-rTW`) | 3,232 |
| Chinese, Traditional HK (`zh-rHK`) | 2,901 |
| Thai (`th-rTH`) | 2,250 |

- **Other languages:** about 80 more locales have only 320–384 strings each, which come from AndroidX and Google libraries, not the app. English is the base (3,618 strings).
- **Notes:** Thai and HK Chinese cover noticeably fewer strings than the base, so some screens fall back to English. The server also sends some messages in two languages (for example `ChineseMessage` in face-verify results, §Appendix B).

### 13.2 Layouts

- **Count:** **940 layout files**: about 122 `activity_*`, 96 `fragment_*`, 84 list or item rows, 38 `leave_*`, 19 `claim_*`, 19 dialogs, 15 notification layouts, 13 `es_*` (eSchedule).
- **Device variants:** `layout-land` (landscape), `layout-sw600dp` (**tablets, used for kiosk mode**), `layout-v26` and `layout-watch` (from libraries).
- **Two face-registration layouts** (`face_register_activity_camerax_ml` and `…_ml_es` for schedule customers).

### 13.3 Resource configuration

- **No `network_security_config.xml`.** With `usesCleartextTraffic="true"` set on the application (§6 #1), HTTP is allowed to every host, and there's no certificate pinning in the config either.
- **`res/xml/file_paths.xml` (FileProvider):** it includes `<external-path name="external_storage_root" path="." />` and `<external-path name="external_files" path="." />`, which cover the **whole external storage**. Any `content://` URI the app shares can then point anywhere in shared storage. It's broader than needed; usually only the app's own folder is shared.
- **`splits0.xml`:** the Play split-APK config (base + ABI + density).

---

## 14. Bundled assets (`assets/`)

| File | Size | Purpose |
|---|---|---|
| `index.html`, `pdf.min.js`, `pdf.worker.min.js`, `pdf_viewer.min.css` | ~0.9 MB | **PDF.js viewer** loaded in a WebView (`LoadPdfFromUrl(url)`) for payslips, policies and memos |
| `voicewave.html`, `siriwave.js`, `siriwave9.js` | small | Voice waveform animation (open-source **SiriWave.js**) shown while the voice assistant listens (§9.19) |
| `colors.json` | 1 KB | A 16+ colour pastel palette (`color1…`) used for schedule shift and session colours and avatars (`Utility` reads it 3 times) |
| `hand_landmarker.task` | 7.8 MB | MediaPipe hand model (hand-over-face check) |
| `live/model_1.*`, `live/model_2.*`, `live/config.json` | ~3.6 MB | **Liveness models** (§15) |
| `detection/detection.*` | ~1.9 MB | Face detector for the liveness engine |
| `models_bundled/*` | — | ML Kit face models: `blazeface.tfl`, `contours.tfl`, `fssd_*`, `MFT_fssd_*`, and **eye-open / smile classifiers** (`BCLlefteyeclosed`, `BCLrighteyeclosed`, `BCLjoy`) used for blink-style checks |
| `mlkit_odt_*` | — | ML Kit object detection (receipt and document scanning) |
| `dexopt/baseline.prof(m)` | — | Android baseline profile (startup performance) |
| `com/tom_roush/…`, `io/michaelrocks/…` | — | PdfBox fonts; libphonenumber metadata |
| **`profile_detail.json`** | 3 KB | **A complete employee profile response**, see below |

**Finding: personal data shipped in the APK.**
- **What it is:** `assets/profile_detail.json` is a full `GetEmployeeDetails` response with a **specific person's details**: name of the emergency contact and relationship, phone numbers, a Singapore home address and a foreign (Thai) address, email (partly masked), gender, marital status and nationality, plus the `strHrCode`, `addressDetail`, `contactDetail`, `spouseDetail`, `childrenDetail`, `nationalServiceDetail` and `personalInformation` blocks.
- **Is it used?** It's only read by `Utility.loadJSONFromAsset()`, which **nothing calls**, so it's a leftover test file. But it ships to every installation.
- **Whose data?** Whether it's a real employee or invented test data can't be told from the APK. If it's real, it's a personal-data exposure under Singapore's PDPA.
- **This report deliberately doesn't reproduce any of the values.**

---

## 15. Native face and liveness engines (from the Java side)

The native code wasn't disassembled. This section describes how the app uses the engines through their Java and JNI interfaces, and the bundled configuration.

### 15.1 Liveness: MiniVision Silent-Face-Anti-Spoofing

- **Which engine:** `antispoofing/AntiSpoofingFacialRecognition` calls **`com.mv.livebodyexample.EngineWrapper`** → `com.mv.engine.FaceDetector` + `com.mv.engine.Live` → `libengine.so`. This is MiniVision's open-source **Silent-Face-Anti-Spoofing** Android demo (Apache-2.0), built into the app largely as-is (package name `livebodyexample`).
- **Models:** a face detector (`assets/detection`) and **two liveness CNNs** (`assets/live/model_1`, `model_2`, 80×80 input, crop scales 2.7 and 4.0, from `config.json`). Their scores are combined into one liveness score.
- **Checks run on each frame:** `engineWrapper.detect(frame, …)` must find a face (`getHasFace()`). Then three checks run alongside it, each against a threshold **sent by the server** (`AntiSpoof{Brightness,Laplacian,Model}ThresholdAndriod`, stored by `saveAntiSpoofingThresholdValues`):
  1. **Brightness** (*"Your Face captured under Low Light…"*).
  2. **Sharpness**, using the OpenCV **Laplacian variance** (a blur measure). Re-photographed screens and prints tend to be blurrier.
  3. **The model's liveness score.**
- **Extra checks:** MediaPipe hand occlusion and the ML Kit eye-open classifiers (kiosk eye check).
- **Exceptions:** `getExceptionAntiSpoofingStatus` lets some users or customers skip the check.

### 15.2 Face matching: Luxand FaceSDK

- **Setup:** `FSDK.ActivateLibrary(<licence key>)` → `FSDK.Initialize()`. The **Luxand licence key is a hard-coded string in the Java code** (not reproduced here).
- **Enrolment and matching:**
  - Detection and templates: `SetFaceDetectionParameters`, `SetFaceDetectionThreshold`, `DetectFace`, `GetFaceTemplateInRegion` → `FSDK_FaceTemplate`.
  - Matching: **`FSDK.MatchFaces(template1, template2)`** gives a similarity score. The pass mark comes from **`FSDK.GetMatchingThresholdAtFAR(far)`**: the threshold is set by a target **false acceptance rate** rather than a fixed number. The FAR value is set in code.
  - Tracker: `CreateTracker`, `SetTrackerMultipleParameters`, and **`SaveTrackerMemoryToFile` / `LoadTrackerMemoryFromFile`**, which keep the tracker's face memory in a `.dat` file in the app's private data folder.
- **On-device storage:** face **templates and tracker memory are kept on the phone** (`Utility.saveTemplate*`, `getTemplateFile`, the tracker `.dat`) as well as on the server (`RegisterPhoto`). That's biometric data at rest in the app's sandbox, and `deleteFace` removes it.
- **Which engine runs:** each customer is set to Luxand or the ML Kit + server path (`sp_Luxand_Enable` / `checkLuxandCustomerId`). On the server path, the client sends `MatchPercentage`, and the server's `VerifyFaceV1` returns `FaceMatchPercentage` and a match decision (`matched_Data`).
- **Old build:** the native `libfsdk.so` is a 2016-era build (§10.1).

---

## 16. Web portal: passive analysis (public pages only)

**Scope and method:** on 2026-11-03 I made ordinary unauthenticated `GET` requests to the public pages a visitor sees. I read the HTML, headers and inline JavaScript and **didn't** log in, submit forms, call any `?handler=` endpoint, scan, or guess paths. Cookie, token and key values are redacted.

### 16.1 How the portal is laid out

```
www.info-tech.com.my            Corporate site (WordPress, behind Cloudflare)
   └─ link → www.infotech-cloudhr.com.my        "Cloud HR" portal (MY)
                 Microsoft-IIS/10.0, ASP.NET 4.0.30319 (WebForms, Login.aspx)
                 └─ 302 → https://login-infotech.com/Login?rUrl=…/Login.aspx
                             Central SSO login (ASP.NET Core Razor Pages, behind Cloudflare)
   └─ link → infotech-accounting.com/login/      Digi Accounting (sister product)
```

- **Shared login:** `login-infotech.com` is the **central sign-in** for Info-Tech's products. It's the web side of the same SSO framework as the app (§10.5), and it sends the user back to the product through `rUrl`.
- **Other regions:** the APK also references `infotech-cloudhr.com.sg`, so each country probably has its own Cloud HR host behind the same SSO.

### 16.2 Login page features (`login-infotech.com/Login`)

| Feature | Evidence in the page |
|---|---|
| Email **or mobile number** login (with country code) | `UserNameType` = Email or Mobile, `Credential.CountryCode`, `Credential.UserId`, `Credential.Password` |
| CSRF protection | `__RequestVerificationToken` in each form, plus an `XSRF-TOKEN` header on AJAX calls |
| **Device fingerprint** | `fingerprintv4.min.js` (FingerprintJS) fills the hidden `uniqueId`, the web version of the app's device binding |
| 2FA and OTP | `?handler=GenerateValidateOTP`, `regeneratedtwoFA()`, `submitOTP()`, a 6-digit OTP with a countdown timer |
| Forgot password | Email or mobile → OTP → reset |
| Microsoft login | `?handler=MSLogin` |
| **Singpass login** | `?handler=SSOSubmit` / `SSOSubmitNew`, `GetSingPassAuthorizationNew`, `GetSingPassToken(New)`, `SetSingPassDetails`, `GetDetails` |
| Session housekeeping | `AjaxCheck`, `resetActivity()` (idle timeout), `deleteAllCookies()` on logout |
| Staging or live detection | The script sets `sourceFrom = 'Live'` if the hostname contains `login-infotech`, otherwise `'Staging'`. The **same page code runs on a staging host** |

**Request encryption (`encryptWithPublicKey`):** the page encrypts data with a **random AES key** (AES-CBC) and encrypts that key with the server's **RSA-OAEP / SHA-256 public key** (Web Crypto). The encrypted key and nonce travel in custom headers (`CustomKey`, `CustomNounce`). Unlike the app (§3.2), no secret key is shipped to the client here: only a public key, which is the correct design.

### 16.3 Singpass on the web: two flows side by side

**New flow (`singpassFlowMode = "new"`):**
- **What the browser does:** it creates a **PKCE** pair (`generateCodeVerifier` → SHA-256 → `generateCodeChallenge`) and redirects to Singpass. On return it posts only `{code, codeVerifier}` to `?handler=GetSingPassTokenNew`.
- **What the server does:** the page's own comment says *"token exchange, DPoP, decryption and ID-token validation all happen server-side"*. **This is the right design.**

**Legacy flow (still in the page and runs when the flag isn't "new"):**
1. `GetDetails()` gets Singpass settings from the server (`iclientId`, `iaud`, `ikid`, `ialg`, `iredirectUri`, **`iecPublicKey`**, **`iecPrivatekey`**, `iUTCData`), encrypted only for transport.
2. The browser builds and **signs the Singpass `client_assertion` JWT itself**: `jose.importJWK(iecPublicKey, ialg)` → `new jose.SignJWT({}).setIssuer(clientId)…sign(key)`. A JWT can only be signed with a **private** key, so the field called `iecPublicKey` must actually hold the **private signing key**.
3. The browser then **decrypts the Singpass ID token (JWE)** with `jose.importJWK(iecPrivatekey)`.

**Finding (web, High):** the legacy flow's code shows that **Info-Tech's Singpass private signing key and decryption key are sent to the browser** by an endpoint (`GetDetails`) that the **login page calls before anyone logs in**. If that endpoint is still active, anyone who opens the login page could get the keys and pose as Info-Tech's Singpass client or decrypt Singpass ID tokens meant for it. It's the **same design flaw as the mobile app** (§10.1, finding #10), exposed through a runtime endpoint instead of a shipped file.
- **Not verified on purpose.** Calling `GetDetails` would mean retrieving secret keys from a production system without authorization. The finding rests only on the public page code.
- **Context:** the new server-side flow shows Info-Tech is already moving away from this. The fix is to **remove the legacy branch and the `GetDetails` key delivery**, and to **rotate the Singpass keys**, since they may already have been exposed.

### 16.4 Headers and client libraries

| Host | Observations |
|---|---|
| `www.infotech-cloudhr.com.my` (legacy) | **`X-AspNet-Version: 4.0.30319`** and `Server: Microsoft-IIS/10.0` are exposed (version details). **The CSP is very weak:** `default-src *; script-src 'self' http://* 'unsafe-inline' 'unsafe-eval'`, which allows scripts from any HTTP origin plus inline code and `eval`, so it gives almost no protection against cross-site scripting. HSTS without `includeSubDomains`. `X-Frame-Options: SAMEORIGIN`. Antiforgery cookie `Secure; HttpOnly` |
| `login-infotech.com` (SSO) | Behind Cloudflare. **A much tighter CSP** (`default-src 'self'`, `object-src 'none'`, `base-uri 'self'`, an allow-list of exact CDN script URLs), though it still has `'unsafe-inline'` and `blob:` for scripts. HSTS with `includeSubDomains; preload`. Session, antiforgery and temp-data cookies are `Secure; HttpOnly; SameSite`. `X-Frame-Options: SAMEORIGIN` |
| `www.info-tech.com.my` (marketing) | WordPress behind Cloudflare. HSTS with preload, `X-Frame-Options: DENY`, `frame-ancestors 'none'`. Google Tag Manager, Facebook and LinkedIn tracking pixels |

**Front-end libraries on the SSO page:**
- jQuery + jQuery UI + jQuery Validation (unobtrusive) and Bootstrap 5.3.3
- SweetAlert2 (both v8 and v11)
- **moment.js 2.24.0** and moment-timezone 0.5.23. moment 2.24.0 is **old**: it's older than the fix for the CVE-2022-31129 regular-expression slowdown, which was fixed in 2.29.4.
- jose 4.3.7 and crypto-js 4.2.0
- FingerprintJS v4
- Custom `biri.min.js` (a browser-ID helper)

### 16.5 Recommended next steps

- **Level 2 (your own account):** record a normal login and your own leave or payslip views in your own browser. That would confirm the real API payloads, whether the web uses the same `{"str": …}` encryption as the app, and how Cloud HR (WebForms) talks to the APIs.
- **Level 3 (only with written permission from Info-Tech):** access-control testing (§8.6), the legacy Singpass `GetDetails` exposure, the generic `AdvSCH/{endPoint}` route, and admin functions.
- **Disclosure:** add the web Singpass key-delivery finding to the responsible-disclosure report together with app findings #10, #11 and #15.

---

## Appendix A: All API endpoints (method, path → Java method)

Extracted from the Retrofit interfaces. Paths are relative to the server for each module (see §3.1).

### EleaveApi

| Method | Path | Java method |
|---|---|---|
| POST | `LoginRequestApprRej` | `ApproveReject2FA` |
| POST | `CheckFor2FALoginRequest` | `Authentication2FARequest` |
| POST | `CreateTicket` | `CreateTicket` |
| POST | `GetAttachments` | `GetAttachmentsDetails` |
| POST | `GetClaimLimitForValidate` | `GetClaimLimitForValidate` |
| POST | `GetDraftLeaves` | `GetDraftListApplied` |
| POST | `GetEmpAssertDataForNotification` | `GetEmpAssertDataForNotification` |
| POST | `GetReferandEarn` | `GetReferandEarnHistory` |
| POST | `GetTickets` | `Gettickets` |
| POST | `GetTicketDetails` | `GetticketsDetails` |
| POST | `GetScheduleOfTheDay` | `Scheduleoftheday` |
| POST | `ApplyClaim` | `applyEClaimDrafts` |
| POST | `ClaimApproveReject` | `approveClaim` |
| POST | `GMApproverTicketReply` | `approverTicketReply` |
| POST | `CancelClaim` | `cancelEClaimSummary` |
| POST | `GMCreateTicket` | `createGrievanceTicket` |
| POST | `DeleteCandidateDetails` | `deleteCandidateDetails` |
| POST | `DeleteDraftClaim` | `deleteEClaimDrafts` |
| POST | `DeleteDocAttachment` | `deletePersonalAttachments` |
| POST | `DownloadjobCostAttachFile` | `deleteProjectCostAttachment` |
| POST | `DownloadCandidateFile` | `downloadCandidateFile` |
| POST | `DownloadScheduleConflicts` | `downloadScheduleConflicts` |
| POST | `EmpJobCostRequestApply` | `empJobCostRequestApply` |
| POST | `GeClaimtProrateDtls` | `fetchProrate` |
| POST | `GetAbsenteesDetail` | `getAbsenteesDetail` |
| POST | `GetAllEmployees` | `getAllEmployees` |
| POST | `GetBranchScheduleData` | `getAllEmployeesMonthWise` |
| POST | `GetEmpAssertDataForNotification` | `getAnnouncementData` |
| POST | `GetAllAppVersions` | `getAppVersion` |
| POST | `GetApprovalRequest` | `getApprovalDetails` |
| POST | `GetClaimEligibilityDetails` | `getApproverViewEmployeeEligibility` |
| POST | `GetBranchAndShift` | `getBranchAndShift` |
| POST | `GetBranchAndShift` | `getBranchAndShiftDetails` |
| POST | `GetBranchAndCompany` | `getBranchCompany` |
| POST | `GetBranchScheduleSingleEmployee7Days` | `getBranchShiftfor7Days` |
| POST | `GetMeetingCalendar` | `getCalendarMeetingData` |
| POST | `GetCandidateProfileDetails` | `getCandidateFileDetails` |
| POST | `GetCalculatedClaimAmount` | `getClaimCalculatedAmount` |
| POST | `GetEClaimEligibility` | `getClaimEligible` |
| GET | `GetGstDetail` | `getClaimGSTDetails` |
| POST | `GetClaims_ForEmployee` | `getClaimLimit` |
| POST | `GetEClaims_PendingForApproval` | `getClaimPendingApproval` |
| POST | `GetClockinData` | `getClockInData` |
| POST | `GetCompanyPolicyList` | `getCompanyPolicy` |
| POST | `GetCompanyPolicyViewList` | `getCompanyPolicyViewedStatus` |
| POST | `GetCorporateTrainingDetails` | `getCorporateTrainingData` |
| POST | `GetCostCenter` | `getCostCenterCode` |
| POST | `GetCountryAndCurrency` | `getCountryCurrency` |
| POST | `GetClaimCurrencyRates` | `getCurrencyRates` |
| POST | `GetClaimExchangeRates` | `getCurrencyRatesMAL` |
| POST | `GetCustomTrainingDetails` | `getCustomTrainingList` |
| POST | `LeaveDraft_Delete` | `getDeleteLeaveDraft` |
| POST | `GetDraftLeaves` | `getDraftList` |
| POST | `GetEClaimDrafts` | `getEClaimDrafts` |
| POST | `GetClaimExchangeRate` | `getEClaimExchangeRate` |
| POST | `GetEClaimSettings` | `getEClaimSettings` |
| POST | `GetEClaimSummary` | `getEClaimSummary` |
| POST | `GetEScheduleData` | `getEScheduleData` |
| POST | `GetEScheduleDataGrabRequest` | `getEScheduleDataGrabRequest` |
| POST | `GetEScheduleDataGrabRequestSave` | `getEScheduleDataGrabRequestSave` |
| POST | `GetEarlyOutDetail` | `getEarlyOutDetail` |
| POST | `LeaveDraft_Update` | `getEditLeaveDraft` |
| POST | `GetEmpAssertData` | `getEmpAssertData` |
| POST | `GetAdditionalDetails` | `getEmployeeAdditionalDetails` |
| POST | `GetEmployeeClaimSummary` | `getEmployeeClaimSummary` |
| POST | `GetTrainingCalendarEmpDetails` | `getEmployeeCourseDetails` |
| POST | `GetEmployeeDetails` | `getEmployeeDetails` |
| POST | `GetEmployeeDocList` | `getEmployeeDocList` |
| POST | `GetJobCostEmployeeAppliedClaims` | `getEmployeeJobCostAppliedRequests` |
| POST | `GetEmployeeJobCostRequests` | `getEmployeeJobCostRequests` |
| POST | `GetEmpLastSalary` | `getEmployeeLastSalary` |
| POST | `GetEmployeeSchedules` | `getEmployeeSchedules` |
| POST | `GetEmployeeviewedMemo` | `getEmployeeviewedMemo` |
| POST | `GMApproverTicketList` | `getGMApproverTicketList` |
| POST | `GMEmpTicketList` | `getGMEmpTicketList` |
| POST | `GMFileDownload` | `getGMFileDownload` |
| POST | `GMOtherApproverTicketList` | `getGMOtherApproverTicketList` |
| POST | `GMTicketChatHistory` | `getGMTicketChatHistory` |
| POST | `GMTicketReply` | `getGMTicketReply` |
| POST | `IR56/EmpGetIR56YearType` | `getHKIncomeTax` |
| POST | `GetTDS` | `getINDetailTDS` |
| POST | `GetNewPayslip` | `getINPayslipDetail` |
| POST | `EmpIR8A/EmpGetIR8AYearType` | `getIncomeTax` |
| POST | `UpdateTaxViewStatus` | `getIncomeTaxAcknowledge` |
| POST | `IR8A/UpdateTaxViewStatus` | `getIncomeTaxHKAcknowledge` |
| POST | `EmpIR8A/EmpGetIR8AYearTypeViewStatus` | `getIncomeTaxSG` |
| POST | `IncomeTax/GetITDeclarationDetails` | `getInvestmentDetails` |
| POST | `JobCostGetApproveUserProfile` | `getJobCostApproversList` |
| POST | `GetJobCostCodeList` | `getJobCostCodes` |
| POST | `GetJobCostReasonList` | `getJobCostReasonCodes` |
| POST | `DontShowAnnouncementPopup` | `getJobsLahPopupAnnouncement` |
| POST | `GetLatenessDetailNew` | `getLateness` |
| POST | `GetApproveUserProfile` | `getLeaveApproverSelectionList` |
| POST | `GetLeaveForCalendar_ByDate` | `getLeaveCalendar` |
| POST | `GetLeaveDetail` | `getLeaveDetail` |
| POST | `GetLeaveEligibility_ForEmployee` | `getLeaveEligible` |
| POST | `GetLeaveHistoryforEmployee` | `getLeaveHistoryonYearChange` |
| POST | `GetLeaveForCalendar_ByDate` | `getLeaveMonthCalendar` |
| POST | `GetLeaveTakenDetails_ForEmployee` | `getLeaveStatus` |
| POST | `GetLeaveTakenDetails_ForEmployee` | `getLeaveStatusHistory` |
| POST | `GetEmployeeLetters` | `getLetterHeadDetails` |
| POST | `EmpGetEAYear` | `getMAIncomeTax` |
| POST | `payslip` | `getMAPayslip` |
| POST | `CheckUserAssignedRoom` | `getMeetingLicenseCheck` |
| POST | `GetMeetingyearList` | `getMeetingRoomList` |
| POST | `GetMeetingRoomList` | `getMeetingRoomListData` |
| POST | `GetMeetingassignedyears` | `getMeetingYearList` |
| POST | `GetEmployeeMemo` | `getMemoDetails` |
| POST | `GetApprovalCount` | `getNotificationCount` |
| POST | `GetOffInLieuEligibility_ForEmployee` | `getOILEligible` |
| POST | `ETMSGetApproveUserProfile` | `getOTApproverList` |
| POST | `GetOTReasonList` | `getOTReasonList` |
| POST | `GetEmployeeAppliedClaims_ToApprove` | `getOverTimeApproveClaimsListData` |
| POST | `ETMSAttendanceApproveReject` | `getOverTimeApproveRejectData` |
| POST | `EmpOTCancelRequest` | `getOverTimeCancelRequestData` |
| POST | `EmpOTRequestApply` | `getOverTimeRequestApplyData` |
| POST | `EmpOTRequestCalculations` | `getOverTimeRequestCalculationsData` |
| POST | `GetAllEmployeeClaims` | `getOverTimeRequestData` |
| POST | `GetEmployeeOverTimeDetail` | `getOvertimeDetail` |
| POST | `Get_PaternityLeave_Employee` | `getPaternityChildDates` |
| POST | `GetDashBoardDetail` | `getPayrollData` |
| POST | `EmpPayslip/EmpGetPayPeriod` | `getPayslip` |
| POST | `UpdatePaySlipViewStatus` | `getPayslipAcknowledge` |
| POST | `GetNewPayslip` | `getPayslipAusDetail` |
| POST | `GetNewPayslip` | `getPayslipDetail` |
| POST | `GetNewPayslip` | `getPayslipDetailPDF` |
| POST | `GetPendingLeavesForApproval` | `getPendingApprovalStatus` |
| POST | `GetPendingLeaves_BasedonID` | `getPendingLeavesListBasedID` |
| POST | `GetPendingLeavesForApproval` | `getPendingStatusApprove` |
| POST | `Rewards/RedeemReward` | `getRedeemReward` |
| POST | `GetReleaseNotes` | `getReleaseNotes` |
| POST | `ReleaseNotesDetail` | `getReleaseNotesDetail` |
| POST | `GetResDetail` | `getRestDetails` |
| POST | `Rewards/GetRewardDetails` | `getRewardDetails` |
| POST | `LeaveDraftApplywithParam_insert` | `getSaveLeaveDraft` |
| POST | `GetScheduleData` | `getScheduleData` |
| GET | `GetServerUTCTime` | `getServerUtcTime` |
| GET | `GetAuthUrl` | `getSingPassAuthUrl` |
| POST | `GetLatenessDetail` | `getTmsLateness` |
| POST | `GetTotalAppliedDays_BasedOnExcludeDay` | `getTotalLeaveDays` |
| POST | `GetTrainingCalendar` | `getTrainingCalendarData` |
| POST | `GetTrainingCertificate` | `getTrainingCertificate` |
| POST | `GetTrainingCustomCertificate` | `getTrainingCustomCertificate` |
| POST | `GetTrainingDetails` | `getTrainingList` |
| POST | `GetUserProfile` | `getUserProfile` |
| POST | `GetWorkingDetail` | `getWorkingDetail` |
| POST | `GetYTSDetail` | `getYetStartDetails` |
| POST | `GetClaimEligibilityDetails` | `getclaimeligibility` |
| POST | `getemployeeattendancesummary` | `getemployeeattendancesummary` |
| POST | `JobCostAttendanceApproveReject` | `jobCostAttendanceApproveReject` |
| POST | `ESCHLeaveApply` | `leaveAssignToEmployee` |
| POST | `Logout` | `logoutUser` |
| POST | `ApprovalProcess` | `postApproveEmployeeDetails` |
| POST | `UpdateEmployeeDetails` | `postEmployeeDetails` |
| POST | `GetReasonListnew` | `postGetReasonList` |
| POST | `LeaveApply` | `postLeaveApply` |
| POST | `LeaveApproveReject` | `postLeaveApproval` |
| POST | `CancelLeave` | `postLeaveCancel` |
| POST | `FileUploadOrRemove` | `postLeaveUpdateAttachment` |
| POST | `LeaveUpdate` | `postLeaveUpdateRemarks` |
| POST | `Login` | `postLogin` |
| POST | `NotificationSetting` | `postNotificationStatus` |
| POST | `RegisterDevice` | `postRegisterDevice` |
| POST | `SecurepayslipandIR8A` | `postSecurePayslipStatus` |
| POST | `ReapplyClaim` | `reapplyEClaimDrafts` |
| POST | `RegisterCorporateTraining` | `registerCorporateTraining` |
| POST | `CustomTraningRequest` | `registerCustomTraining` |
| POST | `RegisterMobileDevice` | `registerMobileDevice` |
| POST | `ReplyTicket` | `replyTicket` |
| POST | `Rewards/GetIndustryList` | `rewardsGetIndustryList` |
| POST | `Rewards/GetRewards` | `rewardsGetRewards` |
| POST | `SaveCandidateDetails` | `saveCandidateDetails` |
| POST | `SaveCandidateFileDetails` | `saveCandidateFileDetails` |
| POST | `SaveClaimAsDraft` | `saveDraftRequest` |
| POST | `SaveReferandEarn` | `saveReferAndEarn` |
| POST | `SaveScheduleDetails` | `saveScheduleDetails` |
| POST | `UpdateRoomMeeting` | `saveUpdateDeleteMeetingRoom` |
| POST | `GetAvailableMeetingRooms` | `searchMeetingRoomList` |
| POST | `SendOTPFor2FA` | `sendOTPFor2FA` |
| POST | `SubmitAttendanceDetails` | `submitTrainingAttendance` |
| POST | `GMTicketReopen` | `ticketReopen` |
| POST | `UpdateCandidateStatus` | `updateCandidateStatus` |
| POST | `UpdateClaimAsDraft` | `updateDraftRequest` |
| POST | `UpdateLetterViewStatus` | `updateLetterHeadViewStatus` |
| POST | `UpdateLikeStatus` | `updateLikeStatus` |
| POST | `UpdateGeo` | `updateLiveLocation` |
| POST | `UpdateMemoViewStatus` | `updateMemoViewStatus` |
| POST | `UpdateStartedNotes` | `updateStartedNotes` |
| POST | `UpdateUserPolicyViewStatus` | `updateUserPolicyViewStatus` |
| POST | `SaveWifiBSSID` | `updateWifiDetails` |
| POST | `ECFileUploadOrRemove` | `uploadOrRemoveClaimAttachments` |
| POST | `UploadDocAttachment` | `uploadPersonalAttachments` |
| POST | `Profile/SaveProfilePhoto` | `uploadProfileImage` |

### EScheduleApiV1

| Method | Path | Java method |
|---|---|---|
| POST | `ShiftSchedule/AddShift` | `addStaffFromOtherBranch` |
| POST | `MySchedule/ApplyChangeRequest` | `applyChangeAttendanceScheduleRequest` |
| POST | `ApproveReject/ApproveRejectRequest` | `approveOrRejectRequest` |
| POST | `ShiftSchedule/AddShift` | `assignShiftToEmployee` |
| POST | `ShiftSchedule/AddShift` | `bookGrabSchedule` |
| POST | `ShiftSchedule/AddShift` | `bookGrabScheduleV1` |
| POST | `MySchedule/GetAutoOTcalculationValue` | `calculateOtValueForAutoSchedule` |
| POST | `CopySchedule/CopyShift` | `copyScheduleFromLastWeekV2` |
| POST | `ShiftSchedule/CreateAddShift` | `createAndAssignShiftV1` |
| GET | `Allowance` | `fetchAllowanceList` |
| GET | `ShiftSchedule/GetEmployeeWiseShiftMobile` | `fetchAssignedShiftDetailV1` |
| GET | `AttendanceSchedule/FetchAttendanceSchedule/{date}` | `fetchAttendanceDetail` |
| GET | `AttendanceSchedule/FetchAttendanceHistory` | `fetchAttendanceHistoryForAutoSchedule` |
| GET | `Branch` | `fetchBranchListForAutoSchedule` |
| GET | `Branch/GetBranchesForUnplannedShift` | `fetchBranchListForUnplanned` |
| GET | `Availability/FetchDailyAvailability/{EmployeeCode}` | `fetchDailyAvailability` |
| GET | `schedule/DisplaySettings` | `fetchDisplaySetting` |
| GET | `Employees/EmployeeList` | `fetchEmployeeList` |
| POST | `ShiftSchedule/GetEmployeeShiftMobile` | `fetchEmployeeShift` |
| GET | `ShiftSchedule/GetEventsForBranch` | `fetchEventByWeek` |
| GET | `Transaction/FetchTransactionFilters` | `fetchFilterDataResponse` |
| GET | `GrabSchedule/GetDateWiseGrab` | `fetchGrabDateListForCalendar` |
| POST | `GrabSchedule/GetGrabHistory` | `fetchGrabHistory` |
| POST | `GrabSchedule/FetchGrabSchedule` | `fetchGrabRequestListV2` |
| GET | `GrabSchedule/FetchGrabSchedule` | `fetchGrabSchedule` |
| GET | `ShiftPlan/GetRoleListMobile` | `fetchMasterRoleList` |
| GET | `ShiftPlan/GetShiftPlanMobile` | `fetchMasterShiftPlan` |
| GET | `MySchedule/GetSCHRequestHistory` | `fetchMyRequestHistory` |
| GET | `MySchedule/GetMyScheduleData` | `fetchMyScheduleData` |
| GET | `ApproveReject/WaitingForApprovels` | `fetchPendingRequestListV1` |
| GET | `ShiftSchedule/GetReasonListMobile` | `fetchReasonListForAutoSchedule` |
| GET | `MySchedule/GetEmpNotificationShifts` | `fetchRemainderShifts` |
| GET | `MySchedule/GetChangeScheduleList` | `fetchRequestScheduleList` |
| GET | `ShiftSchedule/GetRolesListCount` | `fetchRolesListWithCount` |
| POST | `Transaction/FetchTransactions` | `fetchTransactionList` |
| GET | `UnplannedShift/FetchUnplannedShift_V2` | `fetchUnplannedShiftRequests` |
| GET | `ShiftPlan/GetFullShiftPlanMobile` | `fetchViewPlannedShiftList` |
| GET | `Availability/fetchWeeklyAvailability` | `fetchWeeklyAvailability` |
| POST | `Availability/InsertUpdateDailyAvailability` | `insertUpdateDailyAvailability` |
| POST | `Availability/InsertUpdateWeeklyAvailability` | `insertUpdateWeeklyAvailability` |
| POST | `PublishSchedule/InvalidPublishEmployeesExcel` | `invalidPublishEmployeesExcel` |
| PUT | `ShiftSchedule/DayStatus` | `leaveStatusUpdate` |
| PUT | `Transaction/UpdateLock` | `lockUnlockStatusUpdate` |
| POST | `AttendanceSchedule/TimeLog` | `markMyClocking` |
| POST | `UnplannedShift/UnplannedShiftApproveReject_V2` | `postApproveRejectForUnplanned` |
| POST | `GrabSchedule/SendGrabRequest` | `publishGrabSchedule` |
| POST | `PublishSchedule/PublishMobile` | `publishSchedule` |
| DELETE | `ShiftSchedule/RemoveShift` | `removeAssignedShift` |
| DELETE | `PublishSchedule/UnPublishMobile` | `unPublishSchedule` |
| POST | `SalesMatrix/AddUpdateSalesMatrix` | `updateActualSales` |
| PUT | `ShiftSchedule/EmployeeWiseShiftUpdate` | `updateShiftApiCallForAutoSchedule` |
| PUT | `Transaction/UpdateTransactionMobile` | `updateTransaction` |
| POST | `PublishSchedule/ValidatePublish` | `validatePublishSchedule` |
| POST | `MySchedule/ViewAttachment` | `viewAndDownloadAttachment` |

### FieldTrackingAPI

| Method | Path | Java method |
|---|---|---|
| POST | `AddFeedback` | `addFeedback` |
| POST | `DeleteChatHistory` | `deleteChatHistory` |
| POST | `ModuleSwitch/token` | `fetchExchangeToken` |
| POST | `GetAllScheduleDataList` | `getAllScheduleDataList` |
| POST | `SCHEmpOTRequestApproveReject` | `getApproveReject` |
| POST | `GetAssginScheduleList` | `getAssignedSchedule` |
| POST | `GetAutoOTCalculation` | `getAutoOTCalculation` |
| POST | `GetBlobFiles` | `getBlobFiles` |
| POST | `Onboarding/GetBlobFiles` | `getBlobTopics` |
| POST | `GetBotReply` | `getBotReply` |
| POST | `GetBranchDataList` | `getBranchList` |
| POST | `SCHCancelRequestApply` | `getCancelRequestApply` |
| POST | `GetChangeWithEmpList` | `getChangeWithList` |
| POST | `GetChatHistory` | `getChatHistory` |
| POST | `GetChatResponse` | `getChatResponse` |
| POST | `FWA/ApplyFWA` | `getFWAApply` |
| POST | `FWA/ApproveRejectFWA` | `getFWAApproveReject` |
| POST | `FWA/CancelFWA` | `getFWACancel` |
| POST | `FWA/GetEmployeeFWAHistory` | `getFWAHistory` |
| POST | `FWA/GetEmployeeFWALimit` | `getFWALimit` |
| POST | `FWA/FWASettingsforToday` | `getFWASetting` |
| POST | `FWA/GetEmployeeFWASummary` | `getFWASummary` |
| POST | `FWA/GetEmployeeFWASummary` | `getFWASummaryMonth` |
| POST | `FWA/UpdateFWA` | `getFWAUpdate` |
| POST | `FWA/GetEmployeeFWAforApprover` | `getFWAforApprover` |
| POST | `GetEmpAvailabilityData` | `getMyAvailabilityData` |
| POST | `Onboarding/GetBotReply` | `getOnboardingBotReply` |
| POST | `GetEmpPendingRequestList` | `getPendingRequest` |
| POST | `GetEmpPendingRequestDetails` | `getPendingRequestDetails` |
| POST | `ETMSPreOTApproveReject` | `getPreOTApproveReject` |
| POST | `EmpPreOTRequestApply` | `getPreOTRequestApply` |
| POST | `GetRequestAccessList` | `getRequestAccess` |
| POST | `SCHEmpOTRequestApply` | `getRequestApply` |
| POST | `GetSCHRequestHistory` | `getRequestHistory` |
| POST | `GetSCHRequestHistoryDetails` | `getRequestHistoryDetails` |
| POST | `GetRequestScheduleList` | `getRequestSchedule` |
| POST | `Onboarding/DeleteChatHistory` | `onboardingDeleteChatHistory` |
| POST | `Onboarding/GetChatHistory` | `onboardingGetChatHistory` |
| POST | `Onboarding/GetChatResponse` | `onboardingGetChatResponse` |
| POST | `UpdateTransaction` | `updateTransaction` |

### AttendanceApi

| Method | Path | Java method |
|---|---|---|
| POST | `ActivateOTP` | `activateOTP` |
| POST | `RegisterPhoto` | `attendanceLuxandRegisterFace` |
| POST | `VerifyFace` | `attendanceLuxandVerifyFace` |
| POST | `RegisterPhotoV1` | `attendanceRegisterFace` |
| POST | `VerifyFaceV1` | `attendanceVerifyFace` |
| POST | `EmpCP/EmpChangePassword` | `changePassword` |
| POST | `UploadPhotoWithVerify` | `clockWithNonLuxandPhotoWithVerify` |
| POST | `UploadPhotoWithObj` | `clockWithPhotoWithObj` |
| POST | `UploadPhotoWithVerifyV1` | `clockWithPhotoWithVerify` |
| POST | `timelog` | `clockWithoutPhoto` |
| POST | `LoginTrackIMEI` | `doForceLogin` |
| POST | `DownloadPhotoV1` | `downloadRegisteredPhoto` |
| POST | `Employeeprofileimage` | `getEmployeeImage` |
| POST | `FP` | `getForgotPassword` |
| POST | `EmpJobMaster/EmpGetJobs` | `getJob` |
| POST | `EmpJobMaster/EmpGetSearchJobs` | `getSearchJob` |
| POST | `RegisterTTToken` | `getTTRegisterToken` |
| POST | `GetMobileTrackingDetailsV1` | `getTrackingEndTime` |
| GET | `MobileTracker/GetMobileTrackingDetails` | `getTrackingProfile` |
| POST | `validateemployeechange` | `getUserProfile` |
| POST | `PushNotificationTimeLogHistory` | `getWorkFromHomeHistory` |
| POST | `PushNotificationTimeLogOffline` | `offlineLocationApiCall` |
| POST | `PushNotificationTimeLogOnline` | `onlineLocationApiCall` |
| POST | `PushNotificationTimeLog` | `outOfLocationCallApi` |
| POST | `AttendanceHistory/GetAttedanceHistory` | `postGetAttendanceHostory` |
| POST | `SaveMobileTrackerV1` | `postTrackingData` |
| POST | `SaveFeedback` | `saveFeedbackData` |
| POST | `SaveMobileTrackingStatus` | `saveTrackingStatus` |
| POST | `SearchEmployeeV1?` | `searchEmployeeDetails` |
| POST | `SearchEmployee?` | `searchLuxandEmployeeDetails` |

### EScheduleNewFlowApi

| Method | Path | Java method |
|---|---|---|
| POST | `AdvSCH/AddOtherBranchStaff` | `addOtherBranchStaff` |
| POST | `AdvSCH/{endPoint}` | `apiRequestResponseCheck` |
| POST | `AdvSCH/EmpSessionMapping` | `assignShiftToEmployee` |
| POST | `AdvSCH/CopySchedule` | `copySchedule` |
| POST | `AdvSCH/ManageSession` | `createShift` |
| POST | `AdvSCH/CreateAndMappingEmpSession` | `createShiftWithAssign` |
| POST | `AdvSCH/ReturnHTMLTemplate` | `downloadReport` |
| POST | `AdvSCH/GetBranchList` | `fetchBranchList` |
| POST | `AdvSCH/GetEmpAssignedSessionDetails` | `fetchEmployeeShiftList` |
| POST | `AdvSch/GetTransactionFilterVals` | `fetchFilterDataResponse` |
| POST | `Schedule/GetEmpShiftAvailabilityMaster` | `fetchMasterAvailability` |
| POST | `AdvSCH/ReasonList` | `fetchReasonList` |
| POST | `AdvSCH/GetRoleList` | `fetchRoleList` |
| POST | `AdvSCH/GetRoleDetails` | `fetchShiftBasedRoleList` |
| POST | `AdvSCH/GetSessionDetails` | `fetchShiftList` |
| POST | `AdvSCH/GetStaffList` | `fetchStaffList` |
| POST | `AdvSCH/GetTransactionDetails` | `fetchTransactionList` |
| POST | `Schedule/GetEScheduleData` | `getEScheduleData` |
| POST | `AdvSCH/AddReason` | `leaveUpdateToEmployee` |
| POST | `AdvSCH/LockTransactions` | `lockUnlockStatusUpdate` |
| POST | `AdvSCH/GetCommentsList` | `notesApiForSessionAndDate` |
| POST | `Schedule/GetAttedanceHistory` | `postGetAttendanceHistory` |
| POST | `AdvSCH/GrabSchedule` | `publishGrabSchedule` |
| POST | `AdvSCH/PublishedDateForWeek` | `publishSchedule` |
| POST | `AdvSCH/RemoveSession` | `removeSession` |
| POST | `Schedule/GetScheduleOfTheDay` | `scheduleOfTheDay` |
| POST | `AdvSch/AddUpdateSalesMatrix` | `updateActualSales` |
| POST | `AdvSCH/ManageSession` | `updateEmpAssignedShift` |
| POST | `Schedule/AddEmpShiftAvailabilityMaster` | `updateMasterSetupAvailability` |
| POST | `AdvSCH/GetEmpEditSessionDetails` | `viewShiftDetail` |

### SSOApiService

| Method | Path | Java method |
|---|---|---|
| POST | `LoginRequestApprRej` | `sso2FALoginApproveReject` |
| POST | `ActivateNewProfilebyOTP` | `ssoActivateNewProfileOTP` |
| POST | `ChangePassword` | `ssoChangePassword` |
| POST | `CheckFor2FALoginRequest` | `ssoCheckLogin2FA` |
| POST | `DeviceToken` | `ssoDeviceToken` |
| POST | `ForgotPassword` | `ssoForgotPassword` |
| POST | `GenerateAndValidateOTP` | `ssoGenerateOTP` |
| POST | `Authenticate` | `ssoLogin` |
| POST | `Logout` | `ssoLogout` |
| POST | `RegisterDevice` | `ssoRegisterDevices` |
| POST | `RegisterMobileDevice` | `ssoRegisterTrustedDevice` |
| POST | `RejectLoginApproval` | `ssoRejectLoginApproval` |
| POST | `SendOTPFor2FA` | `ssoSendOTPFor2FA` |

### AutoScheduleTokenApi

| Method | Path | Java method |
|---|---|---|
| POST | `Auth/login` | `autoScheduleLogin` |
| GET | `Auth/module-entry/mobile` | `fetchAccessTokenUsingExchangeToken` |
| POST | `Auth/module-entry/sso/infotech` | `fetchAccessTokenUsingSSOToken` |
| POST | `Auth/refresh` | `getNewAccessToken` |

### OnemapApi

| Method | Path | Java method |
|---|---|---|
| GET | `api/public/revgeocode` | `getReverseGeocode` |
| POST | `api/auth/post/getToken` | `postLogin` |


### SingPassApi / GoogleInfoTechApi

| Method | Path | Java method |
|---|---|---|
| POST | `/token` | `getToken` (Singpass) |
| GET | `timezone/json?` | `getGoogleTimeZoneDetail` (Google Maps API) |

---

## Appendix B: API response models

Each endpoint's response type is resolved from its Retrofit signature, with the type's JSON fields (from `@SerializedName`). Nested objects are shown one level deep (first 12 fields). An endpoint listed more than once has a different response model per region (for example `GetNewPayslip` has separate SG/MY, AU/NZ and India payslip models). `raw` means the endpoint returns a file or plain string (`ResponseBody` / `String`). Almost every request body is the encrypted `VKModel {"str"}` wrapper (§3.2), so the real request fields are the request models in §9.

Coverage: **354 of 364** endpoint signatures resolved to a typed model.


### EleaveApi

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `LoginRequestApprRej` | `LoginApproveRejectResponse` | `IsSuccess`, `twoFATransaction` |
| POST | `CheckFor2FALoginRequest` | `Login2FAResponse` | `IsSuccess`, `twoFATransaction` {BrowserName, EmailId, IDN, TransactionGUID, CreatedDateTime, TransactionID} |
| POST | `CreateTicket` | `ReplyCloseTicketResponse` | `isSuccess`, `strMessage` |
| POST | `GetAttachments` | `GetAttachmentResponse` | `Data` |
| POST | `GetClaimLimitForValidate` | `ClaimLimitValidateResponse` | `DateValMessage`, `Monthlyusedamount`, `yearlylimit`, `pervisitlimit`, `monthlimit`, `limitcheck`, `limitvalue`, `Yearlyusedamount`, `finprochk`, `NoofdayYr`, `Noofdayaply` |
| POST | `GetDraftLeaves` | `DraftListModalClass` | `AttachmentBytes`, `AttachmentURL`, `DayUnitN`, `DentalClaimAmtN`, `EmpCodeC`, `EmpNameC`, `EmpShortNameC`, `fTotApplied`, `FileNameC`, `FileNameNewC`, `HrLvFromTime`, `HrLvToTime`, `IdN`, `intApplicationType`, `LeaveFromDateD`, `LeaveStatusC`, `LeaveToDateD`, `MLClaimAmtN`, `ReasonCodeC`, `ReasonNameC`, `RemarksC`, `strCustomerGUIID`, `EmpSelELApproveSupCodeC`, `UniqueID` |
| POST | `GetEmpAssertDataForNotification` | `AssetDetail` | `ErrorMsg`, `IsSuccess`, `request` {AssertCode, AssertName, IssueDate, Qty, ReturnDate, SerialNoC} |
| POST | `GetReferandEarn` | `ReferList` | `Data` {ContactEmail, ContactName, ContactNumber, CreateDate}, `StatusMessage`, `isSuccess` |
| POST | `GetTickets` | `GetTicketResponse` | `Description`, `ReportedBy`, `Subject`, `TicketId`, `whencreated` |
| POST | `GetTicketDetails` | `GetTicketResponseDetails` | `Attachment` {AttachmentFilename}, `Data` {TicketId, Subject, Description, Remarks, WhenChanged, UserName, ReportedName, ShowCloseIcon} |
| POST | `GetScheduleOfTheDay` | `ScheduleDataResponse` | `IsSuccess`, `AllScheduleDataGrabList`, `PunchActionList`, `clockIN`, `clockOUT`, `ScheduleId`, `BranchCodeC`, `BranchNameC`, `EmpNameC`, `ClockIn`, `ClockOut`, `TotClckHrs`, `LeaveNameC`, `SessionNameC`, `TotHrs`, `SessionDate`, `ColorFlag` |
| POST | `ApplyClaim` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `ClaimApproveReject` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `GMApproverTicketReply` | `ApproverUpdateGrievanceResponse` | `IsSuccess`, `FileUploadStatus` {IsSuccess, FileName, FileID} |
| POST | `CancelClaim` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `GMCreateTicket` | `SubmitGrievanceResponse` | `IsSuccess`, `FileUploadStatus` {IsSuccess, FileName, FileID}, `TicketNo` |
| POST | `DeleteCandidateDetails` | `SaveCandidateDetailResponse` | `ErrMsg`, `IsSuccess` |
| POST | `DeleteDraftClaim` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `DeleteDocAttachment` | `ApproveResponse` | `ErrorMsg`, `IsSuccess` |
| POST | `DownloadjobCostAttachFile` | `raw (Call<ResponseBody>)` | — |
| POST | `DownloadCandidateFile` | `ApproveResponse` | `ErrorMsg`, `IsSuccess` |
| POST | `DownloadScheduleConflicts` | `raw (Call<ResponseBody>)` | — |
| POST | `EmpJobCostRequestApply` | `OTSubmitResponse` | `strMessage`, `blnResult` |
| POST | `GeClaimtProrateDtls` | `ClaimProrateResponse` | `EmpCodeC`, `ClaimCode`, `Type`, `strMessage`, `strCustomerGUID`, `ClaimProrateSplitDtls` {EmpCodeC, ClaimCode, Period, Entitlement, Prorate, Used, Balance, strCustomerGUID, Type, strMessage} |
| POST | `GetAbsenteesDetail` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `GetAllEmployees` | `GetAllEmployeeResponse` | `employees` {Employee_Code, Employee_Name, Branch_Code, Branch_Name, Shift_Code, Shift_Name, Reason_Code, Reason_Name}, `IsSuccess` |
| POST | `GetBranchScheduleData` | `GetBranchEmployeeMonthwiseResponse` | `SheduleData` {IsMoreEmployee, DateD, employees}, `IsSuccess`, `employees` {Branch_Name, Employee_Name, Shift_Code, Employee_Code, Shift_Name, Branch_Code, Reason_Code, Reason_Name} |
| POST | `GetEmpAssertDataForNotification` | `AnnouncementDetail` | `ErrorMsg`, `IsSuccess`, `request` {AssertCode, AssertName, IssueDate, Qty, ReturnDate, SerialNoC} |
| POST | `GetAllAppVersions` | `MobOS` | `MobOS` {ANDROID} |
| POST | `GetApprovalRequest` | `ApproveDetailResponse` | `ErrorMsg`, `IsSuccess`, `request` {currentDetail, employee, id, newDetail} |
| POST | `GetClaimEligibilityDetails` | `ClaimApproveEligibilityResponse` | `IsSuccess`, `ResponseObject` {SubClaimCode, Y_AmtUsed, ClaimLimitExceeded, ClaimGroupCode, M_Balance, Y_Balance, SId, M_LimitN, ClaimGroupName, ClaimName, M_AmtUsed, SubClaimName …} |
| POST | `GetBranchAndShift` | `BranchShiftResponse` | `Branch_List` {Branch_Code, Branch_Name}, `IsSuccess`, `Reason_List` {Reason_Code, Reason_Name}, `Shift_List` {Shift_Code, Shift_Name} |
| POST | `GetBranchAndShift` | `ResponseBranchShift` | `IsSuccess`, `Branch_List` {Branch_Name, Branch_Code, Shift_List}, `Reason_List` {Reason_Code, Reason_Name}, `Shift_List` |
| POST | `GetBranchAndCompany` | `BranchCompanyResponse` | `AddRight`, `IsSuccess`, `Branch_List` {Branch_Name, Branch_Code}, `Company_List` {Company_Id, Company_Name, Company_Code}, `isModify` |
| POST | `GetBranchScheduleSingleEmployee7Days` | `BranchWeekScheduleResponse` | `IsSuccess`, `employees` {Branch_Name, Employee_Name, Shift_Code, Reason_Code, Employee_Code, Schedule_Date, Shift_Name, Reason_Name, Branch_Code, IsLeaveModule} |
| POST | `GetMeetingCalendar` | `MeetingCalendarDataResponse` | `IsSuccess`, `ErrMsg`, `meetingMasterData` {BookedBy, BookingReason, BookingDateD, BookingEndTimeC, BookingStartTimeC, RoomNameC, BookingId, RoomCodeC} |
| POST | `GetCandidateProfileDetails` | `EmployeeDetail` | `LocalAddressC`, `LocalAddress2C`, `LocalAddress3C`, `SuburbTown`, `StateTerritories`, `CountryC`, `PostalCodeC`, `AlternateEmailIdC`, `AlternateMobileNumberC`, `AlternateHomePhoneNumberC`, `ForeignAddressC`, `ForeignAddress2C`, `ForeignAddress3C`, `ForeignCityC`, `ForeignStateC`, `ForeignCountryC`, `ForeignPostalCodeC`, `RelationshipC`, `ContactPersonMobileNumberC`, `LastDate`, `CommentC`, `mandatoryFields` {BDAttachFile, BDMPAttachFile, BDRMAttachFile, CDAttachFile, CDHomePhnNo, CDTEAttachFile, DOB, EDAttachFile, EduDetails, EmergencyContactName, EmergencyContactNo, EmergencyContactRelationship …}, `CandidateIdC`, `CandidateidN`, `MyKadIC`, `BankAccNoC`, `BankCodec`, `BankNameC`, `BankNameN`, `BranchIDN`, `BranchidC`, `CandidateUserIdC`, `ShouldAutoLogout`, `ContactPersonNameC`, `CandidateName`, `CandidateGUID`, `TabDetails`, `UserId`, `StatusC`, `GenderC`, … (+27) |
| POST | `GetCalculatedClaimAmount` | `CalculatedClaimAmountResponse` | `strCustomerGUID`, `EmpCodeC`, `ClaimGroupCodeC`, `ClaimCodeC`, `SubClaimCodeC`, `ReceiptDate`, `InputField`, `InputValue`, `OutputValue`, `strMessage`, `MileageRate`, `CalculationExplanation`, `CalcTypeCode` |
| POST | `GetEClaimEligibility` | `ClaimGroupResponse` | `ClaimMasters` {AmtUsed, balance, BlockFlag, BlockLimitN, Count, IsClaimLimitProrate, Monthly_Balance, Monthly_Eligibility, Monthly_Taken, Name, parentCode, PerVisitLimit …}, `PerBlockClaimMaster` {AmtUsed, balance, BlockFlag, BlockLimitN, Count, IsClaimLimitProrate, Monthly_Balance, Monthly_Eligibility, Monthly_Taken, Name, parentCode, PerVisitLimit …}, `IsNewRule`, `strMessage` |
| GET | `GetGstDetail` | `ClaimGSTDetailResponse` | `YearC`, `GstC` |
| POST | `GetClaims_ForEmployee` | `ClaimLimitResult` | `BalanceN`, `ClaimAvailedN`, `ClaimLimitN`, `ClaimName`, `ClaimPerVisitLimitN`, `EmpCodeC` |
| POST | `GetEClaims_PendingForApproval` | `ClaimApproveResponse` | `blnResult`, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `EClaimSummary` {DepartmentName, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, ApprovedDateC, ApprovedDateD, ApprovedGSTAmount, Attachment, Attachment1, Attachment1URL …}, `strMessage` |
| POST | `GetClockinData` | `GetClockInDataResponse` | `ScheduleData` {TimeoutN, TotOtN, Under, TimeInN, LatenessN, otTypeN} |
| POST | `GetCompanyPolicyList` | `PolicyResult` | `listCompanyPolicy` {IsUserReviewed, CompanyCode, CreatedDate, CustomerCode, DocNameOnly, DocumentName, DocumentPath}, `strResult` |
| POST | `GetCompanyPolicyViewList` | `PolicyResult` | `listCompanyPolicy` {IsUserReviewed, CompanyCode, CreatedDate, CustomerCode, DocNameOnly, DocumentName, DocumentPath}, `strResult` |
| POST | `GetCorporateTrainingDetails` | `TrainingCorporateResponse` | `IsSuccess`, `ErrMsg`, `trainingCorpoateDetails` {TrainingCodeC, CourseNameC, CourseStartDateD, CourseEndDateD, DateTimeCourseStartTimeN, DateTimeCourseEndTimeN, CourseDescriptionC, TrainingLocationC, ApprovedStatus, ApprovedDate, ApprovedUpto, PendingWith} |
| POST | `GetCostCenter` | `CostCenterCodeResponse` | `listCostCenter` {CostCenterCodeC, MainOfficeC, CostCenterNameC}, `strCostCentreName`, `strMessage` |
| POST | `GetCountryAndCurrency` | `CountryCurrencyResponse` | `listCountry` {CountryCodeC, CountryNameC, DateCreatedD, DateUpdatedD}, `listCurrency` {CanDelete, CurrencyCode, CurrencyName, DateCreatedD, DateUpdatedD, strMessage, UserIdN}, `strMessage` |
| POST | `GetClaimCurrencyRates` | `CurrencyRatesResponse` | `lstClaimCurrencyRates` {CurrencyRateN, RateId, CurrencyNameC, ForeignLang2CurrencyNameC, ForeignLangCurrencyNameC, CurrencyCodeC, EffectiveFrom, EffectiveTo}, `strMessage`, `ReceiptDate`, `CurrencyRate` |
| POST | `GetClaimExchangeRates` | `CurrencyRatesResponse` | `lstClaimCurrencyRates` {CurrencyRateN, RateId, CurrencyNameC, ForeignLang2CurrencyNameC, ForeignLangCurrencyNameC, CurrencyCodeC, EffectiveFrom, EffectiveTo}, `strMessage`, `ReceiptDate`, `CurrencyRate` |
| POST | `GetCustomTrainingDetails` | `TrainingCustomResponse` | `IsSuccess`, `ErrMsg`, `trainingCustomRequestDetails` {CURequestCode, TrainingCodeC, CourseNameC, InstitutionNameC, CertificateNameC, CourseStartDateD, CourseEndDateD, CourseStartTimeN, CourseEndTimeN, TrainingLocationC, CourseFeeN, Grant …} |
| POST | `LeaveDraft_Delete` | `DeleteDraftResult` | `blnResult`, `strMessage` |
| POST | `GetDraftLeaves` | `GetDraftListResult` | `blnResult`, `strMessage` |
| POST | `GetEClaimDrafts` | `ClaimDraftResponse` | `ECClaimTemplateDetail` {AdditionalFees, Amount, Attachment, CanApply, ClaimAmountWithExRate, ClaimCodeC, ClaimMasterCodeC, ClaimMasterNameC, ClaimNameC, ClaimTemplateName, ClaimableAmount, ClinicName …}, `ECClaimTemplateSummary` {CanReapply, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, TemplateGroupIdN, TemplateReferenceNoC}, `strMessage` |
| POST | `GetClaimExchangeRate` | `ClaimExchangeRateResponse` | `CurrencyExchangeRates` {CurrencyCode, CurrencyExchangeRatePerSGD, CurrencyName, CreatedOn, ExchangeRateSource}, `strMessage` |
| POST | `GetEClaimSettings` | `ClaimApplyResponse` | `GSTPercentage`, `listClaim` {CanDeleteC, ClaimGroupCodeC, groupSettInpFld, PayAllownCodeC, requireSetting, SubClaimCodeC, SubClaimNameC}, `listClaimGroup` {AutoDistanceCalculation, CanDeleteC, ClaimGroupCodeC, ClaimGroupNameC, groupSettInpFld, isAmount, isAttachment, isClaimableAmount, isClinicName, isCostCenterCode, isCountry, isCurrencyCode …}, `listSubClaim` {AllownCodeC, AllownNameC, ClaimCode, ClaimCodeC, ClaimGroup, ClaimGroupCode, ClaimGroupNameC, ClaimName, ClaimNameC, ClaimSubGroup, ClaimSubGroupCodeC, ClaimSubGroupNameC …}, `strCust1Name`, `strCust2Name`, `strCust3Name`, `strCust4Name`, `strCust5Name`, `strMessage`, `WarningMessage` |
| POST | `GetEClaimSummary` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `GetEScheduleData` | `ScheduleResult` | `DateWiseNotes` {BNID, ScheduleDate, ShiftGroupCodeC, Notes}, `ScheduleData` {GraceTimeN, EmpCodeC, EmpNameC, EmployeeShift, ShiftName, ScheduleDate, StartTime, EndTime, BranchName, BranchCode, ClockIn, ClockOut …} |
| POST | `GetEScheduleDataGrabRequest` | `GrapResponse` | `ScheduleDataGrabList` {AssignedStatus, EndTime, RoleCount, OpeningCount, StartTime, TotHrs, SessionNameC, BranchCodeC, BranchName, ScheduleDate, SessionID} |
| POST | `GetEScheduleDataGrabRequestSave` | `GrapSaveResponse` | `ScheduleData`, `IsStatus` |
| POST | `GetEarlyOutDetail` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `LeaveDraft_Update` | `EditDraftResult` | `blnResult`, `strMessage` |
| POST | `GetEmpAssertData` | `AssetDetail` | `ErrorMsg`, `IsSuccess`, `request` {AssertCode, AssertName, IssueDate, Qty, ReturnDate, SerialNoC} |
| POST | `GetAdditionalDetails` | `AdditionalDetailResponse` | `ErrorMsg`, `IsSuccess`, `BankList` {code, name}, `bloodGroup`, `ChildCategory` {ChildCategoryPercentID, ChildCategoryTypeId, Description}, `ChildPercentage` {ChildCategoryPercentID, ChildCategoryTypeId, Description}, `education` {code, name}, `gender`, `marital`, `nationStatus`, `nationality` {code, name}, `nsRank`, `race` {code, name}, `religion` {code, name}, `singaporeCitizen`, `state` {code, name}, `TaxDisablPerson`, `TaxSpouseDisabled`, `TaxSpouseStatus` |
| POST | `GetEmployeeClaimSummary` | `ClaimEmployeeSummaryResponse` | `CustomFields`, `ECClaimTemplateSummary`, `EClaimFilter` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `Gst`, `strMessage` |
| POST | `GetTrainingCalendarEmpDetails` | `CalendarEmployeeDataResponse` | `IsSuccess`, `trainingCalendarEmpDetails` {TrainingCodeC, EmpCodeC, CertificateExpiryDateD, AttendedC, EmpNameC}, `ErrMsg` |
| POST | `GetEmployeeDetails` | `EmployeeDetailResponse` | `ErrorMsg`, `IsSuccess`, `OldBankDetails` {BankDetails, DocAttachmentList, FileAttachmentList}, `addressDetail` {DocAttachmentList, FileAttachmentList, foreign, present}, `childrenDetail` {children, DocAttachmentList, FileAttachmentList, ChildTaxDetails}, `contactDetail` {DocAttachmentList, emergency, FileAttachmentList, personal}, `OldEducationDetail` {DocAttachmentList, Education, FileAttachmentList}, `nationalServiceDetail` {DocAttachmentList, FileAttachmentList, rank}, `NewEducationDetail` {DocAttachmentList, Education, FileAttachmentList}, `personalDetail` {SpouseDisabled, SpouseStatusTypeID, bloodGroup, DocAttachmentList, FileAttachmentList, gender, marital, TaxDisablePerson}, `personalInformation` {dateOfBirth, DocAttachmentList, drivingLicenseNo, FileAttachmentList, foreignID, nationality, race, religion}, `previousEmploymentDetail` {DocAttachmentList, FileAttachmentList, previousEmployment, previousEmploymentTaxDetails}, `spouseDetail` {birthDate, childNo, DocAttachmentList, FileAttachmentList, fin, ic, itRef, name, occupation, phone}, `StatusList` {DocSecName, DocStatus, EmpCode, RejectReason}, `strHrCode`, `strMessage` |
| POST | `GetEmployeeDocList` | `EmployeeDocumentList` | `listEmpDocs` {IdN, DocFileName, DocName, DocPath, EmpCode, PasswordStatusID}, `strResult` |
| POST | `GetJobCostEmployeeAppliedClaims` | `JobAttendanceResponse` | `listJobAttendance` {ClockDateD, EmployeeName, Email, Details, RequestMode, JobClaimStatus, RequestorRemarks, ETMSApproveS1CodeC, ETMSApproveS2CodeC, ETMSApproveHRCodeC, ETMSApproveS1NameC, ETMSApproveS2NameC …}, `strMessage`, `blnResult`, `strCustomerGUIID`, `userId`, `CompanyID`, `userActionVal`, `ActionVal`, `AppRejRemarks` |
| POST | `GetEmployeeJobCostRequests` | `JobAttendanceResponse` | `listJobAttendance` {ClockDateD, EmployeeName, Email, Details, RequestMode, JobClaimStatus, RequestorRemarks, ETMSApproveS1CodeC, ETMSApproveS2CodeC, ETMSApproveHRCodeC, ETMSApproveS1NameC, ETMSApproveS2NameC …}, `strMessage`, `blnResult`, `strCustomerGUIID`, `userId`, `CompanyID`, `userActionVal`, `ActionVal`, `AppRejRemarks` |
| POST | `GetEmpLastSalary` | `LastMonthSalary` | `YearN`, `MonthN`, `GrossWagesN`, `CPFWagesN`, `NettWagesN`, `EmployerCPFN`, `EmployeeCPFN`, `TotalCPFN`, `StatusMessage` |
| POST | `GetEmployeeSchedules` | `EmployeeSchedulesResponse` | `blnResult`, `strMessage` |
| POST | `GetEmployeeviewedMemo` | `MemoStatusResponse` | `EHRUserCodeC`, `EMemoTemplateIdN`, `intApplicationType`, `MemoCreateDateD`, `MemoCreatedID`, `strUserCode`, `ViewStatus` |
| POST | `GMApproverTicketList` | `ApproverTicketListResponse` | `TicketList` {EmpUser_Id, CanApproverReplyBack, GrievanceManagerEmailIdC, UpdatedDate, GrievanceApproverUserId, GrievanceManagerEmpNameC, StatusName, SubmittedDate, Subject, EmailIdC, EmpCodeC, Company_Id …}, `IsSuccess` |
| POST | `GMEmpTicketList` | `GrievanceListModel` | `TicketList` {EmpUser_Id, CanApproverReplyBack, GrievanceManagerEmailIdC, UpdatedDate, GrievanceApproverUserId, GrievanceManagerEmpNameC, StatusName, SubmittedDate, Subject, EmailIdC, EmpCodeC, Company_Id …}, `IsSuccess` |
| POST | `GMFileDownload` | `GetAttachmentResponse` | `Data` |
| POST | `GMOtherApproverTicketList` | `ApproverTicketOtherListResponse` | `TicketList` {EmpUser_Id, CanApproverReplyBack, GrievanceManagerEmailIdC, UpdatedDate, GrievanceApproverUserId, GrievanceManagerEmpNameC, StatusName, SubmittedDate, Subject, EmailIdC, EmpCodeC, Company_Id …}, `IsSuccess` |
| POST | `GMTicketChatHistory` | `GrievanceChatResponse` | `IsSuccess`, `CanReopenTicket`, `TicketNo`, `AttachmentList` {UploadedDate, BlobFileName, FileName, TicketNo, FilebyteArray, FileID, ContentLength}, `Chats` {SentDate, ChatSide, StatusName, ChatID, EmpNameC}, `AllowedUserStatusList` {StatusID, StatusName}, `AllowToReplyBack`, `CanApproverReplyBack`, `Subject` |
| POST | `GMTicketReply` | `ReplyGrievanceResponse` | `IsSuccess`, `FileUploadStatus` {FileID, FileName, IsSuccess} |
| POST | `IR56/EmpGetIR56YearType` | `IncomeTaxResult` | `YearN`, `TypeC`, `Year`, `year` |
| POST | `GetTDS` | `PayslipDetailINResponse` | `blnStatus`, `strMessage` |
| POST | `GetNewPayslip` | `PayslipDetailINResponse` | `blnStatus`, `strMessage` |
| POST | `EmpIR8A/EmpGetIR8AYearType` | `IncomeTaxResult` | `YearN`, `TypeC`, `Year`, `year` |
| POST | `UpdateTaxViewStatus` | `IncomeTaxAcknowledgeResponse` | `blnStatus`, `strMessage` |
| POST | `IR8A/UpdateTaxViewStatus` | `IncomeTaxAcknowledgeResponse` | `blnStatus`, `strMessage` |
| POST | `EmpIR8A/EmpGetIR8AYearTypeViewStatus` | `IncomeTaxResults` | `YearN`, `Types` {TypeC, PublishedDateD, IsViewed, ViewedDateD} |
| POST | `IncomeTax/GetITDeclarationDetails` | `InvestmentDeclartionResponse` | `IncomeTaxDeclaration` {OtherInvestmentsandExemptions, Investments80C, NetTaxPayable, RegimeType, HRA, LetOut, OtherAllowance, Exemptions80D, HouseLoan, PreviousEmployment, NetTaxableIncome, OtherIncomeSource}, `IncomeTaxProofs` {NetTaxPayable, RegimeType, Comments, NetTaxableIncome, Attachments} |
| POST | `JobCostGetApproveUserProfile` | `OTReasonListResponse` | `IsSuccess`, `OTReasonList` {DateCreatedD, LeaveC, CanDeleteC, CompanyID, ReasonNameC, CustomerCodeC, AL_MLC, LvEligibileN, DateUpdatedD, UserIdN, PayTypeC, ReasonCodeC}, `lstsuperuser` {UserID, DisplayName}, `SHowOTInReason` |
| POST | `GetJobCostCodeList` | `JobCodeResponse` | `JobCostList` {JobCostCodeC, JobCostNameC} |
| POST | `GetJobCostReasonList` | `ReasonCodeResponse` | `JobCostReasonList` {ReasonCodeC, ReasonNameC, PayTypeC, LeaveC, AL_MLC} |
| POST | `DontShowAnnouncementPopup` | `JobsLahAnnouncementPopupResponse` | `IsSuccess` |
| POST | `GetLatenessDetailNew` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `GetApproveUserProfile` | `LeaveApproveSelectResponse` | `IsSuccess`, `lstsuperuser` {UserID, DisplayName}, `strMessage` |
| POST | `GetLeaveForCalendar_ByDate` | `LeaveCalendarResult` | `ISBlocked`, `ForeignLangReasonNameC`, `CompanyNameC`, `DayN`, `DeptCodeC`, `DeptNameC`, `EmpNameC`, `EmpShortNameC`, `EmpcodeC`, `ForeignLangLeaveStatusC`, `HRCodeC`, `HolidayNameC`, `LeaveDateD`, `LeaveStatusC`, `ForeignLang2ReasonNameC`, `PayTypeC`, `ReaCodeC`, `ReaNameC`, `RemarksC`, `S1CodeC`, `S2CodeC`, `SecCodeC`, `SecNameC`, `Type` |
| POST | `GetLeaveDetail` | `OvertimeDetails` | `DashboardTable` {EmpCodeC, ClockDateD, EmpNameC, OT10N, OT15N, OT20N, OT30N, SFlatN, TotOTN, d_ClockDateD, DeptCodeC, DeptNameC …}, `ResultInfo` |
| POST | `GetLeaveEligibility_ForEmployee` | `EligibleResult` | `blnRemarksMandatory`, `listLeaveEligibility` {EligPerIncidentC, MLCreditN, MLbfN, ALBFExpiryDateD, ALbfN, AdvanceLeaveN, BalanceN, CreditN, EarnLeave, EligibleN, EmpCodeC, FYUnEarnLeave …}, `strMessage` |
| POST | `GetLeaveHistoryforEmployee` | `LeaveHistoryYearResponse` | `clsLVHistroy` {EmpCodeC, DOJD, LeaveTranAL, LeaveTranML, LeaveTranCCL, EmpNameC}, `strMessage` |
| POST | `GetLeaveForCalendar_ByDate` | `LeaveMonthResult` | `Date`, `ISBLOCKED`, `isHolliday`, `total_leave` |
| POST | `GetLeaveTakenDetails_ForEmployee` | `StatusResult` | `listLeaveSummary` {ApprovedDateTime, ApprovedUpto, AttachmentBytes, AttachmentURL, DayUnitN, DentalClaimAmtN, EmpCodeC, fTotApplied, FileNameC, FileNameNewC, ForeignLangFormattedStatusC, ForeignLangGroupNameC …}, `strMessage` |
| POST | `GetLeaveTakenDetails_ForEmployee` | `ResponseLeaveHistory` | `listLeaveSummary` {AttachmentURL, ForeignLangLeaveStatusC, HrLvToTime, LeaveStatusC, OfficerRemarksC, FormattedStatusC, blnLeaveCancelled, Type, IdN, GroupNameC, EmpShortNameC, fTotApplied …}, `strMessage` |
| POST | `GetEmployeeLetters` | `LetterHeadResponse` | `ErrorMsg`, `IsSuccess`, `request` {SubjectC, BodyMsgC, CompanyId, DocFileName, EHRUserCodeC, EHRUserNameC, empcode, FooterMsgC, intApplicationType, LetterId, MemoCreateDateD, MemoExpiryDateD …}, `MemoDocs` {DocFileName, OBStatus, OnboardingCreatedID, OnboardingDocumentID} |
| POST | `EmpGetEAYear` | `IncomeTaxResult` | `YearN`, `TypeC`, `Year`, `year` |
| POST | `payslip` | `PayslipResult` | `IsPayslipViewed`, `Month`, `MonthName`, `PayType`, `PayTypeID`, `PayslipViewedDate`, `PublishedDate` |
| POST | `CheckUserAssignedRoom` | `MeetingLicenseCheckResponse` | `meetingBookingStatus` {status}, `IsSuccess`, `ErrMsg` |
| POST | `GetMeetingyearList` | `MeetingYearListResponse` | `IsSuccess`, `ErrMsg`, `meetingMasterData` {RoomCodeC, RoomNameC, BookingDateD, BookingStartTimeC, BookingEndTimeC, BookingReason, BookedBy, RoomDescriptionC, BookingId} |
| POST | `GetMeetingRoomList` | `MeetingRoomListResponse` | `IsSuccess`, `ErrMsg`, `roomList` {RoomDescriptionC, RoomCodeC, RoomNameC} |
| POST | `GetMeetingassignedyears` | `MeetingRoomYearListResponse` | `IsSuccess`, `ErrMsg`, `year` |
| POST | `GetEmployeeMemo` | `MemoResponse` | `ErrorMsg`, `IsSuccess`, `request` {EHRUserCodeC, EMemoTemplateIdN, MemoCreateDateD, MemoCreatedID, OnboardingCreatedID, OBStatus, SubjectC, BodyMsgC, EHRUserNameC, strEmpCode, FooterMsgC, intApplicationType …}, `MemoDocs` {OnboardingCreatedID, OnboardingDocumentID, OBStatus, DocFileName, MemoCreatedId, MemoDocId} |
| POST | `GetApprovalCount` | `NotificationCountResponse` | `Count`, `APP` |
| POST | `GetOffInLieuEligibility_ForEmployee` | `OILEligibleResult` | `blnRemarksMandatory`, `listOffInLieuEligibility` {DateD, EffDateD, ExpDateD, GroupNameC, IdN, MemoC, OILBalanceN, OILStatusC, OILTakenN, OILUnitN, OffINLieuIdN, PayTypeC}, `strMessage` |
| POST | `ETMSGetApproveUserProfile` | `OTReasonListResponse` | `IsSuccess`, `OTReasonList` {DateCreatedD, LeaveC, CanDeleteC, CompanyID, ReasonNameC, CustomerCodeC, AL_MLC, LvEligibileN, DateUpdatedD, UserIdN, PayTypeC, ReasonCodeC}, `lstsuperuser` {UserID, DisplayName}, `SHowOTInReason` |
| POST | `GetOTReasonList` | `OTReasonListResponse` | `IsSuccess`, `OTReasonList` {DateCreatedD, LeaveC, CanDeleteC, CompanyID, ReasonNameC, CustomerCodeC, AL_MLC, LvEligibileN, DateUpdatedD, UserIdN, PayTypeC, ReasonCodeC}, `lstsuperuser` {UserID, DisplayName}, `SHowOTInReason` |
| POST | `GetEmployeeAppliedClaims_ToApprove` | `OTApproveListResult` | `listAttendance` {ReaCodeC, ThirdLevelApprovedDateTime, OtTypeN, LatenessN, ThirdLevelApproverEmail, RequestedTotalFLatOTN, UnderN, ThirdLevelApproverName, FlatOTN, OT10N, ApproverName, TotalFlatOTN …}, `strMessage`, `blnResult`, `SHowOTInReason` |
| POST | `ETMSAttendanceApproveReject` | `OTApproveRejectResult` | `strMessage`, `blnResult`, `LockedRecords`, `LockedEmpList` {code, date} |
| POST | `EmpOTCancelRequest` | `OTRequestCancelResult` | `a` |
| POST | `EmpOTRequestApply` | `OTRequestApplyResult` | `strMessage`, `blnResult` |
| POST | `EmpOTRequestCalculations` | `OTCalculationResult` | `ScheduleData`, `CalculatedOTData` {RequestedUnderN, BOName, RequestedOT10N, BOEmail, RequestedOT15N, NextLevelApproverId, RequestedOT20N, NextLevelApproverName, RequestedOT30N, NextLevelApproverEmail, RequestedTotalOTN, LastUpdatedDate …} |
| POST | `GetAllEmployeeClaims` | `OTRequestDetailsResult` | `ScheduleData` {ClockDateD, RequestorRemarks, RequestedTotalOT, IsCancelInitiated, RequestedReaCodeC, RequestedReaNameC, AttachmentName, OTtype, RequestedFlatOTN, TotalFlatOT, PendingWith, ApprovedUpTo …}, `IsSuccess`, `CalculatedOTData`, `IsAllowEmployeeEditOT`, `SHowOTInReason` |
| POST | `GetEmployeeOverTimeDetail` | `OvertimeDetails` | `DashboardTable` {EmpCodeC, ClockDateD, EmpNameC, OT10N, OT15N, OT20N, OT30N, SFlatN, TotOTN, d_ClockDateD, DeptCodeC, DeptNameC …}, `ResultInfo` |
| POST | `Get_PaternityLeave_Employee` | `PaternityLeaveResponse` | `ChildDOBD`, `IdN`, `PatEligibilityN`, `TakenN` |
| POST | `GetDashBoardDetail` | `CalculatedPayrollResponse` | `HeadCountChartTable` {GroupName, EmpTypeCount}, `JoinVsTermintionTable`, `PayrollRevenueTable` {GroupName, PayElementList, TotalCostN}, `PreMonthRevenueTable` {GroupName, PayElementList, TotalCostN}, `ChartDetails` {MonthN, YearN, GroupName, CatgTypeN, DescC, EmpCodeC, EmpNameC, ActDateD}, `ResponseStatus`, `ResponseStatusMessage` |
| POST | `EmpPayslip/EmpGetPayPeriod` | `PayslipResult` | `IsPayslipViewed`, `Month`, `MonthName`, `PayType`, `PayTypeID`, `PayslipViewedDate`, `PublishedDate` |
| POST | `UpdatePaySlipViewStatus` | `PayslipAcknowledgeResponse` | `blnStatus`, `strMessage` |
| POST | `GetNewPayslip` | `PaySlipDetailAusResponse` | `ABN`, `ALBALN`, `MLBALN`, `OTTotalHours`, `AnnualSalary`, `Award`, `AwardClassification`, `BankAcNoC`, `BankNameC`, `BonusDeclaredYearN`, `CompanyNameC`, `EmpCodeC`, `EmpNameC`, `EndDateD`, `FlatAmtN`, `FlatHrsN`, `FlatRate`, `FromDateD`, `GrossWagesN`, `HourlySalary`, `IncrementAmtN`, `IRDNumber`, `JobType`, `KiwiSaverInfo` {AmountN, FundName, MemberNumber}, `MessageInfo` {Message1, Message2, MessageTitle}, `NettWagesN`, `OTInfo` {OT10HrsN, OT10HrsNAIN, OT10HrsNBIN, OT10Total, OT10TotalAIN, OT10TotalBIN, OT15HrsN, OT15HrsNAIN, OT15HrsNBIN, OT15Total, OT15TotalAIN, OT15TotalBIN …}, `OTRateInfo` {OT1, OT2, OT3, OT4}, `PayAdditionInfo` {Addition, AmountN}, `AllowanceInfo` {Allowance, AmountN}, `PaySlipDateD`, `PayDeductionsInfo` {AmountN, Deduction}, `SuperAmount`, `SuperInfo` {AmountN, FundName, MemberNumber}, `SuperPercentage`, `TaxAmount`, `TaxCode`, `TotalDeduction`, `TotalOTAmtBIN`, `TotalShiftAmtN`, … (+3) |
| POST | `GetNewPayslip` | `PaySlipDetailResponse` | `CompanyLogo` {CompanyID, CompanyLogo}, `CommonInfo` {ALBalN, ALN, BankAcNo1C, BankAcNoC, BankAcNoC1, BankAcNoC2, BankCode1C, BankCodeC, BankName1, BankName1C, BankName2, BankNameC …}, `EmpInfo` {ALBalN, ALN, BankAcNo1C, BankAcNoC, BankAcNoC1, BankAcNoC2, BankCode1C, BankCodeC, BankName1, BankName1C, BankName2, BankNameC …}, `AdditionsInfo` {AddiAmountN, AddiDescC, AddiIsIndentC, EarningDesc, Earnings}, `DeductionsInfo` {DedAmountN, DedDescC, DedIsIndentC, DeductDesc, Deductions}, `PayslipParams` {CompanyID, EmpCodes, HideAccountno, HideCompanyAddress, HideDRRate, HideDailyRateCalcC, HideHRRate, HideHrRateCalcC, HideLeaveInfo, HideLeaveInfoC, HideMOMEmpCodeC, HideOTCalcC …} |
| POST | `GetNewPayslip` | `raw (Call<ResponseBody>)` | — |
| POST | `GetPendingLeavesForApproval` | `PendingApprovalResult` | `ApprovedUpto`, `AttachmentBytes`, `AttachmentURL`, `blnLeaveCancelled`, `DayUnitN`, `DentalClaimAmtN`, `DeptCodeC`, `DeptNameC`, `EmpCodeC`, `EmpNameC`, `EmpShortNameC`, `fTotApplied`, `FileNameC`, `FileNameNewC`, `HRCode`, `HrLvFromTime`, `HrLvToTime`, `IdN`, `intApplicationType`, `LastUpdateDate`, `LvAppliedDateD`, `LeaveFromDateD`, `LeaveStatusC`, `LeaveSummaryHide`, `LeaveToDateD`, `MLClaimAmtN`, `OfficerRemarksC`, `ReasonCodeC`, `ReasonNameC`, `RemarksC`, `S1Code`, `S2Code`, `strCustomerGUIID`, `strELUserLevelC`, `Type`, `UniqueID`, `UpdateTMSC` |
| POST | `GetPendingLeaves_BasedonID` | `PendingLeaveResponseItem` | `FormattedStatusC`, `blnLeaveCancelled`, `GroupNameC`, `fTotApplied`, `AttachmentBytes`, `PendingWithUserLevel`, `S1Code`, `NoofTotalDays`, `PendingWithStatus`, `ReasonNameC`, `ApprovedUpto`, `DayUnitN`, `ReasonCodeC`, `S2Code`, `RemarksC`, `EligibilityN`, `ApprovedDateTime`, `DentalClaimAmtN`, `FileNameC`, `HrLvFromTime`, `GroupIDN`, `UpdateTMSC`, `ForeignLangReasonNameC`, `GroupCodeC`, `FileNameNewC`, `AttachmentURL`, `UniqueID`, `ForeignLangLeaveStatusC`, `strELUserLevelC`, `TakenN`, `TotalN`, `HrLvToTime`, `MLClaimAmtN`, `OfficerRemarksC`, `LvAppliedDateD`, `Type`, `DeptCodeC`, `IdN`, `EmpShortNameC`, `ForeignLangFormattedStatusC`, … (+18) |
| POST | `GetPendingLeavesForApproval` | `LeaveApproveResponseList` | `AttachmentURL`, `ForeignLangLeaveStatusC`, `HrLvToTime`, `LeaveStatusC`, `OfficerRemarksC`, `FormattedStatusC`, `blnLeaveCancelled`, `Type`, `IdN`, `GroupNameC`, `EmpShortNameC`, `fTotApplied`, `AttachmentBytes`, `ForeignLangFormattedStatusC`, `LastUpdateDate`, `S1Code`, `strELUserLevelC`, `ApprovedMLClaimAmtN`, `ApprovedDentalClaimAmtN`, `EligibilityN`, `TakenN`, `BalanceN`, `TotalN`, `LeaveSummaryHide`, `GroupIDN`, `LeaveDates`, `UniqueID`, `NoofTotalDays`, `MLClaimAmtN`, `NoofRestDay`, `LvAppliedDateD`, `NoofHoliDay`, `DeptCodeC`, `IsSplitupNeeded`, `ReasonNameC`, `IsEnableGroupLevelApprove`, `ApprovedUpto`, `PendingWith`, `DayUnitN`, `MatnIdN`, … (+20) |
| POST | `Rewards/RedeemReward` | `RedeemRewardResponse` | `IsSuccess`, `ResponseMsg`, `ResponseObject` {VoucherID, VoucherCode} |
| POST | `GetReleaseNotes` | `ReleaseNotesResponse` | `IsSuccess`, `ReleaseNotes` {ReleaseDate, App_ID, ReleaseId, IsRead, ReleaseTitle, AttachmentNameC, ShortDescC, ReleaseDetail, str, UserId, Yearn, ModuleName …} |
| POST | `ReleaseNotesDetail` | `ReleaseNotesDetailResponse` | `CustReleaseNotes` {ReleaseDate, ReleaseId, ReleaseTitle, AttachmentNameC, ShortDescC, str, UserId, IsLike, IsDisLike, IsComments, strCustomerGUID, IsStared}, `IsSuccess` |
| POST | `GetResDetail` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `Rewards/GetRewardDetails` | `RewardDetailsResponse` | `IsSuccess`, `ResponseMsg`, `ResponseObject` {RewardID, IndustryName, IndustryLogo, IndustryLogoByte, RewardTitle, Description, ExpiryDate, IsActive, IndustryTypeId, IndustryTypeName, CreatedBy, CreatedByStr …} |
| POST | `LeaveDraftApplywithParam_insert` | `SaveDraftResult` | `blnResult`, `strMessage` |
| POST | `GetScheduleData` | `ScheduleResult` | `DateWiseNotes` {BNID, ScheduleDate, ShiftGroupCodeC, Notes}, `ScheduleData` {GraceTimeN, EmpCodeC, EmpNameC, EmployeeShift, ShiftName, ScheduleDate, StartTime, EndTime, BranchName, BranchCode, ClockIn, ClockOut …} |
| GET | `GetServerUTCTime` | `ServerUTCTimeResponse` | `time` |
| GET | `GetAuthUrl` | `SingPassResponse` | `authorizationUrl` |
| POST | `GetLatenessDetail` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `GetTotalAppliedDays_BasedOnExcludeDay` | `ExcludeResult` | `ApplyLvTypC`, `PayTypeC`, `strMessage`, `Total` |
| POST | `GetTrainingCalendar` | `CalendarDataResponse` | `IsSuccess`, `trainingCalendarData` {CourseNameC, TrainingCodeC, CourseEndDateD, CourseStartDateD, DateTimeCourseStartTimeN, DateTimeCourseEndTimeN, InstitutionNameC}, `ErrMsg` |
| POST | `GetTrainingCertificate` | `CertificateDownloadResponse` | `IsSuccess`, `ErrMsg`, `certifacateDetails` {FileBytearray, FileName} |
| POST | `GetTrainingCustomCertificate` | `CertificateDownloadResponse` | `IsSuccess`, `ErrMsg`, `certifacateDetails` {FileBytearray, FileName} |
| POST | `GetTrainingDetails` | `TrainingListResponse` | `trainingDetails` {TrainingCodeC, CourseCodeC, CertificateFileNameC, CourseNameC, DateTimeCourseStartTimeN, AttendedResultC, CertificateExpiryDateD, CourseEndDateD, DateTimeCourseEndTimeN, CourseStartDateD, AttendedC, CertExpDate …}, `IsSuccess`, `ErrMsg` |
| POST | `GetUserProfile` | `ProfileResult` | `ClkInNotification`, `ClkInOutPriorAlertDuration`, `ClkOutNotification`, `ESCHISDEPT`, `IsAdvSCHNew`, `ShowFWAInLeave`, `AllowEmptoSelOtherDeptAprOfficerC`, `AllowPastLvCancel`, `APPRAISALISAPPROVER`, `EAPPRAISALSUPERADMIN`, `CLAIMISAPPROVER`, `ECLAIMSUPERADMIN`, `DeptCodeC`, `EHRISAPPROVER`, `EHRSUPERADMIN`, `CalcEarnLeaveALC`, `CalcEarnLeaveChildCareC`, `CalcEarnLeaveMLC`, `EnableForeignLanguage`, `ETMSSUPERADMIN`, `IsCalendarEnabled`, `IsDisplayCalFilterForApprover`, `IsLicenseValid`, `isShowFullSchedule`, `LEAVEISAPPROVER`, `ELEAVESUPERADMIN`, `MenuIdFlag`, `MOBILEATTSUPERADMIN`, `MOBILEATTISAPPROVER`, `PasswordLastModifiedDt`, `PAYROLLISAPPROVER`, `EPAYROLLSUPERADMIN`, `ProfilePhotoBase64`, `ESCHEDULESUPERADMIN`, `Sendwelcomemailc`, `ShowAIAttendance`, `ShowAIClaim`, `ShowAIHR`, `ShowAILeave`, `ShowAIPayroll`, … (+19) |
| POST | `GetWorkingDetail` | `TMSWorkingResult` | `ResultInfo`, `DashboardTable` {Table1, Table} |
| POST | `GetYTSDetail` | `TMSResult` | `ResultInfo`, `DashboardTable` {ReaCodeC, ShiftDetails, TimeInN, TimeOutN, ClockDateD, d_ClockDateD, EmpCodeC, EmpNameC, DeptCodeC, DeptNameC, LatenessN, UnderN …} |
| POST | `GetClaimEligibilityDetails` | `ClaimApplyBalanceRespones` | `isSucess`, `ResponseObject` {M_AmtUsed, M_Balance, M_LimitN, Y_AmtUsed, Y_Balance, Y_LimitN} |
| POST | `getemployeeattendancesummary` | `EtmsPiechartDataResponse` | `DashboardTable` {Total_employee_count, Total_working_employees, Total_employees_on_leave, Total_employees_absent, Total_employees_Late, ADMS_ATT_Process, Latest_ATT_Process, MOBILE_ATT_Process, Total_employees_on_RES_OFF_PH, Total_employees_YTS, Total_employees_EarlyOut}, `isSuccess` |
| POST | `JobCostAttendanceApproveReject` | `OTSubmitResponse` | `strMessage`, `blnResult` |
| POST | `ESCHLeaveApply` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `Logout` | `LogoutResponse` | `blnResult`, `strMessage` |
| POST | `ApprovalProcess` | `ApproveResponse` | `ErrorMsg`, `IsSuccess` |
| POST | `UpdateEmployeeDetails` | `EmployeeDetailUpdateResult` | `ErrorMsg`, `IsSuccess` |
| POST | `GetReasonListnew` | `ReasonResult` | `AL_MLC`, `CanDeleteC`, `DayUnitN`, `EligibleN`, `GroupCodeC`, `GroupNameC`, `IsAttachmentMandatory`, `LeaveC`, `PayTypeC`, `PayTypeNameC`, `permission` {mode, PermPerMonth, NewPermHoursPerDay, PermDate, AllowPermission, flag, EmpCodeC, NewPermPerMonth, PermHoursPerDay, NewAllowPermission}, `ReasonCodeC`, `ReasonNameC` |
| POST | `LeaveApply` | `ApplyResult` | `blnResult`, `strMessage` |
| POST | `LeaveApproveReject` | `LeaveApprovalResult` | `blnResult`, `IsQuotaExceed`, `strMessage` |
| POST | `CancelLeave` | `CancellationResult` | `blnResult`, `strMessage` |
| POST | `FileUploadOrRemove` | `RemarksResponse` | `blnResult`, `strMessage` |
| POST | `LeaveUpdate` | `RemarksResponse` | `blnResult`, `strMessage` |
| POST | `Login` | `LoginResult` | `AllowToApplyPreOT`, `CandidateId`, `IsSCHTMS`, `IsUserHasAccess`, `bNewApi`, `BannerURL`, `BannerActionURL`, `Block_License`, `IToken`, `IDNumber`, `Is2FAEnabled`, `IsFeedback`, `IsIMEIChanged`, `IsMobileAttendance`, `IsMobileRegistered`, `IsNewUI`, `IsShowReferAndEarn`, `LatestUpdatesavbl`, `Maintanence` {Active, FromDate, ToDate, fromTime, toTime}, `MobOS` {ANDROID}, `Notification`, `otherProfiles` {CompanyName, EmailID, EmpCode, EmpName, MobileNo}, `ReminderNotify`, `SecurepayslipIR8A`, `ShowAnnouncementPopup`, `strMessage`, `unReadAnnouncementsCount`, `UserAuthorization` {IsEscheduleApprover, IsEscheduleBackUpofficer, IsOnboardingCandidate, aI_Access, CompanyId, Customer, CustomerAppList, CustomerList, DobD, DojD, ECLAIM_BackUpOfficer, ELEAVE_BackUpOfficer …} |
| POST | `NotificationSetting` | `SettingsEnableDisableResponse` | `strMessage`, `blnStatus` |
| POST | `RegisterDevice` | `RegisterDeviceResult` | `blnStatus`, `strMessage` |
| POST | `SecurepayslipandIR8A` | `SettingsEnableDisableResponse` | `strMessage`, `blnStatus` |
| POST | `ReapplyClaim` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `RegisterCorporateTraining` | `RegisterCorporateTrainingResponse` | `IsSuccess`, `StrMessage` |
| POST | `CustomTraningRequest` | `CustomTrainingResponse` | `IsSuccess`, `StrMessage` |
| POST | `RegisterMobileDevice` | `RegisterMobileDeviceResponse` | `IsSuccess` |
| POST | `ReplyTicket` | `ReplyCloseTicketResponse` | `isSuccess`, `strMessage` |
| POST | `Rewards/GetIndustryList` | `RewardGetIndustryListResponse` | `IsSuccess`, `ResponseMsg`, `ResponseObject` {IndustryTypeID, IndustryTypeName, IsActive, Icon, IconByte, IconBlobUrl} |
| POST | `Rewards/GetRewards` | `RewardsListResponse` | `IsSuccess`, `ResponseMsg`, `ResponseObject` {RewardID, IndustryName, IndustryLogo, IndustryLogoByte, RewardTitle, Description, ExpiryDate, IsActive, IndustryTypeId, IndustryTypeName, CreatedBy, CreatedByStr …} |
| POST | `SaveCandidateDetails` | `SaveCandidateDetailResponse` | `ErrMsg`, `IsSuccess` |
| POST | `SaveCandidateFileDetails` | `SaveCandidateDetailResponse` | `ErrMsg`, `IsSuccess` |
| POST | `SaveClaimAsDraft` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `SaveReferandEarn` | `ReferEarnResponse` | `isSuccess`, `StatusMessage` |
| POST | `SaveScheduleDetails` | `SaveScheduleResponse` | `IsSuccess`, `Branch_List`, `Company_List` |
| POST | `UpdateRoomMeeting` | `UpdateSaveMeetingResponse` | `meetingBookingStatus` {status}, `IsSuccess`, `ErrMsg` |
| POST | `GetAvailableMeetingRooms` | `SearchMeetingRoomResponse` | `availableMeetingRooms` {RoomCodeC, RoomNameC, RoomDescriptionC}, `IsSuccess`, `ErrMsg` |
| POST | `SendOTPFor2FA` | `RegisterMobileDeviceResponse` | `IsSuccess` |
| POST | `SubmitAttendanceDetails` | `SubmitTrainingAttendanceResponse` | `IsSuccess`, `ErrMsg`, `FileUploadStatus` |
| POST | `GMTicketReopen` | `ReopenGrievanceResponse` | `IsSuccess` |
| POST | `UpdateCandidateStatus` | `SaveCandidateDetailResponse` | `ErrMsg`, `IsSuccess` |
| POST | `UpdateClaimAsDraft` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `UpdateLetterViewStatus` | `LetterHeadStatusResponse` | `LetterId`, `companyid`, `empcode`, `strCustomerGUID`, `userId`, `ViewStatus` |
| POST | `UpdateLikeStatus` | `UpdateLikeStatusResponse` | `CustReleaseNotes` {ReleaseDate, ReleaseId, ReleaseTitle, AttachmentNameC, ShortDescC, str, UserId, IsLike, IsDisLike, IsComments, strCustomerGUID, IsStared}, `IsSuccess` |
| POST | `UpdateGeo` | `LocationResponse` | `retflag`, `retmessage` |
| POST | `UpdateMemoViewStatus` | `MemoStatusResponse` | `EHRUserCodeC`, `EMemoTemplateIdN`, `intApplicationType`, `MemoCreateDateD`, `MemoCreatedID`, `strUserCode`, `ViewStatus` |
| POST | `UpdateStartedNotes` | `UpdateStartedNotesResponse` | `CustReleaseNotes` {ReleaseDate, ReleaseId, ReleaseTitle, AttachmentNameC, ShortDescC, str, UserId, IsLike, IsDisLike, IsComments, strCustomerGUID, IsStared}, `IsSuccess` |
| POST | `UpdateUserPolicyViewStatus` | `PolicyStatusRequest` | `iCompanyID`, `iUserId`, `strCompanyCode`, `strCustomerGUIID`, `strFileName` |
| POST | `SaveWifiBSSID` | `WifiResponseModel` | `IsSuccess`, `StatusMessage` |
| POST | `ECFileUploadOrRemove` | `ClaimSummaryResponse` | `blnResult`, `ECClaimTemplateSummary` {CancelC, CanReapply, ClaimApprovedAmtN, ClaimName, ClaimTemplateName, ClaimableAmount, EmpCodeC, IdN, M_AmtUsed, M_Balance, M_LimitN, TemplateGroupIdN …}, `ECClaimTranDetail` {ApprovedGSTAmount, ApprovedSSTAmount, DepartmentName, Paydetails, PendingWithDisplayname, SectionName, ShortName, AdditionalFees, AllownNameC, Amount, AppliedCostCenterCode, ApprovedDateC …}, `strMessage` |
| POST | `UploadDocAttachment` | `ApproveResponse` | `ErrorMsg`, `IsSuccess` |
| POST | `Profile/SaveProfilePhoto` | `ProfileImageResponse` | `blnResult`, `strMessage` |

### AttendanceApi

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `ActivateOTP` | `ActivateOTPResult` | `URL`, `Result` |
| POST | `RegisterPhoto` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `VerifyFace` | `FaceVerifyResult` | `AllowChangeEmployeeIDNumber`, `ChineseMessage`, `EmpCode`, `EmployeeName`, `FaceMatchPercentage`, `IDNumber`, `TTToken`, `IsTemperatureRequired`, `matched_Data` {AllowChangeEmployeeIDNumber, ChineseMessage, EmpCode, EmployeeName, FaceMatchPercentage, IDNumber, TTToken, IsTemperatureRequired, matched_Data, MobileJobsRequired, NotifyMetersC, RemarksRequired …}, `MobileJobsRequired`, `NotifyMetersC`, `RemarksRequired`, `SelfieRequired` |
| POST | `RegisterPhotoV1` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `VerifyFaceV1` | `FaceVerifyResult` | `AllowChangeEmployeeIDNumber`, `ChineseMessage`, `EmpCode`, `EmployeeName`, `FaceMatchPercentage`, `IDNumber`, `TTToken`, `IsTemperatureRequired`, `matched_Data` {AllowChangeEmployeeIDNumber, ChineseMessage, EmpCode, EmployeeName, FaceMatchPercentage, IDNumber, TTToken, IsTemperatureRequired, matched_Data, MobileJobsRequired, NotifyMetersC, RemarksRequired …}, `MobileJobsRequired`, `NotifyMetersC`, `RemarksRequired`, `SelfieRequired` |
| POST | `EmpCP/EmpChangePassword` | `AttendanceForgotPasswordResult` | `StatusCode`, `StatusMessage` |
| POST | `UploadPhotoWithVerify` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `UploadPhotoWithObj` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `UploadPhotoWithVerifyV1` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `timelog` | `ClockResponse` | `isSafeEntryFailure`, `isSuccess` |
| POST | `LoginTrackIMEI` | `AttendanceLoginResult` | `CompanyId`, `CustomerID`, `EmployeeName`, `GUID`, `IDNumber`, `isIMEIChanged`, `UserId` |
| POST | `DownloadPhotoV1` | `FaceRegisterRequest` | `FaceUniqueID`, `CustomerID`, `FaceImages`, `FaceTemplates`, `FileAttachBase64`, `isInfoFace`, `isRegister`, `language`, `NRICNo`, `SuperEmpCode`, `UserId`, `VerifyType` |
| POST | `Employeeprofileimage` | `EmployeeProfileImageResponse` | `EmpCodeC`, `EmpNameC`, `StaffImage` |
| POST | `FP` | `AttendanceForgotPasswordResult` | `StatusCode`, `StatusMessage` |
| POST | `EmpJobMaster/EmpGetJobs` | `JobResult` | `EndLattitude`, `EndLongitude`, `IsJobAssigned`, `IsRoadGeofencingEnabled`, `JobCodeC`, `JobNameC`, `latn`, `lngn`, `LocationNameC`, `SafeEntryURL`, `StartLattitude`, `StartLongitude` |
| POST | `EmpJobMaster/EmpGetSearchJobs` | `JobResult` | `EndLattitude`, `EndLongitude`, `IsJobAssigned`, `IsRoadGeofencingEnabled`, `JobCodeC`, `JobNameC`, `latn`, `lngn`, `LocationNameC`, `SafeEntryURL`, `StartLattitude`, `StartLongitude` |
| POST | `RegisterTTToken` | `TTRegisterTokenResult` | `EnglishMsg`, `ChineseMsg`, `isSuccess`, `isSafeEntryFailure` |
| POST | `GetMobileTrackingDetailsV1` | `TrackingProfileResult` | `TrackingEndTime`, `MobileTrackerSchedule` {FridayEndTime, FridayStartTime, MondayEndTime, MondayStartTime, SaturdayEndTime, SaturdayStartTime, ScheduleCode, ScheduleId, ScheduleName, SundayEndTime, SundayStartTime, ThursdayEndTime …}, `TrackingBasedOn`, `TrackingRequired` |
| GET | `MobileTracker/GetMobileTrackingDetails` | `TrackingProfileResult` | `TrackingEndTime`, `MobileTrackerSchedule` {FridayEndTime, FridayStartTime, MondayEndTime, MondayStartTime, SaturdayEndTime, SaturdayStartTime, ScheduleCode, ScheduleId, ScheduleName, SundayEndTime, SundayStartTime, ThursdayEndTime …}, `TrackingBasedOn`, `TrackingRequired` |
| POST | `validateemployeechange` | `AttendanceProfileResult` | `IsSafeEntryAutoCheckInOut`, `IsShowAllJobsEnabled`, `IsShowOnlyAssignedEmployees`, `IsMoAttAccess`, `AllowChangeEmployeeIDNumber`, `AllowedSSID`, `IsTraceTogetherCheck`, `TTToken`, `EmployeeName`, `FirstClockIn`, `FirstClockLat`, `FirstClockLng`, `IDNumber`, `IsTemperatureRequired`, `IsWIFIRestricted`, `MobileJobsRequired`, `NotifyMetersC`, `RemarksRequired`, `SelfieRequired`, `ServerTime`, `StaffImage`, `Latitude`, `Longitude` |
| POST | `PushNotificationTimeLogHistory` | `WorkFromHomeHistoryResult` | `EmpCodeC`, `EmpNameC`, `ClockTimeD`, `Remarks`, `WFHDistance` |
| POST | `PushNotificationTimeLogOffline` | `OutOfLocationResponse` | `englishMsg`, `chineseMsg`, `isSuccess` |
| POST | `PushNotificationTimeLogOnline` | `OutOfLocationResponse` | `englishMsg`, `chineseMsg`, `isSuccess` |
| POST | `PushNotificationTimeLog` | `OutOfLocationResponse` | `englishMsg`, `chineseMsg`, `isSuccess` |
| POST | `AttendanceHistory/GetAttedanceHistory` | `AttendanceHistoryResult` | `ClockTimeD`, `empnameC`, `ImagePathC`, `LocationNameC`, `Remarks` |
| POST | `SaveMobileTrackerV1` | `raw (Call<String>)` | — |
| POST | `SaveFeedback` | `FeedackResponse` | `a`, `b` |
| POST | `SaveMobileTrackingStatus` | `raw (Call<String>)` | — |
| POST | `SearchEmployeeV1?` | `SearchEmployeeResult` | `TTToken`, `CardNoC`, `CustomerCode`, `EmpCodeC`, `EmployeeName`, `isImageAvailablle`, `PersistedFaceID` |
| POST | `SearchEmployee?` | `SearchEmployeeResult` | `TTToken`, `CardNoC`, `CustomerCode`, `EmpCodeC`, `EmployeeName`, `isImageAvailablle`, `PersistedFaceID` |

### FieldTrackingAPI

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `AddFeedback` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `DeleteChatHistory` | `RecentChatResponse` | `isSuccess`, `requestId`, `responseObject` {hasMore, pageNumber, pageSize, totalCount} |
| POST | `ModuleSwitch/token` | `FetchExchangeTokenResp` | `exchangeToken` |
| POST | `GetAllScheduleDataList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `SCHEmpOTRequestApproveReject` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetAssginScheduleList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetAutoOTCalculation` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetBlobFiles` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `Onboarding/GetBlobFiles` | `BlobDocumentsResponseNew` | `isSuccess`, `responseObject` {documents, botReply} |
| POST | `GetBotReply` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `GetBranchDataList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `SCHCancelRequestApply` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetChangeWithEmpList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetChatHistory` | `RecentChatResponse` | `isSuccess`, `requestId`, `responseObject` {hasMore, pageNumber, pageSize, totalCount} |
| POST | `GetChatResponse` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `FWA/ApplyFWA` | `FWAHistoryResponse` | `clsFWAHistroy` {FWARequestId, EmpCodeC, FWAType, FWAGroupCode, FWAGroupName, FWAGroupOption, FromDateD, ToDateD, Days, NoOfDays, Reason, Remarks …}, `strMessage`, `IsSuccess` |
| POST | `FWA/ApproveRejectFWA` | `FWAApproveRejectResponse` | `clsFWAHistroy`, `strMessage`, `IsSuccess` |
| POST | `FWA/CancelFWA` | `FWACancelResponse` | `FWARequestId`, `EmployeeComments`, `clsFWAHistroy`, `strMessage`, `IsSuccess` |
| POST | `FWA/GetEmployeeFWAHistory` | `FWAHistoryResponse` | `clsFWAHistroy` {FWARequestId, EmpCodeC, FWAType, FWAGroupCode, FWAGroupName, FWAGroupOption, FromDateD, ToDateD, Days, NoOfDays, Reason, Remarks …}, `strMessage`, `IsSuccess` |
| POST | `FWA/GetEmployeeFWALimit` | `FWALimitResponse` | `EmpCodeC`, `Sunday`, `Monday`, `Tuesday`, `Wednesday`, `Thursday`, `Friday`, `Saturday`, `WeeklyLimit`, `MonthlyLimit`, `WeeklyTaken`, `MonthlyTaken`, `AvailableWeeklyBalance`, `AvailableMonthlyBalance`, `EmployeeType`, `WorkHoursPerDay` |
| POST | `FWA/FWASettingsforToday` | `raw (Boolean)` | — |
| POST | `FWA/GetEmployeeFWASummary` | `FWASummaryResponse` | `FWAGroupName`, `FWAGroupOption`, `FromDateD`, `ToDateD`, `Reason`, `Days`, `NoOfDays`, `StatusUpdatedOn`, `FPType`, `CompanyNameC`, `FWARequestId`, `EmpCodeC`, `EmpNameC`, `EmpShortNameC`, `ReaCodeC`, `ReaNameC`, `DeptCodeC`, `DeptNameC`, `SecCodeC`, `SecNameC`, `PayTypeC`, `LeaveDateD`, `CalendarDateD`, `HolidayNameC`, `LeaveStatus`, `ForeignLangLeaveStatusC`, `ForeignLangReasonNameC`, `ForeignLang2ReasonNameC`, `IsBlocked`, `IsHeader`, `IsShowDetails`, `S1CodeC`, `S2CodeC`, `HRCodeC`, `FWAType`, `FWAGroupCodeC` |
| POST | `FWA/GetEmployeeFWASummary` | `FWASummaryMonthResponse` | `FWADate`, `IsHoliday`, `FWACount`, `IsBlocked` |
| POST | `FWA/UpdateFWA` | `FWAUpdateResponse` | `clsFWAHistroy`, `strMessage`, `IsSuccess` |
| POST | `FWA/GetEmployeeFWAforApprover` | `FWAforApproverResponse` | `ApproverId`, `clsFWAHistroy` {AppliedDate, ApproverComments, EmployeeComments, StartTime, EndTime, TotalHours, FPType, RespondBy, isOverDue, FWARequestId, EmpCodeC, EmpNameC …}, `strMessage` |
| POST | `GetEmpAvailabilityData` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `Onboarding/GetBotReply` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `GetEmpPendingRequestList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetEmpPendingRequestDetails` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `ETMSPreOTApproveReject` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `EmpPreOTRequestApply` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetRequestAccessList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `SCHEmpOTRequestApply` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetSCHRequestHistory` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetSCHRequestHistoryDetails` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `GetRequestScheduleList` | `ResponseModel` | `AllScheduleDataGrabList` {AttendanceRequestList, BranchCodeC, BranchNameC, BreakIn, BreakOut, ChangeScheduleRequestList, ClaimStatus, ClockIn, ClockOut, ColorFlag, DateWiseNotes, EmpNameC …}, `AssignedSessionList` {ActualClockIn, ActualClockOut, ActualOT, SessionId, SessionName, SessionTime}, `BranchDataList` {ShiftGroupCodeC, ShiftGroupNameC}, `ChangeWithEmpList` {EmpCode, EmpName}, `DateWiseNotes` {BNID, Notes, ScheduleDate, ShiftGroupCodeC}, `EmpAvailabilityShiftData` {AvailableID, DateD, EmpcodeC, FrmTime_1, FrmTime_2, FrmTime_3, IsMultipleDays, IsUnavailable_1, IsUnavailable_2, IsUnavailable_3, SchDays, SelectedDays …}, `Isstatus`, `LeaveList` {DayStatus, LeaveDate, ReasonCode, ReasonName, Remarks, Status}, `Message`, `PendingRequestDetails` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualOT, ActualSchBreakMinutes, ActualTimeOut, Actualtimein, AttachmentName, Branch, ChangeSchedule, CurrentSchedule …}, `ReqHistoryDetail` {ActualOT, ActualTimeOut, Actualtimein, ApprovedLevel, ApproverRemarks, Branch, ChangeSchedule, ChangeWith, CurrentSchedule, EmpCancelRemarks, EmpCode, EmpName …}, `RequestClaimList` {ActschBreakEndTime, ActschBreakStartTime, ActualBreakIN, ActualBreakOut, ActualSchBreakMinutes, AppliedDate, ApprovedLevel, Approver1Remark, Approver2Remark, Approver3Remark, AttachmentName, BranchName …}, `SchAccesslistDetails` {AccessRight, TypeDesc, TypeId}, `SchTotalOTList` {OT}, `ScheduleList` {SessionId, SessionName, SessionTime}, `blnResult`, `listPreOTRequest` {ApprovedDate, ApprovedUpto, ApproverRemarks, ClaimRejectedOn, ClaimStatus, ClockDateD, DayC, EmpCodeC, EmpNameC, FirstLevelApproverUserId, IsApproved, IsCancelInitiated …}, `strMessage` |
| POST | `Onboarding/DeleteChatHistory` | `RecentChatResponse` | `isSuccess`, `requestId`, `responseObject` {hasMore, pageNumber, pageSize, totalCount} |
| POST | `Onboarding/GetChatHistory` | `RecentChatResponse` | `isSuccess`, `requestId`, `responseObject` {hasMore, pageNumber, pageSize, totalCount} |
| POST | `Onboarding/GetChatResponse` | `BlobFilesResponse` | `isSuccess`, `responseObject`, `requestId`, `responseType` |
| POST | `UpdateTransaction` | `ApiBasicSuccessResponse` | `blnResult` |

### EScheduleApiV1

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `ShiftSchedule/AddShift` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| POST | `MySchedule/ApplyChangeRequest` | `ApplyChangeRequestResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `ApproveReject/ApproveRejectRequest` | `ApproveRejectResponseV1` | `blnResult`, `strMessage` |
| POST | `ShiftSchedule/AddShift` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| POST | `ShiftSchedule/AddShift` | `BookGrabScheduleResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `ShiftSchedule/AddShift` | `BookGrabScheduleResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `MySchedule/GetAutoOTcalculationValue` | `FetchAutoOTCalculationResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `CopySchedule/CopyShift` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| POST | `ShiftSchedule/CreateAddShift` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| GET | `Allowance` | `FetchShiftAllowanceListResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `ShiftSchedule/GetEmployeeWiseShiftMobile` | `FetchAssignedShiftRespV1` | `isSuccess`, `strMessage` |
| GET | `AttendanceSchedule/FetchAttendanceSchedule/{date}` | `FetchAttendanceDetail` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `AttendanceSchedule/FetchAttendanceHistory` | `FetchAttendanceHistoryV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `Branch` | `FetchBranchListRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `Branch/GetBranchesForUnplannedShift` | `FetchBranchListRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `Availability/FetchDailyAvailability/{EmployeeCode}` | `FetchDailyAvailabilityResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `schedule/DisplaySettings` | `FetchDisplaySettingsResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `Employees/EmployeeList` | `FetchEmpListV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `ShiftSchedule/GetEmployeeShiftMobile` | `FetchEmpShiftRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `totalPages`, `validationErrors` |
| GET | `ShiftSchedule/GetEventsForBranch` | `FetchEventsResponse` | `isSuccess`, `successMessage` |
| GET | `Transaction/FetchTransactionFilters` | `FetchFilterValuesRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `GrabSchedule/GetDateWiseGrab` | `FetchGrabDateListResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `GrabSchedule/GetGrabHistory` | `FetchGrabV2Response` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `GrabSchedule/FetchGrabSchedule` | `FetchGrabV2Response` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `GrabSchedule/FetchGrabSchedule` | `FetchGrabScheduleListResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `ShiftPlan/GetRoleListMobile` | `FetchMasterRoleListResp` | `isSuccess`, `strMessage` |
| GET | `ShiftPlan/GetShiftPlanMobile` | `FetchMasterPlanResponse` | `isSuccess`, `strMessage` |
| GET | `MySchedule/GetSCHRequestHistory` | `FetchEmpRequestHistoryRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `MySchedule/GetMyScheduleData` | `FetchMyScheduleResponse` | `dateWiseEventList` {date, eventid, name}, `errorMessage`, `isSuccess`, `leaveInfoList` {dayStatus, leaveDate, reasonCode, reasonName, remarks}, `successMessage`, `validationErrors` |
| GET | `ApproveReject/WaitingForApprovels` | `FetchPendingReqListRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `ShiftSchedule/GetReasonListMobile` | `FetchLeaveReasonListRespV1` | `isSuccess`, `strMessage` |
| GET | `MySchedule/GetEmpNotificationShifts` | `FetchRemainderShiftResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `MySchedule/GetChangeScheduleList` | `FetchRequestScheduleList` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `ShiftSchedule/GetRolesListCount` | `FetchMasterRoleListResp` | `isSuccess`, `strMessage` |
| POST | `Transaction/FetchTransactions` | `FetchTransactionListRespV1` | `blnResult`, `dateDetaillst` {absentCount, actualSalesAmnt, date, etmsAllowLock, etmsAllowUnLock, laborCost, overAllLock, requestCount, targetSalesAmnt, totalHours, totalLateness, totalOTHours …}, `empDetailslst` {designationName, employeeCode, employeeName, employementType, ReasonCodeC, transactionsList}, `strMessage`, `totalPages` |
| GET | `UnplannedShift/FetchUnplannedShift_V2` | `UnplannedShiftReqListResponse` | `blnResult`, `strMessage` |
| GET | `ShiftPlan/GetFullShiftPlanMobile` | `FetchViewShiftPlanResp` | `isSuccess`, `strMessage` |
| GET | `Availability/fetchWeeklyAvailability` | `FetchWeeklyAvailabilityResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `Availability/InsertUpdateDailyAvailability` | `InsertUpdateAvailabilityResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `Availability/InsertUpdateWeeklyAvailability` | `InsertUpdateAvailabilityResp` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `PublishSchedule/InvalidPublishEmployeesExcel` | `raw (ResponseBody)` | — |
| PUT | `ShiftSchedule/DayStatus` | `AddReasonSuccessResponse` | `blnResult`, `message` |
| PUT | `Transaction/UpdateLock` | `LockStatusUpdateRespV1` | `isSuccess`, `OverAllLock`, `successMessage` |
| POST | `AttendanceSchedule/TimeLog` | `ClockingResponseV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `UnplannedShift/UnplannedShiftApproveReject_V2` | `UnplannedActionResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `GrabSchedule/SendGrabRequest` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| POST | `PublishSchedule/PublishMobile` | `PublishResponse` | `isSuccess` |
| DELETE | `ShiftSchedule/RemoveShift` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| DELETE | `PublishSchedule/UnPublishMobile` | `PublishResponse` | `isSuccess` |
| POST | `SalesMatrix/AddUpdateSalesMatrix` | `ActualSalesUpdateRespV1` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| PUT | `ShiftSchedule/EmployeeWiseShiftUpdate` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| PUT | `Transaction/UpdateTransactionMobile` | `AutoScheduleApiBasicResponse` | `errorMessage`, `validationErrors` |
| POST | `PublishSchedule/ValidatePublish` | `ValidatePublishResponse` | `errorCode`, `errorMessage`, `errorType`, `isSuccess`, `successMessage`, `validationErrors`, `validations` {publishValidations} |
| POST | `MySchedule/ViewAttachment` | `raw (Call<ResponseBody>)` | — |

### EScheduleNewFlowApi

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `AdvSCH/AddOtherBranchStaff` | `AddOtherBranchStaffResp` | `blnResult`, `DayPublishList`, `EmpWeeklyOTHoursList`, `Isautopublish`, `ManpowerList`, `publishedDate`, `SessionList`, `strMessage` |
| POST | `AdvSCH/{endPoint}` | `raw (ResponseBody)` | — |
| POST | `AdvSCH/EmpSessionMapping` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/CopySchedule` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/ManageSession` | `CreateShiftResponse` | `blnResult`, `EditSessionList`, `SessionList`, `strMessage` |
| POST | `AdvSCH/CreateAndMappingEmpSession` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/ReturnHTMLTemplate` | `raw (ResponseBody)` | — |
| POST | `AdvSCH/GetBranchList` | `BranchListResponse` | `BranchDataList` {App_ID, Company_ID, geofencingMeters, latitude, longitude, ShiftGroupCodeC, shiftGroupDetailsList, ShiftGroupNameC, UserAppRightList, UserIdN} |
| POST | `AdvSCH/GetEmpAssignedSessionDetails` | `EmployeeShiftListResponse` | `TotalPages`, `blnResult`, `DayPublishList` {DateD, IsPublished}, `EmpWeeklyOTHoursList`, `Isautopublish`, `ManpowerList`, `publishedDate`, `SessionList` {MappedRoles, TotalLaborRate, TotalSessionHrs, TotalWeeklyOThrs, DailyWorkHours, DayInfoList, Days, Designation, EligWorkHours, EmpBranchName, EmpCode, EmpName …}, `strMessage` |
| POST | `AdvSch/GetTransactionFilterVals` | `FilterDataListResponse` | `AttendanceStatuslist` {code}, `blnResult`, `Designationlist` {count, desigCodeC, desigNameC, isSelected}, `Emplist` {empCode, empName, isSelected}, `Shiftlist` {Shift_Code, Shift_Name}, `strMessage` |
| POST | `Schedule/GetEmpShiftAvailabilityMaster` | `MasterAvailabilityResp` | `blnResult`, `EmpShiftAvailabilityMasterDetails` {Companyid, UserId, EmpCode, FromTime_1, FromTime_2, FromTime_3, SchDays, ToTime_1, ToTime_2, ToTime_3}, `strMessage` |
| POST | `AdvSCH/ReasonList` | `LeaveReasonListResponse` | `ReasonList` {ESCHAllowAppLeave, AL_MLC, CanDeleteC, CompanyID, CustomerCodeC, DateCreatedD, DateUpdatedD, LeaveC, LvEligibileN, PayTypeC, ReasonCodeC, ReasonNameC …} |
| POST | `AdvSCH/GetRoleList` | `RollListResponse` | `RolesList` {Count, Id, Name} |
| POST | `AdvSCH/GetRoleDetails` | `ShiftBasedRoleListResponse` | `blnResult`, `RoleList` {ColorC, Name, RoleCount, RoleId, RoleName, SessionId}, `strMessage` |
| POST | `AdvSCH/GetSessionDetails` | `ShiftListResponse` | `blnResult`, `EditSessionList`, `SessionList` {IsEditable, RolesList, ColorC, Days, Name, Rolescnt}, `strMessage` |
| POST | `AdvSCH/GetStaffList` | `StaffListResponse` | `ShiftAllowance`, `StaffList` {DesigGroupCodeC, EmpCodeC, EmpNameC, OpenSlot} |
| POST | `AdvSCH/GetTransactionDetails` | `TransactionListResp` | `blnResult`, `DateDetaillst` {ETMSAllowLock, ETMSAllowUnLock, OverAllLock, absentCount, ActualSalesAmnt, DateD, LaborCost, RequestCount, TargetSalesAmnt, TotalHours, TotalLateness, TotalOTHours …}, `empDetailslst` {designationName, employeeCode, employeeName, employementType, ReasonCodeC, transactionsList}, `EmpDetailslst` {EmpCode, EmpLabourCost, EmpName, EmpOTHours, EmpPlannedHours, EmpSessionDetaillst, EmpWorkedHours, IsLocked, ModifyColumn, ReasonCodeC}, `strMessage`, `totalPages` |
| POST | `Schedule/GetEScheduleData` | `ScheduleResult` | `DateWiseNotes` {BNID, ScheduleDate, ShiftGroupCodeC, Notes}, `ScheduleData` {GraceTimeN, EmpCodeC, EmpNameC, EmployeeShift, ShiftName, ScheduleDate, StartTime, EndTime, BranchName, BranchCode, ClockIn, ClockOut …} |
| POST | `AdvSCH/AddReason` | `AddReasonSuccessResponse` | `blnResult`, `message` |
| POST | `AdvSCH/LockTransactions` | `LockStatusUpdateResponse` | `OverAllLock`, `result` |
| POST | `AdvSCH/GetCommentsList` | `NotesResponse` | `blnResult`, `CommentsList` {BranchId, Comments, CompanyId, EmpCodeC, Mode, Module, NotesID, ScheduleDate, UserId}, `strMessage` |
| POST | `Schedule/GetAttedanceHistory` | `AttendanceHistoryResult` | `ClockTimeD`, `empnameC`, `ImagePathC`, `LocationNameC`, `Remarks` |
| POST | `AdvSCH/GrabSchedule` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/PublishedDateForWeek` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/RemoveSession` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `Schedule/GetScheduleOfTheDay` | `ScheduleDataResponse` | `IsSuccess`, `AllScheduleDataGrabList`, `PunchActionList`, `clockIN`, `clockOUT`, `ScheduleId`, `BranchCodeC`, `BranchNameC`, `EmpNameC`, `ClockIn`, `ClockOut`, `TotClckHrs`, `LeaveNameC`, `SessionNameC`, `TotHrs`, `SessionDate`, `ColorFlag` |
| POST | `AdvSch/AddUpdateSalesMatrix` | `ActualSalesUpdateResponse` | `blnResult`, `strMessage`, `UpdatedVal` |
| POST | `AdvSCH/ManageSession` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `Schedule/AddEmpShiftAvailabilityMaster` | `ApiBasicSuccessResponse` | `blnResult` |
| POST | `AdvSCH/GetEmpEditSessionDetails` | `ShiftDetailResponse` | `blnResult`, `SessionList` {RoleId, BranchName, BreakFrom, breakMinutes, BreakTo, Color, Date, EmpRoleCount, From, hasBreakInOut, Id, IsOtherBranchSession …}, `strMessage` |

### SSOApiService

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `LoginRequestApprRej` | `LoginApproveRejectResponse` | `IsSuccess`, `twoFATransaction` |
| POST | `ActivateNewProfilebyOTP` | `SSOProfileByOTPResponse` | `UserID`, `ActiveValue`, `InactiveValue`, `ActiveOTP`, `InactiveOTP`, `ResponseMsg`, `IsSuccess`, `UpdatedDate` |
| POST | `ChangePassword` | `SSOPasswordResult` | `AppName`, `ConnectionStr`, `CustomerCode`, `Email`, `HomePageURL`, `IsDefault`, `IsSuccess`, `MobileNo`, `ShortenURL`, `StatusMsg`, `URLExpiryTime` |
| POST | `CheckFor2FALoginRequest` | `Login2FAStatusResponse` | `IsSuccess`, `IDN`, `StatusType`, `twoFATransaction` {BrowserName, EmailId, IDN, TransactionGUID, CreatedDateTime, TransactionID} |
| POST | `DeviceToken` | `DeviceTokenResponse` | `IsSuccess` |
| POST | `ForgotPassword` | `SSOPasswordResult` | `AppName`, `ConnectionStr`, `CustomerCode`, `Email`, `HomePageURL`, `IsDefault`, `IsSuccess`, `MobileNo`, `ShortenURL`, `StatusMsg`, `URLExpiryTime` |
| POST | `GenerateAndValidateOTP` | `GenerateOTPResponse` | `Result`, `URL` |
| POST | `Authenticate` | `SSOLoginResponse` | `IsSuccess`, `StatusMsg`, `Token`, `ExpiryTime`, `AppList` {APPID, IsInternal, AppName, IsDefault, UA_ID, IDN, Email, MobileNo, CustomerCode, MobileNoISO}, `Is2FAEnabled`, `IsMobileRegistered`, `Email`, `InActiveAppList` {APPID, IsInternal, AppName, IsDefault, UA_ID, IDN, Email, MobileNo, CustomerCode, MobileNoISO}, `MobileNo`, `ShowChangePassword` |
| POST | `Logout` | `SSOLogoutResponse` | `MobileNo`, `IsSuccess`, `StatusMsg`, `Email`, `IsOtherMobileRegistered`, `IsMobileRegistered`, `Is2FAEnabled`, `StatusObject`, `Token`, `ExpiryTime`, `AppList`, `ShowChangePassword` |
| POST | `RegisterDevice` | `SSORegisterDeviceResponse` | `Date`, `isRegistered`, `IsSuccess`, `StatusMsg` |
| POST | `RegisterMobileDevice` | `RegisterMobileDeviceResponse` | `IsSuccess` |
| POST | `RejectLoginApproval` | `SSOActivateProfileResponse` | `Date`, `Email`, `isRegistered`, `IsSuccess`, `Mobile`, `StatusMsg` |
| POST | `SendOTPFor2FA` | `RegisterMobileDeviceResponse` | `IsSuccess` |

### AutoScheduleTokenApi

| Method | Path | Response model | Response fields |
|---|---|---|---|
| POST | `Auth/login` | `AutoScheduleTokenResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| GET | `Auth/module-entry/mobile` | `AutoScheduleTokenResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `Auth/module-entry/sso/infotech` | `AutoScheduleTokenResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |
| POST | `Auth/refresh` | `AutoScheduleTokenResponse` | `errorMessage`, `isSuccess`, `successMessage`, `validationErrors` |

---

## Change log

| Rev | Changes |
|---|---|
| 1 | Initial static analysis: overview, features, architecture, main flows, storage, security, endpoint list (Appendix A) |
| 2 | §8 roles, permissions and admin logic |
| 3 | §9.1–9.18 module-by-module detail; corrected §4.2 (the phone *does* run geofence, Wi-Fi and moving-device checks) |
| 4 | §9.19–9.24 (voice assistant, project costing, legacy schedule, training, release notes, push routing); §10 native libraries, SDKs, permissions, SSO framework; findings #10–14 |
| 5 | §11 remaining endpoints; §12 large classes; §13 layouts and translations; §14 assets; §15 face and liveness engines; Appendix B response models; findings #15–20 |
| 6 | §16 web portal (passive); findings #21–23; executive summary, contents, refreshed header, scope and at-a-glance table |
