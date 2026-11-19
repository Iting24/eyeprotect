package com.example.eyeprotect.nav

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import com.example.eyeprotect.competition.CompetitionRoom
import com.example.eyeprotect.competition.CompetitionRoomMember
import com.example.eyeprotect.competition.CompetitionRoomRepository
import com.example.eyeprotect.competition.CreateRoomResult
import com.example.eyeprotect.competition.JoinRoomResult
import com.example.eyeprotect.competition.RoomActionResult
import com.example.eyeprotect.competition.SyncScoreResult
import com.example.eyeprotect.competition.defaultCreateRoomRequest
import com.example.eyeprotect.competition.nicknameValidationMessage
import com.example.eyeprotect.competition.normalizeNickname
import com.example.eyeprotect.data.db.MonitoringReportDao
import com.example.eyeprotect.monitoring.CompetitionRanking
import com.example.eyeprotect.monitoring.MonitoringRankCalculator
import com.example.eyeprotect.monitoring.RankMetric
import com.example.eyeprotect.monitoring.RankTotals
import com.example.eyeprotect.monitoring.ReportPeriod
import com.example.eyeprotect.monitoring.ReportWindow
import com.example.eyeprotect.monitoring.RoomMemberScore
import com.example.eyeprotect.ui.theme.EyeDesignTokens
import com.example.eyeprotect.ui.theme.EyeprotectTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private val ReportMintHeader = Color(0xFFC8FFF5)
private val ReportMintBody = Color(0xFF55D7CB)
private val ReportTealDark = Color(0xFF208F83)
private val ReportInk = Color(0xFF1C1C1E)
private val ReportMuted = Color(0xFF6E777B)
private val ReportAmber = Color(0xFFFFC83D)
private val ReportPeach = Color(0xFFFFDDD0)
private val ReportBlue = Color(0xFFDDF2FF)
private val ReportLavender = Color(0xFFE8E0F5)
private val ReportGreen = Color(0xFFD7F3DE)

@AndroidEntryPoint
class RankReportFragment : Fragment() {
    @Inject lateinit var reportDao: MonitoringReportDao
    @Inject lateinit var roomRepository: CompetitionRoomRepository

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setContent {
                EyeprotectTheme {
                    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                    var authMessage by remember { mutableStateOf("正在連接 Firebase…") }
                    var currentUid by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(Unit) {
                        roomRepository.ensureSignedIn()
                            .onSuccess {
                                currentUid = it
                                authMessage = "已連接雲端"
                            }
                            .onFailure {
                                currentUid = null
                                authMessage = "匿名登入失敗：" + (it.message ?: "未知錯誤")
                            }
                        while (true) {
                            delay(30_000L)
                            nowMs = System.currentTimeMillis()
                        }
                    }

                    var period by remember { mutableStateOf(ReportPeriod.DAY) }
                    val deviceZone = ZoneId.systemDefault()
                    val earliestStart = minOf(
                        ReportWindow.containing(nowMs, deviceZone, ReportPeriod.WEEK).startMs,
                        ReportWindow.containing(nowMs, deviceZone, ReportPeriod.MONTH).startMs,
                    ) - 86_400_000L
                    val events by remember(reportDao, earliestStart) {
                        reportDao.observeStatusEventsSince(earliestStart)
                    }.collectAsState(initial = emptyList())
                    val rooms by remember(roomRepository) {
                        roomRepository.observeMyRooms().catch { emit(emptyList()) }
                    }.collectAsState(initial = emptyList())
                    var selectedRoomId by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(rooms) {
                        if (rooms.none { it.id == selectedRoomId }) selectedRoomId = rooms.firstOrNull()?.id
                    }

                    val window = ReportWindow.containing(nowMs, deviceZone, period)
                    val totals = remember(events, window) { MonitoringRankCalculator.totals(events, window) }
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        ReportContent(
                            period = period,
                            onPeriodChange = { period = it },
                            totals = totals,
                            nowMs = nowMs,
                            events = events,
                            authMessage = authMessage,
                            currentUid = currentUid,
                            rooms = rooms,
                            selectedRoomId = selectedRoomId,
                            onSelectRoom = { selectedRoomId = it },
                            roomRepository = roomRepository,
                        )
                    }
                }
            }
        }
}

