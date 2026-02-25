<h1 align="center">Jellyfin Android TV DV7 to DV8</h1>
<h3 align="center">Part of the <a href="https://jellyfin.org">Jellyfin Project</a></h3>

---

<p align="center">
<img alt="Logo banner" src="https://raw.githubusercontent.com/jellyfin/jellyfin-ux/master/branding/SVG/banner-logo-solid.svg?sanitize=true"/>
<br/><br/>
<a href="https://github.com/jellyfin/jellyfin-androidtv">
<img alt="GPL 2.0 License" src="https://img.shields.io/github/license/jellyfin/jellyfin-androidtv.svg"/>
</a>
</p>

Jellyfin Android TV is a Jellyfin client for Android TV, Nvidia Shield, and Amazon Fire TV devices. We welcome all contributions and pull
requests! If you have a larger feature in mind please open an issue so we can discuss the implementation before you start. 

+## This Fork — What's New
+ 
+This is a modified version of the official Jellyfin Android TV client that adds **Dolby Vision Profile 7 compatibility mode**.
+ 
+### The Problem
+UHD Certain types of media containing Dolby Vision 7 Profiles fail to play Dolby Vision on some android devices and TV's.
+ 
+### The Fix
+This fork adds a compatibility mode that silently rewrites Profile 7 streams as Profile 8.1 — a single-layer format that most Android TV devices support natively. 
+ 
+### How to Enable
+**Settings → Playback → Advanced → Video → Dolby Vision Compatibility**
+ 
+A second toggle — **Force Compatibility Mode** — is available for devices that claim to support Profile 7 but play it poorly.
+ 
+### Compatible Devices
+| Device | Result |
+|---|---|
+| TBD | Full Dolby Vision |
+| TBD | Full Dolby Vision |
+ 
+### Download
+Pre-built APKs are available on the releases page. Sideload via ADB or a file manager app. Requires Android 6.0 or later.
+ 
+---
+ 
+Jellyfin Android TV is a Jellyfin client for Android TV, Nvidia Shield, and Amazon Fire TV devices. We welcome all contributions and pull
+requests! If you have a larger feature in mind please open an issue so we can discuss the implementation before you start.

## Building

The app uses Gradle and requires the Android SDK. We recommend using Android Studio, which includes all required dependencies, for
development and building. For manual building without Android Studio make sure a compatible JDK and Android SDK are installed and in your
PATH, then use the Gradle wrapper (`./gradlew`) to build the project with the `assembleDebug` Gradle task to generate an apk file:

```shell
./gradlew assembleDebug
```

The task will create an APK file in the `/app/build/outputs/apk/debug` directory. This APK file uses a different app-id from our stable
builds and can be manually installed to your device.

## Branching

The `master` branch is the primary development branch and the target for all pull requests. It is **unstable** and may contain breaking
changes or unresolved bugs. For production deployments and forks, always use the latest `release-x.y.z` branch. Do not base production work
or long-lived forks on `master`.

Release branches are created at the start of a beta cycle and are kept up to date with each published release. Maintainers will cherry-pick
selected changes into release branches as needed for backports. These branches are reused for subsequent patch releases.

## Translating

Translations can be improved very easily from our [Weblate](https://translate.jellyfin.org/projects/jellyfin-android/jellyfin-androidtv)
instance. Look through the following graphic to see if your native language could use some work! We cannot accept changes to translation
files via pull requests.

<p align="center">
<a href="https://translate.jellyfin.org/engage/jellyfin-android/">
<img alt="Detailed Translation Status" src="https://translate.jellyfin.org/widgets/jellyfin-android/-/jellyfin-androidtv/multi-auto.svg"/>
</a>
</p>
