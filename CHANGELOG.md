# Changelog

## 1.0.2-beta — 29 October 2026

### Added
- **About** (Settings → About): Harmattan Home's icon and version, Create backup, Restore backup and Check for updates (looks for a newer release on GitHub), and these release notes.
- **Search as an app:** the N9 Search has its own icon in Applications, with any icon style. Google's app now shows as itself.
- **Rename apps** (Customize Applications): give any app your own name; "Original name" puts it back.

### Changed
- Recents is now called **Overview**, with its own icon in Home settings.
- **Home settings:** categories slide in and out as N9 pages do; the Appearance icon matches the others.
- **Customize Today** keeps System notifications; the status bar, scroll bars, gesture pill and pull down settings are in Home settings (Status bar and Appearance). Backup and restore moved from Customize Overview to About.
- Holding Today shows **Feed** and **Settings** (the Home editor is a pinch away).
- Customize Applications: **Icons** comes before **Icon shape**, and both are camera-style grids, four to a row, each tile showing the icon it gives.

### Fixed
- Apps that change their own icon (such as Duolingo) no longer jump to the end of Applications or out of their folder when it changes.

## 1.0.1 — 27 October 2026

### Added
- **Search, as the N9's.** A search screen rebuilt from the N9 Search app's own firmware artwork: the light grey sheet with rounded corners, the header strip, and the N9 text field with its blue border (Nokia Pure Light 24 px, prompt in the N9's grey). Results list each app with its icon, name and "Application"; matches at the start of a name come first, then a word inside it, then anywhere. The last row searches the web. Back or Home closes it.
- **Ways into Search:** pull down at the top of any view, or tap the Search icon in Applications (it no longer opens Google's app).
- **Pull down setting** (Customize Today → System): Search (default), Quick settings, or Off.
- **Pinch for the Home editor:** pinch in on Applications or Today. On Recents, pinching in switches to small tiles first, then opens the Home editor.
- **Feed button** beside Home editor when you hold Today, opening the feed's settings on their own page.
- **Dark top switch** (Customize Today → Wallpaper) to turn off the dark shade behind the date and weather.
- **Phone wallpaper too** (Customize Today → Wallpaper): also sets your picture as Android's home wallpaper, so Recents shows the same one. Off by default; the lock screen keeps its own.

- **Backup and restore** (Customize Recents → Backup): saves app positions, folders, hidden apps, sizes and icon settings, the wallpaper and its settings, feed sources and apps, and the weather place to one file you can keep anywhere. Restoring replaces them and restarts home. Recents aren't included.

### Changed
- **Endless parallax:** the wallpaper keeps scrolling the same way as you swipe round the views, a quarter of the screen per view. Every other copy is mirrored so the joins don't show.
- **Dark top** fades in as Today arrives, instead of growing with the swipe, and fades out as you scroll down the feed.
- **Holding Today** dims the whole view, wallpaper and empty space included, with the ✕ buttons drawn on top. The ✕ buttons are smaller and sit on the corner of each notification's icon.
- **Back to top** appears once five posts have scrolled by, and slides up into place (and back down) as it fades. The Done button on Customize sheets slides in the same way.
- **Customize sheets:** settings start further below the preview, and fade out as they scroll up under it instead of being cut off.
- **Customize Today preview** shows the status bar again (N9 or Android style, following the setting), all notifications, then the feed.
- **Feed settings** moved out of Customize Today into the new Feed page.
- **Pull down set to Off:** the top edge now follows your finger at half speed and springs back, as the N9's edges do, instead of a short stiff stretch.

### Fixed
- Black strips at the sides of the wallpaper when swiping into Today.
- A folder could keep its ✕ after leaving edit mode (once an app had been dropped into an empty folder).
- Holding Today while the feed was still moving (or moving under the finger) brought up the toolbar.
- Holding Today left the wallpaper and the area below the notifications undimmed, with a gap at the left edge.
- The Add a source example no longer names a particular website.
- Tapping Done on the Feed page went to Recents instead of back to Today.
- Some photos were saved cut off, with a black lower part (and passed to Android that way). Android's photo picker hands apps a filtered copy that was damaged for some Samsung photos; wallpapers are now chosen with Android's file chooser, and a picture is only swapped in once it's been read and saved whole.

### Battery
- The N9 status bar stops its clock tick and its twice-a-second Wi-Fi activity check while you're in other apps; they used to keep running behind them.

### Notes
- Apps turning half see-through while you rearrange them matches the N9: its stylesheet sets `SwipeLauncherButtonStyle:edit-mode-enabled { opacity: 0.5 }`.
- After swiping up out of an app, home can't be scrolled for a moment. This comes from Android: One UI holds touches back from home until its own swipe-home transition ends (measured on a Galaxy Z Flip7), so it can't be changed from the app.
- New firmware artwork in `app/art/n9-1x/` (upscaled by `tools/hd_assets.py`): `n9_textedit`, `n9_textedit_selected`, `n9_search_header`, `n9_icon_search`.
