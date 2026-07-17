# CouchToMouth POS Bridge App

Android bridge app that wraps the web-based OSPOS system and adds:
- **Bluetooth receipt printing** (ESC/POS printers)
- **Cash drawer control**
- **Card payment integration** (SumUp or Zettle - coming soon)

## Supported Hardware

### Tested Printers
- TP510UB (58mm, no cash drawer)
- M335B (58/80mm, with cash drawer)
- Most ESC/POS compatible Bluetooth thermal printers

### Card Readers (Planned)
- SumUp Air
- Zettle Reader

### Android Requirements
- Android 8.0 (API 26) or higher
- Bluetooth 4.0+
- Tested on Android 14 & 15

## Building the App

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or later
- JDK 17

### Steps

1. Open the project in Android Studio
2. Update `app/build.gradle.kts` with your POS URL:
   ```kotlin
   buildConfigField("String", "POS_URL", "\"https://your-server.com/ospos_ivans/\"")
   ```

3. Build the APK:
   - For debug: `./gradlew assembleIvansDebug` or `./gradlew assembleDavesDebug`
   - For release: `./gradlew assembleIvansRelease` or `./gradlew assembleDavesRelease`

4. Install on tablet:
   ```bash
   adb install app/build/outputs/apk/ivans/debug/app-ivans-debug.apk
   ```

## Configuration

### First-time Setup

1. **Open the app** on your tablet
2. **Tap the settings icon** (⚙️) in the top right
3. **Configure printer**:
   - First, pair your Bluetooth printer in Android Settings
   - Then tap "Setup Printer" in the app
   - Select your printer from the list
   - Enable "Has Cash Drawer" if your printer has one connected (M335B)
4. **Set POS URL** to your OSPOS server address
5. **Save** settings

### Adding SumUp (When Ready)

1. Sign up at [sumup.com/developer](https://sumup.com/developer)
2. Create an app to get your **Affiliate Key** and **App ID**
3. In the bridge app settings:
   - Select "SumUp" as payment provider
   - Enter your Affiliate Key
   - Enter your App ID
4. Install the **SumUp app** on the tablet
5. Log in to SumUp and pair your card reader

### Adding Zettle (When Ready)

1. Go to [developer.zettle.com](https://developer.zettle.com)
2. Create an app to get your **Client ID**
3. In the bridge app settings:
   - Select "Zettle" as payment provider
   - Enter your Client ID
4. Install the **Zettle Go app** on the tablet
5. Log in and pair your card reader

## How It Works

### Payment Flow

```
Web POS → "Card" button clicked
    ↓
Bridge app intercepts via JavaScript
    ↓
Launches SumUp/Zettle SDK with amount
    ↓
Card payment processed
    ↓
Result returned to Bridge app
    ↓
Receipt auto-printed (card payments)
    ↓
POS updated with payment confirmation
```

### Cash Payment Flow

```
Web POS → "Cash" button clicked
    ↓
Bridge app intercepts via JavaScript
    ↓
Cash drawer opened (if configured)
    ↓
POS updated (no receipt printed)
```

## Project Structure

```
app/
├── src/main/
│   ├── java/com/couchtommouth/bridge/
│   │   ├── ui/                  # Activities
│   │   ├── printer/             # Bluetooth printing
│   │   ├── payment/             # SumUp/Zettle integration
│   │   └── config/              # App settings
│   └── res/
│       ├── layout/              # UI layouts
│       └── values/              # Colors, strings, themes
└── build.gradle.kts             # Build config
```

## Troubleshooting

### Printer not connecting
1. Make sure printer is paired in Android Bluetooth settings first
2. Printer must be turned on and nearby
3. Try restarting the printer

### Cash drawer not opening
1. Ensure "Has Cash Drawer" is enabled in settings
2. Cash drawer must be connected to printer via RJ11 cable
3. Try the "Test Drawer" button in printer setup

### WebView not loading
1. Check POS URL is correct (include https://)
2. Ensure tablet has internet connection
3. Check server is accessible from tablet's network

## License

Private - CouchToMouth Ltd

# Build trigger

