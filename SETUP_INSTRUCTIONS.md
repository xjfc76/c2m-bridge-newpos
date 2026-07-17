# CouchToMouth Bridge App - Self-Update Setup Instructions

## What Has Been Done

I've added code to your Android app that enables automatic updates. Here's what was added:

1. **UpdateManager.kt** - Checks for updates when app opens
2. **AndroidManifest.xml** - Permission to install APKs
3. **file_paths.xml** - Required for Android 7+ APK installation
4. **GitHub Actions workflow** - Automatically builds APK when you push code
5. **releases/version.json** - Tells the app what version is available

---

## Step-by-Step Setup

### STEP 1: Push Code to GitHub

Run these commands on your VPS:

```bash
cd /root/var/www/html

# Stage all the new files
git add couch2mouth-bridge-app/
git add .github/

# Commit
git commit -m "Add self-update system for Android app"

# Push to GitHub
git push origin main
```

---

### STEP 2: Add GitHub Secrets

1. Open your browser and go to:
   ```
   https://github.com/xjfc76/couchToMouth_pos/settings/secrets/actions
   ```

2. Click **"New repository secret"**

3. Add these 3 secrets one by one:

#### Secret 1
- **Name:** `VPS_HOST`
- **Secret:** `pos.couchtomouth.com`

#### Secret 2
- **Name:** `VPS_USER`
- **Secret:** `root`

#### Secret 3
- **Name:** `VPS_SSH_KEY`
- **Secret:** (see below)

To get the SSH key, run this on your VPS:
```bash
cat ~/.ssh/id_rsa
```

Copy the ENTIRE output including:
```
-----BEGIN OPENSSH PRIVATE KEY-----
(all the random characters)
-----END OPENSSH PRIVATE KEY-----
```

Paste that as the secret value.

---

### STEP 3: Build First APK with Android Studio

Since this is the first APK with the self-update code, you need to build it manually ONE TIME.

1. **On your computer**, open Android Studio

2. **File → Open** and select the `couch2mouth-bridge-app` folder

3. Wait for **Gradle sync** to finish (bottom status bar)

4. Go to **Build → Build Bundle(s) / APK(s) → Build APK(s)**

5. Wait for build to complete

6. Click **"locate"** in the notification, or find it at:
   ```
   couch2mouth-bridge-app/app/build/outputs/apk/debug/app-debug.apk
   ```

---

### STEP 4: Upload APK to Server

1. **Rename** the file from `app-debug.apk` to `couch2mouth-bridge.apk`

2. **Upload** to your VPS using one of these methods:

**Option A - Using FileZilla/SFTP:**
- Connect to `pos.couchtomouth.com`
- Navigate to `/var/www/html/couch2mouth-bridge-app/releases/`
- Upload `couch2mouth-bridge.apk`

**Option B - Using command line (from your computer):**
```bash
scp couch2mouth-bridge.apk root@pos.couchtomouth.com:/var/www/html/couch2mouth-bridge-app/releases/
```

---

### STEP 5: Install on Your Devices

#### Method A: Download from URL
1. On your Android device, open Chrome
2. Go to: `https://pos.couchtomouth.com/couch2mouth-bridge-app/releases/couch2mouth-bridge.apk`
3. Tap to download
4. Tap notification to install
5. If blocked, go to Settings → Apps → Chrome → Install unknown apps → Allow

#### Method B: Transfer via USB/Email
1. Email the APK to yourself
2. Open email on device
3. Download and install

---

## How to Release Future Updates

After the initial setup, releasing updates is simple:

### 1. Make your code changes in Cursor

### 2. Bump the version number

Edit `couch2mouth-bridge-app/app/build.gradle.kts`:

```kotlin
versionCode = 2        // Increment this (was 1, now 2)
versionName = "1.1.0"  // Update this too
```

### 3. Push to GitHub

```bash
cd /root/var/www/html
git add .
git commit -m "Version 1.1.0 - description of changes"
git push origin main
```

### 4. That's it!

GitHub Actions will automatically:
- Build the new APK
- Upload it to your server
- Update version.json

### 5. Users get the update

Next time users open the app, they'll see:
```
┌─────────────────────────────────┐
│     Update Available            │
│                                 │
│  A new version (1.1.0) is       │
│  available.                     │
│                                 │
│  Current version: 1.0.0         │
│                                 │
│  [Update Now]  [Later]          │
└─────────────────────────────────┘
```

They tap "Update Now" → APK downloads → Android installer opens → Done!

---

## Troubleshooting

### GitHub Actions not running?
- Check: https://github.com/xjfc76/couchToMouth_pos/actions
- Make sure secrets are added correctly
- Make sure you pushed to `main` branch

### APK not downloading on device?
- Check the URL works in browser: `https://pos.couchtomouth.com/couch2mouth-bridge-app/releases/couch2mouth-bridge.apk`
- Make sure the file exists on server

### App not detecting update?
- Make sure `versionCode` in build.gradle.kts is HIGHER than current
- Check version.json on server has the new version number

### Installation blocked?
- Settings → Apps → Special access → Install unknown apps
- Enable for browser or file manager

---

## Quick Reference

| Task | Command/Action |
|------|----------------|
| Push new version | `git add . && git commit -m "v1.x" && git push` |
| Check build status | https://github.com/xjfc76/couchToMouth_pos/actions |
| APK download URL | https://pos.couchtomouth.com/couch2mouth-bridge-app/releases/couch2mouth-bridge.apk |
| Version info URL | https://pos.couchtomouth.com/couch2mouth-bridge-app/releases/version.json |
