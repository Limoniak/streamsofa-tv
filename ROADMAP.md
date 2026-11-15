# Roadmap

Ideas for StreamSofa, grouped by theme. Nothing here is a promise: priorities move with feedback.
Shipped changes are listed in the [changelog](CHANGELOG.md).

**Legend**: 🟢 small · 🟡 medium · 🔴 large · ⚠️ depends on Twitch APIs that may not allow it

## Comfort

- [ ] 🟢 More interface languages (English, French, Spanish, German, Italian and Brazilian Portuguese today); translations and corrections welcome.

## Performance

Measured on a Xiaomi Mi Box 4 (Android 9, 2 GB, 32-bit, Mali-T720): scrolling a Home row still costs ~26 ms per frame (~6 ms composition, ~12 ms render thread), so it stays under 60 fps on modest boxes.

- [ ] 🟡 Find what recomposes on every frame while a row scrolls (Compose runtime tracing), and cut it.
- [ ] 🟡 Lighter cards for the GPU: fewer layers and clips per card (avatar, viewer badge, rounded image). Removing only the focus zoom and colour fade was tried and gave no gain.
- [ ] 🟡 Side menu that opens without animating its width (tv-material animates it; the content no longer moves, but the menu itself still drops frames).
- [ ] 🟢 Measure the live player, the chat and multi-view on the same box.

## Not planned

- Moving a profile's settings to another TV with a QR code: a TV has no camera to read it.
- Blocking or skipping ads, or unlocking subscriber / Turbo perks.
- Following and unfollowing from the app, as long as Twitch keeps those endpoints closed to third-party apps.