@Composable
private fun ReportContent(
    period: ReportPeriod,
    onPeriodChange: (ReportPeriod) -> Unit,
    totals: RankTotals,
    nowMs: Long,
    events: List<com.example.eyeprotect.data.db.MonitoringStatusEventEntity>,
    authMessage: String,
    currentUid: String?,
    rooms: List<CompetitionRoom>,
    selectedRoomId: String?,
    onSelectRoom: (String) -> Unit,
    roomRepository: CompetitionRoomRepository,
) {
    var dialog by remember { mutableStateOf<RoomDialog?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var actionRoom by remember { mutableStateOf<CompetitionRoom?>(null) }
    var nicknameBeforeEditing by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val selectedRoom = rooms.firstOrNull { it.id == selectedRoomId }

    when (dialog) {
        RoomDialog.CREATE -> CreateRoomDialog(
            isSubmitting = submitting,
            errorMessage = dialogError,
            onDismiss = { if (!submitting) dialog = null },
            onCreate = { roomName ->
                dialogError = null
                submitting = true
                scope.launch {
                    when (val result = roomRepository.createRoom(defaultCreateRoomRequest(roomName))) {
                        is CreateRoomResult.Success -> {
                            statusMessage = "房間已建立。邀請碼：" + result.room.inviteCode
                            onSelectRoom(result.room.id)
                            dialog = null
                        }
                        is CreateRoomResult.Failure -> dialogError = result.message
                    }
                    submitting = false
                }
            },
        )
        RoomDialog.JOIN -> JoinRoomDialog(
            isSubmitting = submitting,
            errorMessage = dialogError,
            onDismiss = { if (!submitting) dialog = null },
            onJoin = { inviteCode ->
                dialogError = null
                submitting = true
                scope.launch {
                    when (val result = roomRepository.joinRoom(inviteCode)) {
                        is JoinRoomResult.Success -> {
                            statusMessage = "已加入「" + result.room.name + "」。"
                            onSelectRoom(result.room.id)
                            dialog = null
                        }
                        is JoinRoomResult.Failure -> dialogError = result.message
                    }
                    submitting = false
                }
            },
        )
        RoomDialog.RENAME -> actionRoom?.let { room ->
            RenameNicknameDialog(
                initialNickname = nicknameBeforeEditing,
                isSubmitting = submitting,
                errorMessage = dialogError,
                onDismiss = { if (!submitting) dialog = null },
                onSave = { nickname ->
                    dialogError = null
                    submitting = true
                    scope.launch {
                        when (val result = roomRepository.updateNickname(room.id, nickname)) {
                            RoomActionResult.Success -> {
                                statusMessage = "暱稱已更新為「${normalizeNickname(nickname)}」。"
                                dialog = null
                            }
                            is RoomActionResult.Failure -> dialogError = result.message
                        }
                        submitting = false
                    }
                },
            )
        }
        RoomDialog.LEAVE -> actionRoom?.let { room ->
            RoomConfirmationDialog(
                title = "退出競賽房間？",
                message = "退出「${room.name}」後，你的房間排名資料會一併移除。之後仍可使用邀請碼重新加入。",
                confirmLabel = "退出房間",
                isSubmitting = submitting,
                errorMessage = dialogError,
                onDismiss = { if (!submitting) dialog = null },
                onConfirm = {
                    dialogError = null
                    submitting = true
                    scope.launch {
                        when (val result = roomRepository.leaveRoom(room.id)) {
                            RoomActionResult.Success -> {
                                statusMessage = "你已退出「${room.name}」。"
                                dialog = null
                            }
                            is RoomActionResult.Failure -> dialogError = result.message
                        }
                        submitting = false
                    }
                },
            )
        }
        RoomDialog.DELETE -> actionRoom?.let { room ->
            RoomConfirmationDialog(
                title = "刪除整個房間？",
                message = "「${room.name}」的邀請碼、成員與排名資料都會永久刪除，這個動作無法復原。",
                confirmLabel = "永久刪除",
                isSubmitting = submitting,
                errorMessage = dialogError,
                onDismiss = { if (!submitting) dialog = null },
                onConfirm = {
                    dialogError = null
                    submitting = true
                    scope.launch {
                        when (val result = roomRepository.deleteRoom(room.id)) {
                            RoomActionResult.Success -> {
                                statusMessage = "「${room.name}」已刪除。"
                                dialog = null
                            }
                            is RoomActionResult.Failure -> dialogError = result.message
                        }
                        submitting = false
                    }
                },
            )
        }
        null -> Unit
    }

    val latestRooms by rememberUpdatedState(rooms)
    val latestEvents by rememberUpdatedState(events)
    val roomIds = rooms.map { it.id }
    LaunchedEffect(roomRepository, roomIds) {
        if (rooms.isEmpty()) return@LaunchedEffect
        delay(1_000L)
        while (true) {
            val syncNowMs = System.currentTimeMillis()
            latestRooms.forEach { room ->
                val zone = runCatching { ZoneId.of(room.timezoneId) }.getOrDefault(ZoneId.systemDefault())
                val roomWindow = ReportWindow.containing(syncNowMs, zone, ReportPeriod.DAY)
                val roomTotals = MonitoringRankCalculator.totals(latestEvents, roomWindow)
                val localDate = Instant.ofEpochMilli(syncNowMs).atZone(zone).toLocalDate()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                syncMessage = "正在同步「${room.name}」的今日有效時長…"
                when (val result = roomRepository.syncDailyScore(room, localDate, roomTotals)) {
                    SyncScoreResult.Success -> {
                        syncMessage = "已同步今日有效時長：${formatDuration(roomTotals.effectiveMs)}"
                    }
                    is SyncScoreResult.Failure -> syncMessage = result.message
                }
            }
            delay(15_000L)
        }
    }

    val rankingZone = selectedRoom?.let {
        runCatching { ZoneId.of(it.timezoneId) }.getOrDefault(ZoneId.systemDefault())
    }
    val rankingDate = rankingZone?.let {
        Instant.ofEpochMilli(nowMs).atZone(it).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
    val remoteScores by remember(selectedRoom?.id, rankingDate, roomRepository) {
        if (selectedRoom == null || rankingDate == null) flowOf(emptyList())
        else roomRepository.observeDailyScores(selectedRoom.id, rankingDate).catch { error ->
            syncMessage = "讀取房間排名失敗：" + (error.message ?: "未知錯誤")
            emit(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val roomMembers by remember(selectedRoom?.id, roomRepository) {
        if (selectedRoom == null) flowOf(emptyList())
        else roomRepository.observeRoomMembers(selectedRoom.id).catch { error ->
            syncMessage = "讀取房間成員失敗：" + (error.message ?: "未知錯誤")
            emit(emptyList())
        }
    }.collectAsState(initial = emptyList())
    val localRoomTotals = remember(events, rankingZone, nowMs) {
        if (rankingZone == null) RankTotals()
        else MonitoringRankCalculator.totals(
            events,
            ReportWindow.containing(nowMs, rankingZone, ReportPeriod.DAY),
        )
    }
    val myRemoteScore = remoteScores.firstOrNull { it.uid == currentUid }
    val myRoomMember = roomMembers.firstOrNull { it.uid == currentUid }
    var selectedRankMetric by remember { mutableStateOf(RankMetric.EFFECTIVE_TIME) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(ReportMintBody),
    ) {
        ReportHero(authMessage = authMessage)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .align(Alignment.CenterHorizontally)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PersonalReportCard(
                period = period,
                onPeriodChange = onPeriodChange,
                totals = totals,
            )

            CompetitionHeader(
                onCreate = {
                    statusMessage = null
                    dialogError = null
                    dialog = RoomDialog.CREATE
                },
                onJoin = {
                    statusMessage = null
                    dialogError = null
                    dialog = RoomDialog.JOIN
                },
            )
            statusMessage?.let { FriendlyMessageCard(it, ReportGreen) }
            syncMessage?.let { FriendlyMessageCard(it, ReportBlue) }

            if (rooms.isEmpty()) {
                EmptyRoomsCard()
            } else {
                RoomSelector(
                    rooms = rooms,
                    selectedRoomId = selectedRoomId,
                    onSelectRoom = onSelectRoom,
                )
            }

            selectedRoom?.let { room ->
                SelectedRoomCard(
                    room = room,
                    localTotals = localRoomTotals,
                    remoteTotals = myRemoteScore?.totals,
                    isOwner = room.ownerUid == currentUid,
                    onRename = {
                        actionRoom = room
                        nicknameBeforeEditing = myRoomMember?.displayName.orEmpty()
                        dialogError = null
                        dialog = RoomDialog.RENAME
                    },
                    onLeave = {
                        actionRoom = room
                        dialogError = null
                        dialog = RoomDialog.LEAVE
                    },
                    onDelete = {
                        actionRoom = room
                        dialogError = null
                        dialog = RoomDialog.DELETE
                    },
                )
                RoomMembersCard(
                    members = roomMembers,
                    scores = remoteScores,
                    currentUid = currentUid,
                )
                LeaderboardCard(
                    remoteScores = remoteScores,
                    members = roomMembers,
                    currentUid = currentUid,
                    selectedMetric = selectedRankMetric,
                    onSelectMetric = { selectedRankMetric = it },
                )
            }

            FriendlyMessageCard(
                text = "小提醒：有效時長只計入同時辨識到臉與姿勢的區間。暫停或離開鏡頭時不會計時，事件重複觸發也會自動合併。",
                background = Color.White.copy(alpha = 0.78f),
            )
            Spacer(modifier = Modifier.height(90.dp))
        }
    }
}

@Composable
private fun ReportHero(authMessage: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ReportMintHeader)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text("★", fontSize = 32.sp, color = ReportAmber, fontWeight = FontWeight.Black)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "護眼挑戰賽",
                    style = EyeDesignTokens.typography.pageTitle,
                    color = ReportInk,
                )
                Text(
                    "和夥伴一起累積健康用眼時間",
                    style = EyeDesignTokens.typography.body,
                    color = ReportMuted,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.86f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        if (authMessage == "已連接雲端") "● 雲端已連線" else authMessage,
                        style = EyeDesignTokens.typography.caption,
                        color = if (authMessage == "已連接雲端") ReportTealDark else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalReportCard(
    period: ReportPeriod,
    onPeriodChange: (ReportPeriod) -> Unit,
    totals: RankTotals,
) {
    CuteCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("我的護眼紀錄", style = EyeDesignTokens.typography.sectionTitle, color = ReportInk)
                    Text(periodDescription(period), style = EyeDesignTokens.typography.bodySmall, color = ReportMuted)
                }
                Box(
                    modifier = Modifier.clip(CircleShape).background(ReportGreen).padding(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("眼", fontWeight = FontWeight.Bold, color = ReportTealDark)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFF1F5F4))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ReportPeriod.entries.forEach { option ->
                    val selected = option == period
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onPeriodChange(option) }
                            .background(if (selected) ReportInk else Color.Transparent)
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (option) {
                                ReportPeriod.DAY -> "今天"
                                ReportPeriod.WEEK -> "本週"
                                ReportPeriod.MONTH -> "本月"
                            },
                            style = EyeDesignTokens.typography.bodyStrong,
                            color = if (selected) Color.White else ReportMuted,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportMetricTile(
                    modifier = Modifier.weight(1f),
                    badge = "時",
                    title = "有效監測",
                    value = formatDuration(totals.effectiveMs),
                    background = ReportPeach,
                )
                ReportMetricTile(
                    modifier = Modifier.weight(1f),
                    badge = "背",
                    title = "駝背比例",
                    value = totals.slouchRatio?.let { formatNumber(it * 100) + "%" } ?: "資料不足",
                    background = ReportLavender,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReportMetricTile(
                    modifier = Modifier.weight(1f),
                    badge = "眼",
                    title = "瞇眼頻率",
                    value = totals.squintPerHour?.let { formatNumber(it) + " 次／時" } ?: "資料不足",
                    background = ReportGreen,
                )
                ReportMetricTile(
                    modifier = Modifier.weight(1f),
                    badge = "近",
                    title = "過近頻率",
                    value = totals.tooClosePerHour?.let { formatNumber(it) + " 次／時" } ?: "資料不足",
                    background = ReportBlue,
                )
            }
            Text(
                "原始紀錄　駝背 ${formatDuration(totals.slouchMs)}・瞇眼 ${totals.squintEvents} 次・過近 ${totals.tooCloseEvents} 次",
                style = EyeDesignTokens.typography.caption,
                color = ReportMuted,
            )
        }
    }
}

@Composable
private fun ReportMetricTile(
    modifier: Modifier,
    badge: String,
    title: String,
    value: String,
    background: Color,
) {
    Column(
        modifier = modifier
            .height(106.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(badge, fontSize = 13.sp, fontWeight = FontWeight.Black, color = ReportInk)
            }
            Text(title, style = EyeDesignTokens.typography.bodySmall, color = ReportMuted)
        }
        Text(value, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Black, color = ReportInk)
    }
}

