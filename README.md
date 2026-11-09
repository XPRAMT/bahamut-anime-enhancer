[English](README.md) | [繁體中文](README_TW.md)

# Bahamut Anime Enhancer

An LSPosed module that fixes the brightness and volume gestures in the player of **Anime Crazy** (巴哈姆特動畫瘋, `tw.com.gamer.android.animad`), and adds its settings to the player's own **Playback settings** panel.

## Features

- Swipe up or down on the left half for brightness and on the right half for volume. The change follows the finger's absolute travel instead of the number of touch events, so it is smooth and predictable.
- Optional: turn off long-press 2x speed so vertical swipes take over directly.
- Optional: double tap toggles play and pause.
- Adjustable swipe range: how much of the screen height covers 0–100%.
- The top and bottom 10% of the screen are ignored, so swipes from the edges do not trigger anything.
- Settings appear in the player's Playback settings panel in the app's own style, and are stored with the app's own data. The module's own screen only explains how to use it.

## Requirements

- Android 12 or later (API 31+).
- LSPosed with libxposed API 102 support (tested with LSPosed 2.2.1).
- Anime Crazy 1.19.x (tested with 1.19.7.2).

## Install

1. Install the APK from the Releases page.
2. In LSPosed, enable the module and select **Anime Crazy** as its scope.
3. Restart Anime Crazy, then open **Playback settings** in the player. The module's rows are at the bottom.

## Build

```
./gradlew assembleRelease
```

The signing key comes from the `BAE_ANDROID_KEYSTORE`, `BAE_ANDROID_KEY_ALIAS`, `BAE_ANDROID_KEYSTORE_PASSWORD` and `BAE_ANDROID_KEY_PASSWORD` environment variables (see `tools/build-release.ps1`); without them the debug key is used.

## License

[MIT](LICENSE)
