# Changelog

All notable changes to StreamSofa are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- English interface. The app follows the system language (English or French) and can be set per app in the Android settings on Android 13 and later.
- Channel zapping (Up / Down) without followed channels live, in guest mode for example: it goes through the popular streams.
- The project is now licensed under the GNU General Public License v3.0 or later.
- Screenshots and a short demo in the README.

### Changed
- Up / Down on a channel outside the zapping list now jumps to the first channel of the list.

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

[Unreleased]: https://github.com/Limoniak/streamsofa-tv/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/Limoniak/streamsofa-tv/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Limoniak/streamsofa-tv/releases/tag/v0.1.0
