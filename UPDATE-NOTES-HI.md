# Callio Android — Update Notes (Hinglish)

## Is update mein kya badla?
1. App ke andar ab bottom navigation hai: Home, Friends, Requests, Profile.
2. Profile editing ko alag Profile tab mein shift kiya.
3. Friends tab mein username se friend add karne ka option aur existing friends filter karne ka option hai.
4. Friend cards ko clean kiya aur direct audio-call button rakha.
5. Friends loading ab `friendships` collection ke saath accepted `friendRequests` ko bhi check karti hai. Isse purane web app ke accepted relations ka data milne ke chances better hote hain.
6. Existing Firebase project/config, ringtone, app package aur WebRTC calling code ko preserve kiya.

## Exactly kaun si files update hui?
- `app/src/main/java/com/callio/app/CallioApp.kt`
- `app/src/main/java/com/callio/app/CallioRepository.kt`
- `README.md`

## Important
- `app/google-services.json` aur `app/src/main/res/raw/callio_ringtone.wav` ko delete ya replace mat karna.
- Firestore collections reset/migrate nahi ki gayi hain.
- Is environment mein Android SDK/Gradle available nahi tha, isliye naya APK locally compile karke verify nahi hua.
- ZIP ko GitHub repo mein upload karne ke baad Actions tab mein `Build Callio Android APK` green hona chahiye. Agar red ho, Actions ka error text share karna; iske liye screenshot/file upload zaroori nahi, error lines copy karke bhej sakte ho.
- Ye development build hai; dono phones par call testing aur TURN/network testing abhi required hai.
