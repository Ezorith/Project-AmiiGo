# Project AmiiGo

**Harmattan Home** — the Nokia N9's MeeGo 1.2 Harmattan home screen, rebuilt for Android from the N9's own firmware.

> **Beta.** Harmattan Home is usable day to day, but expect rough edges. Bug reports are very welcome.

Swipe. That's how the N9 worked, and that's how Harmattan Home works: three views on an endless ring — **Applications → Recents → Today → Applications…** — dragged one-to-one under your finger, with the N9's own fonts, artwork, physics and timing.

---

## Why it's the most authentic Harmattan launcher

Most "N9-style" launchers are redrawn from screenshots. Harmattan Home isn't. It was built by unpacking the N9's PR1.3 firmware (`DFL61_HARMATTAN_40.2012.21-3`) and taking the real thing apart.

### Taken from the compiled firmware, not from screenshots

- **Layouts, sizes and timings** come straight from the N9 home screen's stylesheets (`meegotouchhome.css`): tile sizes, spacing, animation durations and easing curves, down to the 20 ms stagger when you close all apps.
- **Colours, fonts and type sizes** come from the Blanco theme's constants. The text is Nokia Pure, from the firmware.
- **The motion is the N9's.** The `overshotbezier` curve was read out of `libmeegotouchcore` as a 100-point table, and the kinetic scrolling physics (`MPhysics2DPanning`: friction, edge spring, bounce) were decoded from the ARM machine code and re-implemented exactly.
- **The words are the N9's:** "Clear", "Refresh", "New folder", "Close all", "Nothing open yet" come from the firmware's own translation files. Text the N9 never showed (like a "no notifications" message) isn't shown here either.
- **The weather widget** is laid out from the N9's AccuWeather home widget, decompressed from inside its extension library, with the N9's own 20 weather icons.
- **The settings pages** follow the N9 Settings app: its group headers, drill-down arrows, sliders and switches, all drawn from the original theme art.

### Upscaled for today's screens

The firmware only ships its artwork at the N9's 480 × 854 resolution. Instead of stretching it, every image is rebuilt at 3× with an edge-aware upscaler ([`tools/hd_assets.py`](tools/hd_assets.py)). It keeps outlines crisp and transparent areas clean, and avoids halos. Buttons are drawn in code at your screen's resolution, matched to the shape and shading of the originals. The untouched 1× originals are kept in `app/art/n9-1x/`.

### Modernised for Android

- **High refresh rate:** home runs at your screen's fastest rate (120 Hz on most flagships). The N9's 60 Hz physics are kept and drawn smoothly in between frames.
- **Fits your phone:** the status bar sits in the camera cutout, clears rounded corners, and works with the gesture pill. You can use the N9 status bar or Android's own.
- **Per-panel sizes:** Applications, Recents, Today and the status bar can each be made bigger or smaller, along with text and icon size.
- **Your icons, your way:** keep the N9's original icons for Phone, Messages, Web, Camera and friends; cut every other app to the N9 squircle (or circle, rounded, square…); or use any Nova / ADW icon pack.
- **Wallpaper** behind Today, with a dark top so the date and weather stay readable, and a soft blur behind the other views.

### The full experience with Shizuku

