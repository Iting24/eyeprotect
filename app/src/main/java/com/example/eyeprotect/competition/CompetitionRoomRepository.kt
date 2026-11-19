package com.example.eyeprotect.competition

import com.example.eyeprotect.monitoring.RankTotals
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

data class CreateCompetitionRoomRequest(
    val name: String,
    val timezoneId: String,
)

data class CompetitionRoom(
    val id: String,
    val name: String,
    val inviteCode: String,
    val timezoneId: String,
    val ownerUid: String = "",
)

data class RemoteDailyScore(
    val uid: String,
    val displayName: String,
    val totals: RankTotals,
)

data class CompetitionRoomMember(
    val uid: String,
    val displayName: String,
    val role: String,
    val joinedAtEpochMs: Long?,
)

sealed interface CreateRoomResult {
    data class Success(val room: CompetitionRoom) : CreateRoomResult
    data class Failure(val message: String) : CreateRoomResult
}
sealed interface JoinRoomResult {
    data class Success(val room: CompetitionRoom) : JoinRoomResult
    data class Failure(val message: String) : JoinRoomResult
}

sealed interface SyncScoreResult {
    data object Success : SyncScoreResult
    data class Failure(val message: String) : SyncScoreResult
}

sealed interface RoomActionResult {
    data object Success : RoomActionResult
    data class Failure(val message: String) : RoomActionResult
}

interface CompetitionRoomRepository {
    suspend fun ensureSignedIn(): Result<String>
    fun observeMyRooms(): Flow<List<CompetitionRoom>>
    fun observeRoomMembers(roomId: String): Flow<List<CompetitionRoomMember>>
    fun observeDailyScores(roomId: String, localDate: String): Flow<List<RemoteDailyScore>>
    suspend fun createRoom(request: CreateCompetitionRoomRequest): CreateRoomResult
    suspend fun joinRoom(inviteCode: String): JoinRoomResult
    suspend fun updateNickname(roomId: String, nickname: String): RoomActionResult
    suspend fun leaveRoom(roomId: String): RoomActionResult
    suspend fun deleteRoom(roomId: String): RoomActionResult
    suspend fun syncDailyScore(
        room: CompetitionRoom,
        localDate: String,
        totals: RankTotals,
    ): SyncScoreResult
}

fun defaultCreateRoomRequest(name: String) = CreateCompetitionRoomRequest(
    name = name.trim(),
    timezoneId = ZoneId.systemDefault().id,
)

fun normalizeInviteCode(value: String): String =
    value.trim().uppercase().filter { it.isLetterOrDigit() }

fun normalizeNickname(value: String): String =
    value.trim().replace(Regex("\\s+"), " ")

fun nicknameValidationMessage(value: String): String? {
    val nickname = normalizeNickname(value)
    return when {
        nickname.length < 2 -> "暱稱至少需要 2 個字。"
        nickname.length > 16 -> "暱稱不可超過 16 個字。"
        nickname.any { it.isISOControl() } -> "暱稱含有無法使用的字元。"
        else -> null
    }
}
