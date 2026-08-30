# Release checklist

Run on a real device before tagging a release. Nothing here is automated — the whole point is that
the failures live in places unit tests do not reach: the system's media stack, Doze, and YouTube's
own DOM.

## Playback

- [ ] Track plays in the Music tab
- [ ] Audio continues after pressing Home, **on the Music tab**
- [ ] Video keeps playing after pressing Home, **on the YouTube tab**. Test both tabs separately:
      they fail for different reasons. Video is suspended by Chromium's window visibility, audio
      is not, so a passing Music tab says nothing about YouTube
- [ ] Audio continues after switching tabs
- [ ] Audio continues with the screen off / phone locked (leave it 5+ minutes)
- [ ] Audio survives 30+ minutes backgrounded **on battery, unplugged** — the Doze case. A phone on
      USB never dozes, so this one cannot be tested over adb
- [ ] Audio recovers after a phone call or another app steals audio focus
- [ ] **Audio survives the Activity being destroyed under memory pressure.** This was the cause of
      background playback failing intermittently. Reproduce it deterministically:

      adb shell settings put global always_finish_activities 1
      # play something, press Home, then sample twice — position must advance
      adb shell dumpsys media_session | grep -oE "state=[A-Z]+\([0-9]\), position=[0-9]+"
      adb shell settings put global always_finish_activities 0

- [ ] App is exempt from battery optimisation, or the one-time prompt appears and can be accepted:
      `adb shell dumpsys deviceidle whitelist | grep hush`
- [ ] Known and accepted: swiping the app out of Recents kills playback

## Controls

- [ ] Notification shows title, artist and album art
- [ ] Metadata updates when the track changes
- [ ] Notification play / pause works
- [ ] Notification next / previous works
- [ ] Lock screen card appears and its controls work
- [ ] Seek bar scrubs to the right position
- [ ] Wired headset play/pause and next work
- [ ] Bluetooth headset play/pause and next work

## Tabs and chrome

- [ ] Splash shows the logo and name, then fades
- [ ] The bottom chooser is visible on first launch
- [ ] It collapses to the floating button on first playback, or when a tab is picked
- [ ] The floating button drags anywhere on screen
- [ ] Its position survives a force-stop and relaunch
- [ ] Tapping it opens the chooser, and the chooser **stays open** while music plays
      (regression: it used to be slammed shut within a second by the playback state report)
- [ ] Picking a tab closes the chooser and switches
- [ ] Back: closes the chooser, exits fullscreen, goes back in the page, then backgrounds the app

## YouTube tab

- [ ] A video plays
- [ ] Fullscreen enters, rotates to landscape, hides the system bars
- [ ] Back exits fullscreen and restores portrait and the bars
- [ ] The screen does not sleep during fullscreen playback

## Ads

- [ ] No banner or promo units on the home feed
- [ ] Pre-roll is skipped within about a second
- [ ] Mid-roll is skipped

## Account

- [ ] Google sign-in completes without "This browser or app may not be secure"
- [ ] The login survives a force-stop
- [ ] The login survives a reboot
- [ ] Personalised content (Listen again, Quick picks) appears

## Release mechanics

- [ ] `versionCode` incremented
- [ ] APK is release-signed: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
- [ ] It installs **over** the previous release without uninstalling
- [ ] A fresh install works on a device that never had the app

## Checking from adb

Useful, and one trap worth knowing.

```sh
# Reliable: does playback actually progress? Sample twice and compare position.
adb shell dumpsys media_session | grep -oE "state=PLAYING\(3\), position=[0-9]+"

# Is the foreground service alive?
adb shell dumpsys activity services io.github.nebulaanish.hush | grep isForeground

# Chooser and floating button visibility (first flag: V visible, G gone)
adb shell dumpsys activity top | grep -E "BottomNavigationView\{|app:id/fab" | awk '{print $1, $2}'
```

**Trap:** `dumpsys audio` reporting `state:started` does not mean audio is playing. The AAudio stream
stays "started" for a while after the media element pauses, so it reads as playing when it is not.
Use the MediaSession position above instead. Equally, `dumpsys` view bounds do not move when a view
is dragged — dragging changes translation, not layout, so the bounds always read as the original
position. Confirm drags from a screenshot.