@Composable
private fun CompetitionHeader(onCreate: () -> Unit, onJoin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("競賽房間", style = EyeDesignTokens.typography.sectionTitle, color = ReportInk)
            Text("揪朋友一起守護眼睛，每天進步一點點。", style = EyeDesignTokens.typography.body, color = ReportInk.copy(alpha = 0.72f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onCreate,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReportInk, contentColor = Color.White),
            ) { Text("＋ 建立房間", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onJoin,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.5.dp, ReportInk),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = ReportInk),
            ) { Text("輸入邀請碼", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun FriendlyMessageCard(text: String, background: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .border(BorderStroke(0.5.dp, Color.White.copy(alpha = 0.7f)), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Text(text, style = EyeDesignTokens.typography.bodySmall, color = ReportInk.copy(alpha = 0.78f))
    }
}

@Composable
private fun EmptyRoomsCard() {
    CuteCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(60.dp).clip(CircleShape).background(ReportPeach),
                contentAlignment = Alignment.Center,
            ) { Text("★", fontSize = 28.sp, color = Color(0xFFE97A38)) }
            Text("建立你的第一個挑戰房間", style = EyeDesignTokens.typography.bodyStrong, color = ReportInk)
            Text("分享邀請碼後，朋友就能一起加入。", style = EyeDesignTokens.typography.bodySmall, color = ReportMuted)
        }
    }
}

