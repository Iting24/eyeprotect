# EyeProtect（護眼監測）

使用前鏡頭 + 感測器做「距離 / 咪眼 / 姿勢 / 躺姿」偵測，並以語音、震動、通知（必要時可搭配螢幕遮罩）提醒你維持良好用眼習慣。

## 功能

- **距離提醒（太近）**：以雙眼關鍵點距離（正規化）估算靠近程度。
- **咪眼提醒**：使用 ML Kit 眼睛張開機率（`leftEyeOpenProbability/rightEyeOpenProbability`）判斷是否瞇眼。
- **駝背提醒**：用耳朵到肩膀的相對位置推估前傾/駝背程度。
- **不要躺著滑手機**：結合重力/姿態/陀螺儀與「近期有看到臉」的條件做躺姿判斷。
- **校正流程**：首次使用以 3 秒校正建立個人化門檻，降低誤報。
- **儀表板**：顯示即時指標、警告狀態與趨勢圖；可一鍵暫停/恢復監測。

## 使用流程（App 內）

1. **授權相機**（需要前鏡頭做即時分析）。
2. **完成校正（3 秒）**：保持舒適坐姿、臉與上半身（含肩膀）入鏡，盡量不要眨眼直到倒數結束。
3. **開啟無障礙服務**：到系統「無障礙」設定中啟用 EyeProtect，才能在背景持續提醒。
4. 回到 App **查看儀表板**並視需要調整「監測中 / 已暫停」。

## 開發與建置

### 需求

- Android Studio（建議最新版）
- JDK 17
- Android 裝置/模擬器（建議實機：前鏡頭 + 感測器更完整）

### 指令

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## 權限與隱私

- `CAMERA`：前鏡頭影像僅用於**本機即時**偵測距離/咪眼/姿勢。
- `BIND_ACCESSIBILITY_SERVICE`（由系統設定啟用）：用於在背景維持監測與提醒；本服務設定為不擷取視窗內容（`canRetrieveWindowContent=false`）。
- `VIBRATE`：警告震動回饋。
- `SYSTEM_ALERT_WINDOW` / 無障礙 Overlay：用於必要時顯示遮罩效果（例如距離過近）。

本專案程式碼未包含影像上傳或雲端儲存邏輯；偵測使用 Google ML Kit（模型可能由 Google Play 服務下載/更新）。

## 專案結構（重點檔案）

- `app/src/main/java/com/example/eyeprotect/MainActivity.kt`：權限/校正/儀表板（Compose UI）。
- `app/src/main/java/com/example/eyeprotect/EyeHealthAccessibilityService.kt`：背景監測、相機分析、語音/震動/通知與躺姿偵測。
- `app/src/main/java/com/example/eyeprotect/PostureAndEyeDetector.kt`：距離/咪眼/駝背等指標計算與警告判定。

## Troubleshooting

- **校正失敗**：確保光線充足、臉部與肩膀入鏡、鏡頭對焦清楚；校正期間盡量不要眨眼。
- **背景偵測被系統停掉**：確認無障礙服務仍啟用，並檢查電池最佳化/背景限制設定。
- **沒有語音**：確認系統 TTS 可用且已安裝中文語音（不同裝置/引擎支援度不同）。

---

> 這是一個原型/實驗性專案；偵測結果可能受光線、鏡頭角度與個人體態影響，建議依實際情況調整與驗證。
