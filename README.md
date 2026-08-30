# Hush Music

YouTube Music and YouTube on Android, playing in the background, without ads.

A small app (4.6 MB) that wraps both sites and adds the two things the mobile web is missing:
playback that keeps going when you leave the screen, and real media controls.

## Features

- **Plays in the background** — screen off, another app open, phone in your pocket. Music *and* video.
- **No ads** — pre-roll, mid-roll and banners are skipped.
- **Lock screen and notification controls** — album art, play/pause, next, previous and a seek bar.
  Headset and Bluetooth buttons work too.
- **Both in one app** — YouTube Music and YouTube side by side, and switching between them never
  interrupts what is playing.
- **Sign in with Google** — your playlists, history and recommendations, as normal.

## Install

1. Download `hush-music-v1.0.0.apk` from [Releases](../../releases).
2. Open it on your phone and allow installing from unknown sources when asked.
3. On first launch, tap **Allow** on the battery optimisation prompt. Without it Android stops
   playback once the phone has been sitting idle.

Requires Android 8.0 or newer. Updates are manual — there is no store listing.

## Using it

**Switching tabs.** The bar at the bottom gets out of the way once you start playing something, so
the page has the whole screen. A small floating button takes its place: tap it to bring the switcher
back, and drag it anywhere if it is covering something. It stays where you put it.

**Stopping.** Pause from the notification, or swipe the app out of Recents to stop it completely.

## Building

```sh
make build
```

Builds a signed release APK and installs it on a connected device. Needs the Android SDK and JDK 17+.

Signing reads `keystore.properties` from the repo root, which is gitignored:

```properties
storeFile=/absolute/path/to/keystore.jks
storePassword=
keyAlias=
keyPassword=
```

Without that file the build falls back to the debug key, so a fresh clone still compiles.

## How it works

Two WebViews pointed at `music.youtube.com` and `m.youtube.com`, owned by the application rather
than by the screen, so Android reclaiming the Activity does not take the audio with it. Injected
JavaScript reports what the page is playing, which drives a real `MediaSession` — that is where the
notification and lock screen controls come from. Ads are handled by a blocklist for tracker hosts
plus a small script that skips in-stream ads.

The interesting details are commented where they matter, in `Players.kt`.

## Limitations

- Ad skipping follows YouTube's page structure, so it can break when YouTube changes it. The fix is
  usually a CSS selector in `Players.kt`.
- No picture-in-picture, downloads or offline playback.
- Sideload only, so no automatic updates.

## Contributing

`TESTING.md` is the manual checklist to run on a real device before tagging a release. Most of what
can break here — background audio, Doze, lock screen controls — cannot be caught by unit tests.
