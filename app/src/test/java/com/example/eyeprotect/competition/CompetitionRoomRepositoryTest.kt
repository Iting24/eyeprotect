package com.example.eyeprotect.competition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetitionRoomRepositoryTest {
    @Test
    fun inviteCodeIsNormalizedForSharingAndTyping() {
        assertEquals("A2BC3D", normalizeInviteCode(" a2-bc 3d "))
    }

    @Test
    fun createRequestUsesTrimmedNameAndDeviceTimezone() {
        val request = defaultCreateRoomRequest("  護眼挑戰  ")
        assertEquals("護眼挑戰", request.name)
        assertTrue(request.timezoneId.isNotBlank())
    }

    @Test
    fun nicknameIsTrimmedAndRepeatedWhitespaceIsCollapsed() {
        assertEquals("護眼 小隊長", normalizeNickname("  護眼   小隊長  "))
    }

    @Test
    fun nicknameMustContainBetweenTwoAndSixteenCharacters() {
        assertEquals("暱稱至少需要 2 個字。", nicknameValidationMessage("A"))
        assertEquals("暱稱不可超過 16 個字。", nicknameValidationMessage("12345678901234567"))
        assertEquals(null, nicknameValidationMessage("眼睛寶寶"))
    }
}
