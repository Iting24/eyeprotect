package com.example.eyeprotect.competition

import com.example.eyeprotect.monitoring.RankTotals
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentReference
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class FirebaseCompetitionRoomRepository @Inject constructor() : CompetitionRoomRepository {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val random = SecureRandom()
    private val lastUploaded = ConcurrentHashMap<String, RankTotals>()
    private val roomDisplayNames = ConcurrentHashMap<String, String>()

    override suspend fun ensureSignedIn(): Result<String> = runCatching {
        auth.currentUser?.uid
            ?: auth.signInAnonymously().awaitTask().user?.uid
            ?: error("匿名登入未回傳使用者")
    }

    override fun observeMyRooms(): Flow<List<CompetitionRoom>> = callbackFlow {
        var registration: ListenerRegistration? = null
        val authListener = FirebaseAuth.AuthStateListener { currentAuth ->
            registration?.remove()
            registration = null
            val uid = currentAuth.currentUser?.uid
            if (uid == null) {
                trySend(emptyList())
            } else {
                registration = firestore.collection("users").document(uid).collection("rooms")
                    .addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            close(error)
                            return@addSnapshotListener
                        }
                        val rooms = snapshot?.documents.orEmpty().mapNotNull { document ->
                            val name = document.getString("name") ?: return@mapNotNull null
                            CompetitionRoom(
                                id = document.id,
                                name = name,
                                inviteCode = document.getString("inviteCode").orEmpty(),
                                timezoneId = document.getString("timezoneId") ?: ZoneId.systemDefault().id,
                                ownerUid = document.getString("ownerUid").orEmpty(),
                            )
                        }.sortedBy { it.name.lowercase() }
                        trySend(rooms)
                    }
            }
        }
        auth.addAuthStateListener(authListener)
        awaitClose {
            registration?.remove()
            auth.removeAuthStateListener(authListener)
        }
    }

    override fun observeDailyScores(roomId: String, localDate: String): Flow<List<RemoteDailyScore>> =
        callbackFlow {
            val registration = firestore.collection("rooms").document(roomId)
                .collection("dailyScores")
                .whereEqualTo("localDate", localDate)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@addSnapshotListener
                    }
                    val scores = snapshot?.documents.orEmpty().mapNotNull { document ->
                        val uid = document.getString("uid") ?: return@mapNotNull null
                        RemoteDailyScore(
                            uid = uid,
                            displayName = document.getString("displayName") ?: participantName(uid),
                            totals = RankTotals(
                                effectiveMs = document.getLong("effectiveMs")?.coerceAtLeast(0L) ?: 0L,
                                slouchMs = document.getLong("slouchMs")?.coerceAtLeast(0L) ?: 0L,
                                squintEvents = document.getLong("squintEvents")?.coerceAtLeast(0L)?.toInt() ?: 0,
                                tooCloseEvents = document.getLong("tooCloseEvents")?.coerceAtLeast(0L)?.toInt() ?: 0,
                            ),
                        )
                    }
                    trySend(scores)
                }
            awaitClose { registration.remove() }
        }

    override fun observeRoomMembers(roomId: String): Flow<List<CompetitionRoomMember>> = callbackFlow {
        val registration = firestore.collection("rooms").document(roomId)
            .collection("members")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val members = snapshot?.documents.orEmpty().mapNotNull { document ->
                    val uid = document.getString("uid") ?: return@mapNotNull null
                    CompetitionRoomMember(
                        uid = uid,
                        displayName = document.getString("displayName") ?: participantName(uid),
                        role = document.getString("role") ?: "member",
                        joinedAtEpochMs = document.getTimestamp("joinedAt")?.toDate()?.time,
                    )
                }.sortedWith(
                    compareBy<CompetitionRoomMember> { if (it.role == "owner") 0 else 1 }
                        .thenBy { it.joinedAtEpochMs ?: Long.MAX_VALUE }
                        .thenBy { it.displayName.lowercase() }
                )
                auth.currentUser?.uid?.let { currentUid ->
                    members.firstOrNull { it.uid == currentUid }?.let { me ->
                        roomDisplayNames[roomId] = me.displayName
                    }
                }
                trySend(members)
            }
        awaitClose { registration.remove() }
    }

    override suspend fun createRoom(request: CreateCompetitionRoomRequest): CreateRoomResult {
        val name = request.name.trim()
        if (name.isEmpty()) return CreateRoomResult.Failure("請輸入房間名稱。")
        if (name.length > 40) return CreateRoomResult.Failure("房間名稱不可超過 40 個字。")
        val zoneId = runCatching { ZoneId.of(request.timezoneId).id }
            .getOrElse { return CreateRoomResult.Failure("時區格式不正確。") }
        val uid = ensureSignedIn().getOrElse {
            return CreateRoomResult.Failure(userMessage(it, "匿名登入失敗"))
        }

        repeat(MAX_INVITE_ATTEMPTS) {
            val inviteCode = generateInviteCode()
            val roomRef = firestore.collection("rooms").document()
            val inviteRef = firestore.collection("invites").document(inviteCode)
            val memberRef = roomRef.collection("members").document(uid)
            val userRoomRef = firestore.collection("users").document(uid).collection("rooms").document(roomRef.id)
            val room = CompetitionRoom(roomRef.id, name, inviteCode, zoneId, uid)
            try {
                firestore.runTransaction { transaction ->
                    if (transaction.get(inviteRef).exists()) throw InviteCollisionException()
                    val serverTime = FieldValue.serverTimestamp()
                    transaction.set(roomRef, mapOf(
                        "name" to name,
                        "ownerUid" to uid,
                        "inviteCode" to inviteCode,
                        "timezoneId" to zoneId,
                        "status" to "active",
                        "createdAt" to serverTime,
                        "schemaVersion" to SCHEMA_VERSION,
                    ))
                    transaction.set(inviteRef, mapOf(
                        "roomId" to roomRef.id,
                        "roomName" to name,
                        "ownerUid" to uid,
                        "inviteCode" to inviteCode,
                        "timezoneId" to zoneId,
                        "active" to true,
                        "createdAt" to serverTime,
                    ))
                    transaction.set(memberRef, memberData(uid, "owner", ""))
                    transaction.set(userRoomRef, roomSummary(room))
                    room
                }.awaitTask()
                roomDisplayNames[room.id] = participantName(uid)
                return CreateRoomResult.Success(room)
            } catch (_: InviteCollisionException) {
                // Retry with a new cryptographically random code.
            } catch (error: Exception) {
                return CreateRoomResult.Failure(userMessage(error, "建立房間失敗"))
            }
        }
        return CreateRoomResult.Failure("無法產生唯一邀請碼，請再試一次。")
    }

    override suspend fun joinRoom(inviteCode: String): JoinRoomResult {
        val normalizedCode = normalizeInviteCode(inviteCode)
        if (!INVITE_REGEX.matches(normalizedCode)) {
            return JoinRoomResult.Failure("邀請碼應為 6 位英文字母或數字。")
        }
        val uid = ensureSignedIn().getOrElse {
            return JoinRoomResult.Failure(userMessage(it, "匿名登入失敗"))
        }
        return try {
            val room = firestore.runTransaction { transaction ->
                val inviteRef = firestore.collection("invites").document(normalizedCode)
                val invite = transaction.get(inviteRef)
                if (!invite.exists() || invite.getBoolean("active") != true) {
                    throw InvalidInviteException()
                }
                val roomId = invite.getString("roomId") ?: throw InvalidInviteException()
                val name = invite.getString("roomName") ?: throw InvalidInviteException()
                val zoneId = invite.getString("timezoneId") ?: throw InvalidInviteException()
                val ownerUid = invite.getString("ownerUid").orEmpty()
                val room = CompetitionRoom(roomId, name, normalizedCode, zoneId, ownerUid)
                val roomRef = firestore.collection("rooms").document(roomId)
                transaction.set(roomRef.collection("members").document(uid), memberData(uid, "member", normalizedCode))
                transaction.set(
                    firestore.collection("users").document(uid).collection("rooms").document(roomId),
                    roomSummary(room),
                )
                room
            }.awaitTask()
            roomDisplayNames[room.id] = participantName(uid)
            JoinRoomResult.Success(room)
        } catch (_: InvalidInviteException) {
            JoinRoomResult.Failure("找不到此邀請碼，或房間已關閉。")
        } catch (error: Exception) {
            JoinRoomResult.Failure(userMessage(error, "加入房間失敗"))
        }
    }

    override suspend fun updateNickname(roomId: String, nickname: String): RoomActionResult {
        val validationMessage = nicknameValidationMessage(nickname)
        if (validationMessage != null) return RoomActionResult.Failure(validationMessage)
        val normalizedNickname = normalizeNickname(nickname)
        val uid = ensureSignedIn().getOrElse {
            return RoomActionResult.Failure(userMessage(it, "匿名登入失敗"))
        }
        return try {
            val memberRef = firestore.collection("rooms").document(roomId)
                .collection("members").document(uid)
            val member = memberRef.get().awaitTask()
            if (!member.exists()) return RoomActionResult.Failure("你目前不是這個房間的成員。")
            memberRef.update("displayName", normalizedNickname).awaitTask()
            roomDisplayNames[roomId] = normalizedNickname
            clearUploadCache(roomId)
            RoomActionResult.Success
        } catch (error: Exception) {
            RoomActionResult.Failure(userMessage(error, "更新暱稱失敗"))
        }
    }

    override suspend fun leaveRoom(roomId: String): RoomActionResult {
        val uid = ensureSignedIn().getOrElse {
            return RoomActionResult.Failure(userMessage(it, "匿名登入失敗"))
        }
        val roomRef = firestore.collection("rooms").document(roomId)
        val memberRef = roomRef.collection("members").document(uid)
        val userRoomRef = firestore.collection("users").document(uid).collection("rooms").document(roomId)
        return try {
            val member = memberRef.get().awaitTask()
            if (!member.exists()) return RoomActionResult.Success
            if (member.getString("role") == "owner") {
                return RoomActionResult.Failure("房主不能退出自己的房間，請改用刪除房間。")
            }
            val myScores = roomRef.collection("dailyScores").whereEqualTo("uid", uid).get().awaitTask()
            commitDeletes(myScores.documents.map { it.reference })
            firestore.runTransaction { transaction ->
                transaction.delete(memberRef)
                transaction.delete(userRoomRef)
            }.awaitTask()
            roomDisplayNames.remove(roomId)
            clearUploadCache(roomId)
            RoomActionResult.Success
        } catch (error: Exception) {
            RoomActionResult.Failure(userMessage(error, "退出房間失敗"))
        }
    }

    override suspend fun deleteRoom(roomId: String): RoomActionResult {
        val uid = ensureSignedIn().getOrElse {
            return RoomActionResult.Failure(userMessage(it, "匿名登入失敗"))
        }
        val roomRef = firestore.collection("rooms").document(roomId)
        return try {
            val roomSnapshot = roomRef.get().awaitTask()
            if (!roomSnapshot.exists()) return RoomActionResult.Success
            if (roomSnapshot.getString("ownerUid") != uid) {
                return RoomActionResult.Failure("只有房主可以刪除房間。")
            }
            val inviteCode = roomSnapshot.getString("inviteCode").orEmpty()
            val inviteRef = firestore.collection("invites").document(inviteCode)

            firestore.runTransaction { transaction ->
                val invite = if (inviteCode.isNotEmpty()) transaction.get(inviteRef) else null
                transaction.update(roomRef, "status", "deleting")
                if (invite?.exists() == true) transaction.update(inviteRef, "active", false)
            }.awaitTask()

            val scores = roomRef.collection("dailyScores").get().awaitTask().documents
            val members = roomRef.collection("members").get().awaitTask().documents
            commitDeletes(scores.map { it.reference })
            commitDeletes(
                members.map { member ->
                    firestore.collection("users").document(member.id).collection("rooms").document(roomId)
                }
            )
            commitDeletes(members.map { it.reference })
            if (inviteCode.isNotEmpty()) inviteRef.delete().awaitTask()
            roomRef.delete().awaitTask()

            roomDisplayNames.remove(roomId)
            clearUploadCache(roomId)
            RoomActionResult.Success
        } catch (error: Exception) {
            RoomActionResult.Failure(userMessage(error, "刪除房間失敗"))
        }
    }

    override suspend fun syncDailyScore(
        room: CompetitionRoom,
        localDate: String,
        totals: RankTotals,
    ): SyncScoreResult {
        if (runCatching { LocalDate.parse(localDate) }.isFailure) {
            return SyncScoreResult.Failure("排行榜日期格式不正確。")
        }
        val uid = ensureSignedIn().getOrElse {
            return SyncScoreResult.Failure(userMessage(it, "匿名登入失敗"))
        }
        val normalized = RankTotals(
            effectiveMs = totals.effectiveMs.coerceIn(0L, MAX_DAILY_DURATION_MS),
            slouchMs = totals.slouchMs.coerceIn(0L, totals.effectiveMs.coerceAtMost(MAX_DAILY_DURATION_MS)),
            squintEvents = totals.squintEvents.coerceIn(0, MAX_DAILY_EVENTS),
            tooCloseEvents = totals.tooCloseEvents.coerceIn(0, MAX_DAILY_EVENTS),
        )
        val cacheKey = room.id + "/" + localDate
        if (lastUploaded[cacheKey] == normalized) return SyncScoreResult.Success

        return try {
            val displayName = resolveDisplayName(room.id, uid)
            val data = mapOf(
                "uid" to uid,
                "displayName" to displayName,
                "localDate" to localDate,
                "effectiveMs" to normalized.effectiveMs,
                "slouchMs" to normalized.slouchMs,
                "squintEvents" to normalized.squintEvents,
                "tooCloseEvents" to normalized.tooCloseEvents,
                "updatedAt" to FieldValue.serverTimestamp(),
                "schemaVersion" to SCHEMA_VERSION,
            )
            firestore.collection("rooms").document(room.id)
                .collection("dailyScores").document(localDate + "_" + uid)
                .set(data)
                .awaitTask()
            lastUploaded[cacheKey] = normalized
            SyncScoreResult.Success
        } catch (error: Exception) {
            SyncScoreResult.Failure(userMessage(error, "同步排名資料失敗"))
        }
    }

    private fun memberData(uid: String, role: String, inviteCodeUsed: String) = mapOf(
        "uid" to uid,
        "displayName" to participantName(uid),
        "role" to role,
        "inviteCodeUsed" to inviteCodeUsed,
        "joinedAt" to FieldValue.serverTimestamp(),
    )

    private fun roomSummary(room: CompetitionRoom) = mapOf(
        "name" to room.name,
        "ownerUid" to room.ownerUid,
        "inviteCode" to room.inviteCode,
        "timezoneId" to room.timezoneId,
        "joinedAt" to FieldValue.serverTimestamp(),
    )

    private fun generateInviteCode(): String =
        buildString(INVITE_CODE_LENGTH) {
            repeat(INVITE_CODE_LENGTH) { append(INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)]) }
        }

    private fun participantName(uid: String): String = "參賽者-" + uid.takeLast(4).uppercase()

    private suspend fun resolveDisplayName(roomId: String, uid: String): String {
        roomDisplayNames[roomId]?.let { return it }
        val member = firestore.collection("rooms").document(roomId)
            .collection("members").document(uid).get().awaitTask()
        val displayName = member.getString("displayName") ?: participantName(uid)
        roomDisplayNames[roomId] = displayName
        return displayName
    }

    private suspend fun commitDeletes(references: List<DocumentReference>) {
        references.chunked(DELETE_BATCH_SIZE).forEach { chunk ->
            firestore.runBatch { batch -> chunk.forEach(batch::delete) }.awaitTask()
        }
    }

    private fun clearUploadCache(roomId: String) {
        lastUploaded.keys.filter { it.startsWith("$roomId/") }.forEach(lastUploaded::remove)
    }

    private fun userMessage(error: Throwable, prefix: String): String {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        val detail = when (firestoreError?.code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> "Firestore 權限被拒絕，請確認安全規則已部署。"
            FirebaseFirestoreException.Code.UNAVAILABLE -> "目前無法連線到 Firebase，請檢查網路。"
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> "Firebase 登入狀態已失效。"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "未知錯誤"
        }
        return "$prefix：$detail"
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            when {
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase task failed"))
            }
        }
    }

    private class InviteCollisionException : RuntimeException()
    private class InvalidInviteException : RuntimeException()

    private companion object {
        const val SCHEMA_VERSION = 1
        const val INVITE_CODE_LENGTH = 6
        const val MAX_INVITE_ATTEMPTS = 8
        const val MAX_DAILY_DURATION_MS = 86_400_000L
        const val MAX_DAILY_EVENTS = 10_000
        const val DELETE_BATCH_SIZE = 400
        const val INVITE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val INVITE_REGEX = Regex("^[A-Z0-9]{6}$")
    }
}