@Composable
private fun RoomSelector(
    rooms: List<CompetitionRoom>,
    selectedRoomId: String?,
    onSelectRoom: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("我的房間", style = EyeDesignTokens.typography.bodyStrong, color = ReportInk)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rooms.forEach { room ->
                val selected = room.id == selectedRoomId
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSelectRoom(room.id) }
                        .background(if (selected) ReportInk else Color.White)
                        .border(BorderStroke(1.dp, if (selected) ReportInk else Color.White), RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        room.name,
                        style = EyeDesignTokens.typography.bodyStrong,
                        color = if (selected) Color.White else ReportInk,
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedRoomCard(
    room: CompetitionRoom,
    localTotals: RankTotals,
    remoteTotals: RankTotals?,
    isOwner: Boolean,
    onRename: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit,
) {
    val progress = (localTotals.effectiveMs.toFloat() / MonitoringRankCalculator.MIN_RANK_MS.toFloat()).coerceIn(0f, 1f)
    val qualified = localTotals.effectiveMs >= MonitoringRankCalculator.MIN_RANK_MS
    CuteCard(background = ReportTealDark, border = Color.White.copy(alpha = 0.28f)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("今日挑戰", style = EyeDesignTokens.typography.caption, color = Color.White.copy(alpha = 0.72f))
                    Text(room.name, style = EyeDesignTokens.typography.cardTitle, color = Color.White)
                }
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("邀請碼", fontSize = 11.sp, color = ReportMuted)
                        Text(room.inviteCode, fontSize = 18.sp, fontWeight = FontWeight.Black, color = ReportInk)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("我的有效監測", style = EyeDesignTokens.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
                    Text(
                        if (qualified) "已取得參賽資格" else qualificationSuffix(localTotals.effectiveMs).removeSurrounding("（", "）"),
                        style = EyeDesignTokens.typography.bodySmall,
                        color = if (qualified) Color(0xFFFFE07B) else Color.White,
                    )
                }
                Text(formatDuration(localTotals.effectiveMs), fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(999.dp)),
                    color = ReportAmber,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Text(
                    "雲端已同步：${remoteTotals?.effectiveMs?.let(::formatDuration) ?: "等待第一次同步"}",
                    style = EyeDesignTokens.typography.caption,
                    color = Color.White.copy(alpha = 0.74f),
                )
            }
            Text("排行榜日期依 ${room.timezoneId} 計算", style = EyeDesignTokens.typography.caption, color = Color.White.copy(alpha = 0.62f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                OutlinedButton(
                    onClick = onRename,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("更換暱稱", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = if (isOwner) onDelete else onLeave,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFD0C7)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFFFFE9E4).copy(alpha = 0.16f),
                        contentColor = Color(0xFFFFE3DD),
                    ),
                ) {
                    Text(if (isOwner) "刪除房間" else "退出房間", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RoomMembersCard(
    members: List<CompetitionRoomMember>,
    scores: List<com.example.eyeprotect.competition.RemoteDailyScore>,
    currentUid: String?,
) {
    val scoresByUid = scores.associateBy { it.uid }
    CuteCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("挑戰夥伴", style = EyeDesignTokens.typography.sectionTitle, color = ReportInk)
                    Text("一起累積今天的護眼時間", style = EyeDesignTokens.typography.bodySmall, color = ReportMuted)
                }
                Box(modifier = Modifier.clip(CircleShape).background(ReportGreen).padding(horizontal = 11.dp, vertical = 6.dp)) {
                    Text("${members.size} 人", style = EyeDesignTokens.typography.bodyStrong, color = ReportTealDark)
                }
            }
            if (members.isEmpty()) {
                Text("正在把夥伴名單帶進來…", style = EyeDesignTokens.typography.body, color = ReportMuted)
            } else {
                members.forEachIndexed { index, member ->
                    val totals = scoresByUid[member.uid]?.totals
                    val isMe = member.uid == currentUid
                    val isOwner = member.role == "owner"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isMe) ReportBlue else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(if (isOwner) ReportAmber else ReportGreen),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (isOwner) "★" else member.displayName.takeLast(2),
                                fontWeight = FontWeight.Black,
                                color = ReportInk,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                member.displayName + if (isMe) "（你）" else "",
                                style = EyeDesignTokens.typography.bodyStrong,
                                color = ReportInk,
                            )
                            Text(
                                totals?.let { "今日 ${formatDuration(it.effectiveMs)}" } ?: "今天還沒開始監測",
                                style = EyeDesignTokens.typography.bodySmall,
                                color = ReportMuted,
                            )
                        }
                        MemberStatusPill(isOwner = isOwner, effectiveMs = totals?.effectiveMs)
                    }
                    if (index != members.lastIndex) {
                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFF0F0F2)))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberStatusPill(isOwner: Boolean, effectiveMs: Long?) {
    val (label, background, foreground) = when {
        isOwner -> Triple("房主", ReportPeach, Color(0xFFE46F35))
        effectiveMs != null && effectiveMs >= MonitoringRankCalculator.MIN_RANK_MS -> Triple("已參賽", ReportGreen, ReportTealDark)
        else -> Triple("加油中", Color(0xFFF2F2F7), ReportMuted)
    }
    Box(modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(label, style = EyeDesignTokens.typography.caption, color = foreground, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeaderboardCard(
    remoteScores: List<com.example.eyeprotect.competition.RemoteDailyScore>,
    members: List<CompetitionRoomMember>,
    currentUid: String?,
    selectedMetric: RankMetric,
    onSelectMetric: (RankMetric) -> Unit,
) {
    val memberNames = members.associate { it.uid to it.displayName }
    val rankedMembers = remoteScores.map {
        RoomMemberScore(it.uid, memberNames[it.uid] ?: it.displayName, it.totals)
    }
    val ranking = CompetitionRanking.ranked(rankedMembers, selectedMetric)
    CuteCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("今日排行榜", style = EyeDesignTokens.typography.sectionTitle, color = ReportInk)
                Text(rankMetricDescription(selectedMetric), style = EyeDesignTokens.typography.bodySmall, color = ReportMuted)
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                RankMetric.entries.forEach { metric ->
                    val selected = metric == selectedMetric
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { onSelectMetric(metric) }
                            .background(if (selected) ReportInk else Color(0xFFF2F2F7))
                            .padding(horizontal = 13.dp, vertical = 8.dp),
                    ) {
                        Text(
                            rankMetricShortLabel(metric),
                            style = EyeDesignTokens.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Color.White else ReportMuted,
                        )
                    }
                }
            }
            if (ranking.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ReportBlue).padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("再累積一下就能上榜", style = EyeDesignTokens.typography.bodyStrong, color = ReportInk)
                    Text(
                        if (remoteScores.isEmpty()) "今天還沒有雲端排名資料" else "成員需先完成 30 分鐘有效監測",
                        style = EyeDesignTokens.typography.bodySmall,
                        color = ReportMuted,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                ranking.forEachIndexed { index, member ->
                    val isMe = member.memberId == currentUid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isMe) ReportGreen else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(11.dp),
                    ) {
                        RankPositionBadge(index + 1)
                        Text(
                            member.displayName + if (isMe) "（你）" else "",
                            modifier = Modifier.weight(1f),
                            style = EyeDesignTokens.typography.bodyStrong,
                            color = ReportInk,
                        )
                        Text(
                            metricValue(member.totals, selectedMetric),
                            style = EyeDesignTokens.typography.bodyStrong,
                            fontWeight = FontWeight.Black,
                            color = ReportInk,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RankPositionBadge(position: Int) {
    val color = when (position) {
        1 -> ReportAmber
        2 -> Color(0xFFD9E1E5)
        3 -> ReportPeach
        else -> Color(0xFFF2F2F7)
    }
    Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(position.toString(), fontWeight = FontWeight.Black, color = ReportInk)
    }
}

@Composable
private fun CuteCard(
    background: Color = Color.White.copy(alpha = 0.97f),
    border: Color = Color.White.copy(alpha = 0.75f),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black.copy(alpha = 0.05f))
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .border(BorderStroke(0.5.dp, border), RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) { content() }
}

private fun periodDescription(period: ReportPeriod): String = when (period) {
    ReportPeriod.DAY -> "今天的護眼表現"
    ReportPeriod.WEEK -> "本週一至週日的累積"
    ReportPeriod.MONTH -> "本月原始紀錄加總後計算"
}

private fun rankMetricShortLabel(metric: RankMetric): String = when (metric) {
    RankMetric.EFFECTIVE_TIME -> "有效時長"
    RankMetric.SLOUCH_RATIO -> "駝背比例"
    RankMetric.SQUINT_FREQUENCY -> "瞇眼頻率"
    RankMetric.TOO_CLOSE_FREQUENCY -> "過近頻率"
}

private fun rankMetricDescription(metric: RankMetric): String = when (metric) {
    RankMetric.EFFECTIVE_TIME -> "有效監測越久，排名越前面"
    RankMetric.SLOUCH_RATIO -> "駝背比例越低，排名越前面"
    RankMetric.SQUINT_FREQUENCY -> "每小時瞇眼事件越少，排名越前面"
    RankMetric.TOO_CLOSE_FREQUENCY -> "每小時距離過近事件越少，排名越前面"
}

private enum class RoomDialog { CREATE, JOIN, RENAME, LEAVE, DELETE }

@Composable
private fun CreateRoomDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var roomName by remember { mutableStateOf("") }
    val trimmedName = roomName.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立競賽房間") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = roomName,
                    onValueChange = { if (it.length <= 40) roomName = it },
                    label = { Text("房間名稱") },
                    placeholder = { Text("例如：護眼挑戰賽") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("排名日期將使用建立者目前時區：" + ZoneId.systemDefault().id)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(trimmedName) },
                enabled = trimmedName.isNotEmpty() && !isSubmitting,
            ) { SubmitButtonContent(isSubmitting, "建立") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        },
    )
}

