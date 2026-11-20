# NebulaMusic

A full-featured Android client for [Navidrome](https://www.navidrome.org/) (and other OpenSubsonic servers), built for people who care how their music sounds and looks.

- **Liquid glass UI** built on [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Jetpack Compose).
- **Artwork-driven theme**: with nothing loaded the app uses a deep dark background; as soon as a track is loaded (even paused) every screen is tinted by a blurred copy of its cover and the accent color is taken from it. Tracks without a cover fall back to the default dark look.
- **Original quality by default**: lossless files are streamed untouched (no transcoding), with Hi-Res / Lossless badges, a detailed audio-path sheet (codec, decoder, output format, device) and optional **bit-perfect USB DAC** output on Android 14+.
- **Streaming & library**: artists, albums, songs, genres, playlists (create, rename, delete, add and remove songs), favorites (stars), search, artist biographies and top songs.
- **Playback**: gapless playback, ReplayGain (track / album), fade between songs, scrobbling, lock-screen / notification controls with a favorite button, "stop after this song" mode, seek-by-cursor waveform with anti-click fades.
- **Offline**: download songs, albums or selections as original files and play them without a network; save to / share from any folder.
- **Multiple servers**, passwords stored with the Android Keystore (AES-GCM), settings export / import (without passwords).

## Requirements

- Android 8.0+ (API 26). Bit-perfect USB output needs Android 14+.
- A Navidrome server reachable from the phone.

## Building

```sh
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease    # needs keystore.properties (see below)
```

`keystore.properties` (not committed) must contain `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.

## Releases

Signed APKs are published on the [Releases](../../releases) page. There is no CI: releases are built and signed locally.

## Project layout

| Path | Content |
| --- | --- |
| `app/.../data` | Subsonic / OpenSubsonic client, models, server store, offline downloads, preferences |
| `app/.../player` | Media3 service and controller, ReplayGain, fades, bit-perfect USB routing, audio info |
| `app/.../glass` | Liquid glass components adapted from AndroidLiquidGlass |
| `app/.../ui` | Screens: home, library, search, album / artist / playlist / genre, player, settings, login |

## License

Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
