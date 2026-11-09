[English](README.md) | [繁體中文](README_TW.md)

# 動畫瘋手勢修正

修正**巴哈姆特動畫瘋**（`tw.com.gamer.android.animad`）橫向播放器亮度與音量手勢的 LSPosed 模組，並把模組設定加進播放器自己的「播放設定」面板。

## 功能

- 左半邊上下滑調亮度、右半邊上下滑調音量。數值跟著手指的絕對位移走，不依賴觸控事件數量，滑動平順、可預期。
- 可選：關閉長按 2 倍速，上下滑手勢直接接管。
- 可選：雙擊播放／暫停。
- 滑動範圍可調：螢幕高度的多少比例對應 0–100%。
- 螢幕上下各 10% 不觸發，避免從邊緣滑動時誤動作。
- 設定會出現在播放器的「播放設定」面板，沿用 App 原本的樣式，並跟著動畫瘋自己的資料一起保存。模組自己的畫面只放使用說明。

## 需求

- Android 12 以上（API 31+）。
- 支援 libxposed API 102 的 LSPosed（以 LSPosed 2.2.1 測試）。
- 動畫瘋 1.19.x（以 1.19.7.2 測試）。

## 安裝

1. 從 Releases 頁面安裝 APK。
2. 在 LSPosed 啟用模組，作用域選**動畫瘋**。
3. 重新啟動動畫瘋，在播放器開啟「播放設定」，模組的項目在面板底部。

## 建置

```
./gradlew assembleRelease
```

簽章金鑰來自環境變數 `BAE_ANDROID_KEYSTORE`、`BAE_ANDROID_KEY_ALIAS`、`BAE_ANDROID_KEYSTORE_PASSWORD`、`BAE_ANDROID_KEY_PASSWORD`（見 `tools/build-release.ps1`）；沒有時使用 debug 金鑰。

## 授權

[MIT](LICENSE)
