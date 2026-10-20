# 📱 InmobilIA Pro — App móvil nativa

Wrapper Capacitor de la PWA para Android e iOS. Compila APK / IPA reales.

## Setup

```bash
cd mobile-app
npm install
npx cap add android
npx cap add ios
```

## Build Android (APK)

```bash
npm run build:android
# Abre Android Studio. Build → Generate Signed Bundle / APK
```

## Build iOS

```bash
npm run build:ios
# Abre Xcode. Configura cert + Archive
```

## Publicar

- **Play Store**: Sube el `.aab` desde Android Studio
- **App Store**: Archive → Distribute desde Xcode

La app envuelve la web (https://inmobilia-pro.onrender.com) con soporte nativo de:
- Cámara
- Geolocalización
- Push notifications (FCM/APNS)
- Status bar / splash screen
