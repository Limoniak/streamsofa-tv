# Roadmap

Ideas for StreamSofa, grouped by theme. Nothing here is a promise: priorities move with feedback.
Shipped changes are listed in the [changelog](CHANGELOG.md).

**Legend**: 🟢 small · 🟡 medium · 🔴 large · ⚠️ depends on Twitch APIs that may not allow it

## Next up

- [ ] 🟡 English interface, with the language picked from the system (the app is French only today).
- [ ] 🟢 Screenshots and a short demo GIF in the README.
- [ ] 🟢 Choose and add a license.
- [ ] 🟢 Channel zapping (Up / Down) in guest mode, using the popular streams list.

## Watching

- [ ] 🟡 "Stream ended" screen: offer the raid target, the next followed live, or the last broadcast.
- [ ] 🟢 Remember the chosen quality per channel.
- [ ] 🟢 Stream info overlay: resolution, bitrate, latency to live, dropped frames.
- [ ] 🟢 Audio-only mode, to listen with the screen off.
- [ ] 🟢 Sleep timer.
- [ ] 🟡 Playback speed and chapters (game changes) for past broadcasts. ⚠️
- [ ] 🟡 Picture-in-picture on devices that support it, to browse while watching.
- [ ] 🟡 Multi-view: swap a tile for another channel without leaving the layout.

## Chat

- [ ] 🟡 Chat appearance settings: text size, panel width, transparent overlay on top of the video.
- [ ] 🟡 Emote picker for sending (the account's emotes are already readable).
- [ ] 🟢 Highlight messages that mention you; optional timestamps.
- [ ] 🟡 Reply to a message and show reply threads.
- [ ] 🔴 Read-only polls and predictions. ⚠️

## Discover

- [ ] 🟡 Android TV launcher row with your followed channels that are live.
- [ ] 🟢 Recent searches.
- [ ] 🟡 Top clips per category and per followed channel.
- [ ] 🟢 Follow a channel by scanning a QR code that opens its page on the phone (following from the app itself is no longer allowed by the Twitch API).

## Accounts and profiles

- [ ] 🟡 Settings per profile (accent color, chat, quality) instead of shared ones.
- [ ] 🟡 Optional PIN on a profile.
- [ ] 🟡 Kids profile that hides mature content.
- [ ] 🟢 Clear status when an account has to sign in again, with one-click re-login.

## Quality and tooling

- [ ] 🟡 UI tests for the player remote (zapping, live list, chat, action bar).
- [ ] 🟡 Startup and scrolling benchmarks in CI, with automatic Baseline Profile refresh.
- [ ] 🟢 Opt-in local crash log, exportable to attach to a bug report (nothing sent automatically).
- [ ] 🟢 Beta update channel for pre-releases.

## Not planned

- Blocking or skipping ads, or unlocking subscriber / Turbo perks.
- Following and unfollowing from the app, as long as Twitch keeps those endpoints closed to third-party apps.
