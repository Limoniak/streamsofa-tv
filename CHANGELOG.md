# Changelog

All notable changes to StreamSofa are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
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

[Unreleased]: https://github.com/Limoniak/streamsofa-tv/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/Limoniak/streamsofa-tv/releases/tag/v0.1.0