@Composable
private fun JoinRoomDialog(
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onJoin: (String) -> Unit,
) {
    var inviteCode by remember { mutableStateOf("") }
    val normalized = inviteCode.trim().uppercase()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入競賽房間") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = inviteCode,
                    onValueChange = { value ->
                        inviteCode = value.uppercase().filter { it.isLetterOrDigit() }.take(6)
                    },
                    label = { Text("6 位邀請碼") },
                    placeholder = { Text("例如：A2BC3D") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onJoin(normalized) },
                enabled = normalized.length == 6 && !isSubmitting,
            ) { SubmitButtonContent(isSubmitting, "加入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        },
    )
}

@Composable
private fun RenameNicknameDialog(
    initialNickname: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var nickname by remember(initialNickname) { mutableStateOf(initialNickname) }
    val normalized = normalizeNickname(nickname)
    val validationMessage = nicknameValidationMessage(nickname)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更換我的暱稱") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("這個暱稱會顯示在目前房間的成員名單與排行榜。")
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { if (it.length <= 16) nickname = it },
                    label = { Text("暱稱") },
                    placeholder = { Text("輸入 2～16 個字") },
                    singleLine = true,
                    enabled = !isSubmitting,
                    isError = nickname.isNotEmpty() && validationMessage != null,
                    supportingText = {
                        Text(
                            when {
                                nickname.isNotEmpty() && validationMessage != null -> validationMessage
                                else -> "${normalized.length}/16"
                            }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(normalized) },
                enabled = validationMessage == null && normalized != initialNickname && !isSubmitting,
            ) { SubmitButtonContent(isSubmitting, "儲存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        },
    )
}

@Composable
private fun RoomConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { SubmitButtonContent(isSubmitting, confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("取消") }
        },
    )
}

