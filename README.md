# Cover Wallpaper

A Pixel live wallpaper that fills your home and lock screen with the album cover of
whatever Apple Music is playing. The cover slowly pans and zooms while music plays,
cross-fades between tracks, and freezes when you pause (so it isn't using battery).

## Build the APK (no Android Studio needed)

1. Make a new **empty** GitHub repo (e.g. `cover-wallpaper`).
2. Upload everything in this folder, including the hidden `.github` folder.
   Easiest from a computer: unzip, then drag the folder's contents onto the repo's
   "uploading an existing file" page — or `git init && git add . && git commit -m init && git push`.
3. Open the repo's **Actions** tab. The "Build APK" run starts on its own (takes ~3 min).
4. When it's green, open the run and download **CoverWallpaper-apk** at the bottom.
   It's a zip with the `.apk` inside.

Or open the folder in Android Studio and press Run.

## Install on the Pixel

1. Copy the `.apk` to the phone and open it. Allow "install unknown apps" for whatever opened it.
2. Open **Cover Wallpaper**.
3. Tap **Allow access to what's playing** and switch it on.
   - If the switch is greyed out ("restricted setting"): tap **open App info**, then ⋮ (top right)
     → **Allow restricted settings**, then try again.
4. Tap **Set as wallpaper** → choose **Home and lock screen**.
5. Play something in Apple Music.

## Tweaks

In `AlbumArtWallpaper.kt`, the constants at the bottom control the motion:
`ZOOM_PERIOD`, `PAN_X_PERIOD`, `PAN_Y_PERIOD` (seconds per cycle — bigger = slower),
`FADE_MS` (track cross-fade) and `FRAME_MS` (33 = ~30 fps).

## Limits

- It uses the still cover Apple Music shares with Android. Apple's moving "motion artwork"
  video isn't exposed to other apps, so the movement here is a pan-and-zoom effect.
- If nothing has played yet, it shows a plain gradient.
- Each GitHub build is signed with a fresh debug key, so to install a newer build you
  may need to uninstall the old one first.