Android doesn't let a home app see or control the system's recent apps. With [Shizuku](https://shizuku.rikka.app/) installed and allowed, Harmattan Home does what the N9 did:

- **Recents matches Android's recent apps exactly,** in the same order. Closing an app in one closes it in the other.
- **Live previews:** each Recents tile shows the app as you left it, using Android's own task snapshots, the closest thing to the N9's live windows. Leaving an app, its window shrinks back into its tile.
- **Close all,** as on the N9.

Without Shizuku, everything still works: Recents follows the apps you open, and tiles show app-coloured window cards.

---

## Features

### Applications
- The N9 grid, kinetic scrolling and all.
- **Folders:** hold an app, tap **New folder**, then drag apps in and out. Tap a folder's name to rename it.
- Hold to rearrange, or tap ✕ to uninstall.
- Hidden apps, chosen from an icon grid.

### Recents
- Large tiles, switching to three to a row past a number of apps you choose (10 by default).
- Pinch to switch between large and small tiles.
- Hold for **Done** and **Close all**; tap ✕ on a tile to close it.
- Live previews and system Recents sync with Shizuku.

### Today
- The date with an N9 weather widget. Weather comes from [Open-Meteo](https://open-meteo.com/) (free, no account), for your location or a city you choose, in °C or °F.
- **Notifications** with each app's own icon. Music shows its album art with the app as a badge. Tap to open and clear; ongoing ones (charging, USB…) can be shown or hidden.
- **Feed:**
  - posts from social apps' notifications, kept after the notification goes;
  - public posts from Bluesky, Mastodon, YouTube, Reddit or Tumblr accounts, or any website with an RSS / Atom feed;
  - each post shows its source's picture with the service's badge, and opens in its app when it's installed.
- **Back to top** once you've scrolled the feed.

### Everywhere
- Pull down at the top of any view for quick settings (or notifications).
- An N9 status bar that shows Android's real signal, network name, Wi-Fi activity and battery.

### Home settings
Hold an empty part of **Today** and tap **Home settings**. Home zooms out to show the three views side by side; pick one and tap **Customize** for its settings, each with a live preview.

---

## Requirements

- **Android 8.0 or newer.** Wallpaper blur needs Android 12 or newer.
- **Recommended:** gesture navigation.
- **Optional:** [Shizuku](https://shizuku.rikka.app/), for system Recents sync and live previews.
- Tested on a Samsung Galaxy Z Flip7.

## Install

1. Download the latest APK from [Releases](../../releases) and install it.
2. Open **Home settings** from Android's app info for Harmattan Home, or set it as your home app and hold an empty part of Today.
3. Under **Permissions**, allow these. Each is optional, but the views they feed stay empty without them:
   - **Default home app**
   - **Notifications**: for Today and the feed.
   - **Usage access**: so Recents follows apps you open from elsewhere.
4. *(Optional)* Install and start Shizuku, then turn on **Customize Recents → Match system Recents**.

## Build

```bash
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. To regenerate the high-resolution artwork from the 1× originals:

```bash
python tools/hd_assets.py
```

(Needs Python 3 with Pillow.)

---

## How it was made

The N9's firmware image is a FIASCO container. Its `rootfs` is a chunked LZO1X stream (20-byte chunk headers `10 b4 c3 b8`), whose payload is a sparse map (`a1 a9 ae ad`) followed by a 4 GiB ext4 filesystem. Once unpacked:

| Taken | From the firmware |
|---|---|
| Layout, sizes, timings, curves | `/usr/share/themes/base/meegotouch/meegotouchhome/style/meegotouchhome.css` |
| Colours, fonts, sizes | `/usr/share/themes/blanco/meegotouch/constants.ini` |
| Settings look | `libmeegotouchviews` common styles, `duicontrolpanel` |
| Fonts | Nokia Pure Text (and the tabular cut), `/usr/share/fonts/nokia` |
| Artwork | Blanco theme images: icons, status icons, buttons, sliders, switches, masks |
| Default app order | `/etc/xdg/meegotouchhome-nokia/launcherbuttons.data` |
| Words | `home_en.qm`, `notification_en.qm` |
| `overshotbezier` curve | 100-float table at `0x4404beb0` in `libmeegotouchcore.so.0.25.9` |
| Kinetic scrolling | `MPhysics2DPanning::integrateAxis`, decoded from ARM, plus `mpannablewidgetstyle.css` |
| Weather widget | `widget.qml`, compressed inside `libevents-weather.so` |

MeeGo stylesheets measure in millimetres at 10 px per mm on the 480 px-wide N9. Harmattan Home lays everything out in N9 pixels and scales it to your screen.

### Where it differs from the N9, and why

- **Recents tiles** can't show live windows: Android doesn't let home apps see other apps' screens. With Shizuku they show Android's last snapshot of each app; without it, app-coloured cards.
- **The swipe from the bottom** belongs to Android. Harmattan Home takes over the moment it ends, so leaving an app shrinks its window into Recents, as on the N9.
- **The feed** can't sign in to Facebook or X the way the N9's services did. It collects those apps' notifications instead, plus public feeds.
- **Wallpaper and per-panel sizes** are additions; leave them at their defaults for the stock N9 look.

---

## Notices

Project AmiiGo is a fan project. It isn't affiliated with or endorsed by Nokia, HMD Global or Microsoft.

The Nokia Pure fonts, the Blanco theme artwork and the other firmware material in this repository belong to Nokia; see [`app/NOKIA_PURE_NOTICE.txt`](app/NOKIA_PURE_NOTICE.txt). They're included for personal, non-commercial use and preservation. Nokia, N9 and MeeGo are trademarks of their respective owners.

The N9 firmware image itself is **not** included and isn't needed to build the app.