@Composable
private fun SubmitButtonContent(isSubmitting: Boolean, label: String) {
    if (isSubmitting) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    } else {
        Text(label)
    }
}

private fun metricValue(totals: RankTotals, metric: RankMetric): String = when (metric) {
    RankMetric.EFFECTIVE_TIME -> formatDuration(totals.effectiveMs)
    RankMetric.SLOUCH_RATIO -> formatNumber(totals.slouchRatio!! * 100) + "%"
    RankMetric.SQUINT_FREQUENCY -> formatNumber(totals.squintPerHour!!) + " 次／小時"
    RankMetric.TOO_CLOSE_FREQUENCY -> formatNumber(totals.tooClosePerHour!!) + " 次／小時"
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes >= 60) (minutes / 60).toString() + " 小時 " + (minutes % 60) + " 分鐘"
    else minutes.toString() + " 分鐘"
}

private fun qualificationSuffix(effectiveMs: Long): String =
    if (effectiveMs >= MonitoringRankCalculator.MIN_RANK_MS) {
        "（已達參賽資格）"
    } else {
        val remainingMs = MonitoringRankCalculator.MIN_RANK_MS - effectiveMs
        val remainingMinutes = (remainingMs + 59_999L) / 60_000L
        "（還差 $remainingMinutes 分鐘）"
    }

private fun formatNumber(value: Double): String = String.format(Locale.TAIWAN, "%.1f", value)
