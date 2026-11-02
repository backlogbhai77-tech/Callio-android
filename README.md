
# Callio Native Android 1.0 — Starter Build

A separate Kotlin + Jetpack Compose Android app for the existing Callio Firebase project. The existing Next.js PWA is not modified by this project. The Android app reuses the same Firebase Authentication users and Firestore collections.

## Current starter features

- Existing Firebase email/password login and account creation
- Existing `users/{uid}` profile documents
- Existing username lookup documents (`usernames/{username}`)
- Existing friend requests and accepted friendships
- Audio-only WebRTC calling using the existing `calls/{callId}` document shape
- ICE candidates in `callerCandidates` and `calleeCandidates`
- Native Firebase Cloud Messaging token registration in `users/{uid}/devices/{token}`
- Android incoming-call notification using Android `CallStyle` on Android 12+
- Incoming-call full-screen activity intent and Accept/Decline actions
- Custom ringtone resource and vibration channel
- Microphone foreground service while a call is active
- Premium dark violet Compose UI
- GitHub Actions workflow to build a debug APK in the cloud

## Important status

This is the first native implementation milestone, not a production-certified release. A full Gradle build and two-device live call test have not been run in this environment. TURN credentials are optional in the code but strongly recommended for calls across mobile networks/different Wi-Fi networks. A native Android Telecom `ConnectionService` integration and Google sign-in are follow-up milestones; the current app uses Android CallStyle notifications and its own full-screen call activity.

## Package name

`com.callio.app`

## Setup — phone-only workflow

1. In Firebase Console, open the **same Firebase project** used by the Callio website.
2. Add an Android app with package name `com.callio.app`.
3. Download `google-services.json` and place it at `app/google-services.json` in this project. Do not create a second Firebase project.
4. In `gradle.properties`, replace `https://YOUR-CALLIO-VERCEL-DOMAIN.vercel.app` with the real URL of the existing Callio Next.js deployment. Keep the `https://` prefix.
5. In the existing Next.js repository, replace `app/api/calls/notify/route.js` with the version in `backend-patch/app/api/calls/notify/route.js`, then commit and redeploy Vercel. This adds high-priority Android FCM delivery and the caller name to the push data.
6. Confirm the existing Vercel Firebase Admin environment variables are still configured. Never put the Firebase Admin private key in this Android project.
7. In GitHub, upload this project to a new repository (recommended name: `callio-native-android`). Keep the folder paths exactly as shown.
8. Add `app/google-services.json` to the repository. Firebase's Android client config is not an Admin secret; Firestore rules and Authentication protect the data.
9. Push to the `main` branch or manually run **Actions → Build Callio Android APK → Run workflow**.
10. When the workflow finishes, open its run, download the `Callio-Android-debug-APK` artifact, extract it, and install `app-debug.apk` on both test phones.

## Firebase configuration

- Enable Email/Password in Firebase Authentication (already used by the website).
- Use the same Firestore database. No collection migration or reset is required.
- Keep the existing Firestore rules. Merge the device-token rule from the existing `FIRESTORE_PHASE4_RULES.txt` if it is not already present:

```text
match /users/{userId}/devices/{deviceId} {
  allow read: if request.auth != null && request.auth.uid == userId;
  allow create, update: if request.auth != null
    && request.auth.uid == userId
    && request.resource.data.keys().hasOnly(['token', 'platform', 'createdAt', 'updatedAt'])
    && request.resource.data.token is string
    && request.resource.data.token.size() > 20;
  allow delete: if request.auth != null && request.auth.uid == userId;
}
```

Do not replace all Firestore rules with this snippet. Merge it inside the existing `match /databases/{database}/documents` block and preserve all other rules.

## Incoming call permissions on Android 12

On each phone:

- Allow microphone permission when prompted.
- Allow Callio notifications.
- Allow lock-screen notifications.
- Set battery usage to Unrestricted / Allow background activity if the phone manufacturer offers this setting.
- Do not force-stop Callio if you expect calls. Android cannot deliver calls after a user force-stops an app until it is opened again.

For Android 14+ devices, Android may require the user to allow full-screen notifications for Callio in Special app access. Android 12 is the first target test device here.

## TURN server

The default configuration includes Google's public STUN server. STUN alone cannot connect every mobile carrier/NAT combination. Add TURN values to Gradle project properties before building:

- `CALLIO_TURN_URL` — for example `turn:your-turn-host:3478` or `turns:your-turn-host:5349`
- `CALLIO_TURN_USERNAME`
- `CALLIO_TURN_CREDENTIAL`

For production, use short-lived TURN credentials minted by a trusted backend. Static TURN credentials embedded in an APK can be extracted. TURN relay usage may cost money, so start with same-Wi-Fi tests and budget a small relay allowance before public launch.

## What is not finished yet

- Android Telecom `ConnectionService` / self-managed PhoneAccount integration for deeper system call integration
- Google sign-in
- Full call history screen and profile editing parity
- Production TURN credential endpoint
- Server-side stale-call timeout and scheduled cleanup
- End-to-end tests on two physical phones, mobile-to-mobile and Wi-Fi-to-mobile
- Signed release APK / Play Store publishing configuration

The app should be treated as a development build until those items and the live tests are complete.
