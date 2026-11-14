# Changelog

All notable changes to StreamSofa are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Live player: OK opened the chat along with the action bar on some remotes; it now only opens the action bar.

## [0.3.0] - 2026-11-14

### Added
- English interface. The app follows the system language (English or French) and can be set per app in the Android settings on Android 13 and later.
- Channel zapping (Up / Down) without followed channels live, in guest mode for example: it goes through the popular streams.
- The project is now licensed under the GNU General Public License v3.0 or later.
- Screenshots and a short demo in the README.
- The live player remembers the quality chosen for each channel.
- Stream stats in the live player's action bar: resolution, bitrate, latency to live, dropped frames.
- Audio only mode for live streams: less data, and the sound keeps playing when the screen goes to sleep.
- Sleep timer (15, 30, 60 or 90 minutes) in the live player.
- Chat messages that mention you are highlighted; optional message times (Settings > Chat).
- Recent searches, shown under the empty search field.
- A QR code on each channel page opens the channel on your phone, to follow it there.
- A dot on "Profiles" in the side menu when an account has to sign in again.
- Optional crash log, kept on the device and exportable to Downloads (Settings > Crash log). Nothing is sent.
- Beta versions: an option to also be offered pre-releases (Settings > Updates).
- Project website, with a short install link to type in Downloader on the TV: `limoniak.github.io/streamsofa-tv/get`.
- Each release also ships `streamsofa.apk` (universal), always reachable at `releases/latest/download/streamsofa.apk`.
- Past broadcasts: Up changes the playback speed (1×, 1.25×, 1.5×, 2×, 0.75×); chapters (game changes) are marked on the progress bar and the current one is named.
- When a live stream ends, or a channel is offline, the player offers the raid target, the next live channel and the channel's last broadcast.
- Multi-view: a long press on OK lets you replace a tile with another channel, without stopping the other tiles.
- Picture-in-picture in the live player's action bar, on devices that support it.
- Chat appearance (Settings > Chat): text size, width, and a see-through chat on top of the video.
- Emote picker when writing a message: the account's Twitch emotes, the channel's first, and its 7TV / BTTV / FFZ emotes.
- Chat replies: a reply shows the message it answers, and the composer can reply to a recent message.
- Polls and predictions of the channel, read-only, at the top of the chat.
- "Live channels you follow" row on the Android TV home screen, refreshed every 30 minutes (Google TV may not show it).
- Top clips of the week in each category (following the language filter), and clips from the channels you follow in Following.
- Each profile, guest included, now has its own settings (accent color, chat, quality, language filter, recent searches). Update and crash log options stay shared. Your current settings are copied into every profile.
- Optional 4-digit PIN on a profile (long press on it in the profile picker). A forgotten PIN: remove the profile and sign in again.
- Kids profile: no chat, and channels marked mature or carrying content warnings are hidden and cannot be played. Only the PIN of a non-kids profile can turn it off.
- Spanish, German, Italian and Brazilian Portuguese interface.
- Faster channel zapping: while you watch, the channels above and below are prepared, so Up / Down skips the Twitch round trips.
- Pin channels to the top (button on the channel page): they come first among your live channels, in Up / Down zapping and in the live list.
- "Recently watched" row on Home: the channels you watched lately that are live now, guest mode included.
- Hide a category from Home with a long press on it: its streams leave Popular and Recommended too. Settings > Home brings them back.
- TalkBack reads the side menu labels when it is folded, the viewer count with its unit, the channel or video being played, and the multi-view tiles.

### Changed
- Live streams start sooner: playback begins after 1.5 s of video instead of 2.5 s.
- Smoother chat on busy channels: only new messages are laid out, instead of every visible line ten times a second.
- Smaller download: the app only ships the six languages of its interface.
- Up / Down on a channel outside the zapping list now jumps to the first channel of the list.
- Multi-view: a long press on OK opens a menu (replace or remove) instead of removing the tile at once.

### Fixed
- Singular and plural are now right in every language: "1 vote" instead of "1 votes" in polls, "up to 1 channel" in the multi-view picker.
- On Android 12 and later, app data (accounts, profile PINs, settings) is no longer copied to a new device during a device-to-device transfer.
- Only the system search can query the app's search suggestions; other apps can no longer run Twitch searches through your account.
- Multi-view: after replacing a tile, the sound no longer jumps to the first tile for a moment.
- Left in the empty search field now leaves it, instead of being stuck there.
- Multi-view channel picker: the Start and Cancel buttons could be pushed off screen by a long list.

## [0.2.0] - 2026-11-13

### Changed
- Live player remote, TV style: Up / Down change channel, Left opens the list of live channels, Right shows or hides the chat, OK opens an action bar (chat, quality, multi-view, channel page). The available keys are always shown in the info panel.
- Writing a chat message starts from a "Write" button at the bottom of the chat (Right when the chat is shown), never by accident; the message box has a Cancel button.
- New visual identity: the "stream**sofa**" wordmark with the word sitting on its own sofa, new launcher banner and icon.
- Warmer color palette: plum backgrounds, cream text, lavender accent. The orange accent becomes peach.

## [0.1.0] - 2026-11-13

First public release.

### Added
- Sign in with a Twitch account using a code shown on the TV (Device Code flow), multiple accounts and a guest mode. Tokens are encrypted with the Android Keystore.
- Home with followed live channels, recommendations, popular streams and categories; category pages with a language filter.
- Live player (HLS) with quality selection, quick channel zapping and an experimental low-latency mode.
- Chat: read and send messages, badges, 7TV / BTTV / FFZ emotes (animated on capable devices), hidden words and users, blocked users of the account.
- Channel page: past broadcasts with resume position, clips, schedule and "About" panels.
- VOD player with seeking and synchronized chat replay.
- Multi-view: watch 2 to 4 streams at once on devices that can decode them.
- Search for channels and categories.
- Raid banner: follow the raid or stay on the current channel.
- Android TV integration: "Continue watching" row, global and voice search, `twitch.tv` and `streamsofa://` links.
- Settings: chat at launch, default quality, accent color, update check at startup.
- In-app updates from GitHub Releases, with the APK matching the device's architecture.

[Unreleased]: https://github.com/Limoniak/streamsofa-tv/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Limoniak/streamsofa-tv/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Limoniak/streamsofa-tv/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Limoniak/streamsofa-tv/releases/tag/v0.1.0
