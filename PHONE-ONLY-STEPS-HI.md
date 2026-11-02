# Callio Native Android — Phone se simple setup

Bhai, ye naya Android app project hai. Purani website ko delete ya replace nahi karna.

## Step 1 — Firebase Android app add karo

1. Firebase Console kholo.
2. Wahi Callio Firebase project select karo jo website use karti hai.
3. Project settings → Your apps → Add app → Android.
4. Package name bilkul ye likho: `com.callio.app`
5. `google-services.json` download karo.
6. Is file ko native project ke `app` folder mein rakho.

**Naya Firebase project mat banana.** Isse existing users aur friends ka data wahi rahega.

## Step 2 — Website ka push endpoint update karo

Native app ka background incoming-call push existing website ke secure API se bheja jayega.

1. Existing Callio website repository mein jao.
2. `app/api/calls/notify/route.js` kholo.
3. Native ZIP ke `backend-patch/app/api/calls/notify/route.js` ka complete code is file mein paste karke replace karo.
4. Commit karo; Vercel redeploy karega.

Vercel ke Firebase Admin environment variables ko delete/change mat karna.

## Step 3 — Server URL set karo

Native project ke `gradle.properties` mein:

`CALLIO_SERVER_URL=https://YOUR-CALLIO-VERCEL-DOMAIN.vercel.app`

`YOUR-CALLIO-VERCEL-DOMAIN` ko apni actual website ke Vercel URL se replace karo.

## Step 4 — GitHub se APK build

1. Native project ko GitHub ke naye repo `callio-native-android` mein upload karo.
2. `app/google-services.json` bhi upload karo.
3. Main branch par commit/push karo.
4. GitHub → Actions kholo.
5. `Build Callio Android APK` workflow run hone do.
6. Successful run ke Artifacts se `Callio-Android-debug-APK` download karo.
7. ZIP extract karke `app-debug.apk` dono Android phones par install karo.

## Step 5 — Do phone test

1. Dono phones par Callio install karo.
2. Dono par existing Callio email/password se login karo.
3. Dono accounts ek dusre ke accepted friends hone chahiye.
4. Dono phones par notification permission allow karo.
5. Phone B par app background mein bhejo, force-stop mat karo.
6. Phone A se B ko call karo.
7. B par incoming-call notification / full-screen screen aani chahiye. Accept karo; phir audio test karo. Decline bhi test karo.

## Zaroori sach

- Is ZIP mein Firebase config file nahi hai; tumhare Firebase project se download karni hogi.
- Is source ko yahan full Android Gradle build nahi mila, kyunki build environment/dependencies available nahi the. GitHub Actions pe pehla compile check hoga.
- Pehla version Android CallStyle notification aur apni full-screen UI deta hai. Android ke Phone app jaisa deep Telecom integration next milestone hai.
- STUN-only connection har mobile network par kaam nahi karega. Reliable carrier-to-carrier calls ke liye TURN server chahiye.
- Android force-stop, disabled notifications, no internet, revoked microphone permission ko bypass nahi kar sakta.
