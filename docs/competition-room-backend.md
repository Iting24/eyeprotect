# 競賽房間後端需求

目前 App 已串接 Firebase Authentication 匿名登入與 Cloud Firestore。「排名報表」頁會從本機 Room 統計原始監測資料，再把每日彙總同步到使用者加入的競賽房間。

已提供：

1. **帳號與房間服務**：匿名登入、建立房間、6 位邀請碼、加入房間、成員名單、每房間暱稱、退出房間及房主刪除房間。
2. **安全的分數同步**：每位成員按房間和日期提交 `effectiveMs`、`slouchMs`、`squintEvents`、`tooCloseEvents`。只傳彙總數字，不上傳相機畫面。
3. **即時排名**：以房間時區的今日日期顯示四種排序；有效監測未滿 30 分鐘者不列名次，比例與頻率皆由原始總量重算。
4. **安全規則**：成員只能修改自己的暱稱與每日分數；一般成員只能退出自己；只有房主能刪除整個房間。
5. **資料清理**：退出時移除該成員的房間分數；刪除房間時停用邀請碼並清除成員、每日分數及使用者房間索引。

專案設定使用 `eyeprotect-b14ec`，Android Firebase 設定位於 `app/google-services.json`，Firestore 規則位於根目錄 `firestore.rules`。



## 建議的分階段實作

### 第一階段：建立房間入口（已完成）

- 報表頁提供「建立房間」按鈕。
- 表單收集房間名稱，日期界線採建立者裝置時區。
- UI 經由 `CompetitionRoomRepository` 呼叫後端，不把本機假資料當成成功結果。
- 未設定雲端時會明確提示需要 Firebase。

### 第二階段：Firebase 基礎連線（已完成）

已完成以下 Firebase 設定：

1. 建立 Firebase 專案。
2. 新增 Android App，package name 使用 `com.example.eyeprotect`。
3. 下載 `google-services.json` 並放到 `app/google-services.json`。
4. 啟用 Firebase Authentication；原型可先用匿名登入，正式版再加入 Google 或 Email 登入。
5. 建立 Cloud Firestore。

App 端已實作：

- `FirebaseCompetitionRoomRepository.createRoom()`、`joinRoom()`、`updateNickname()`、`leaveRoom()` 與 `deleteRoom()`。
- 產生不可預測的邀請碼。
- 以 Firestore transaction 同時建立房間與房主成員資料。
- 將房間 ID 存在匿名登入帳號下，讓同一 App 安裝可持續讀取。
- 建立或加入房間後，即時顯示遠端成員與排行榜。

### 建議資料結構

```
rooms/{roomId}
  name
  ownerUid
  inviteCode
  timezoneId
  createdAt
  status

rooms/{roomId}/members/{uid}
  displayName
  joinedAt
  role

rooms/{roomId}/dailyScores/{yyyy-MM-dd_uid}
  uid
  localDate
  effectiveMs
  slouchMs
  squintEvents
  tooCloseEvents
  updatedAt
  schemaVersion
```

排行榜比例與頻率由原始總量計算；Firestore Security Rules 必須限制使用者只能更新自己的每日資料。正式競賽若需要防作弊，需以 Cloud Functions 驗證異常值與更新速率。

> 目前採匿名登入。解除安裝、清除 App 資料或更換手機可能產生新的 UID；正式公開版本建議加入 Google 登入或帳號綁定，讓玩家可跨裝置恢復身分。
