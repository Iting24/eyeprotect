package com.example.eyeprotect

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class FaceProfile(
    val id: String,
    val label: String,
    val irisThreshold: Float? = null,
    val eyeOpenThreshold: Float? = null,
    val slouchThreshold: Float? = null,
    val signature: FaceSignature? = null,
    val calibratedAtEpochMs: Long = 0L
) {
    val hasValidCalibration: Boolean
        get() = CalibrationPrefs.hasValidCalibration(
            irisThreshold = irisThreshold ?: Float.NaN,
            eyeOpenThreshold = eyeOpenThreshold ?: Float.NaN,
            slouchThreshold = slouchThreshold ?: Float.NaN
        )
}

object FaceProfileStore {
    private const val PREF_FACE_PROFILES_JSON = "pref_face_profiles_json"
    private const val PREF_ACTIVE_FACE_PROFILE_ID = "pref_active_face_profile_id"

    fun getProfiles(prefs: SharedPreferences): List<FaceProfile> {
        val state = ensureInitialized(prefs)
        return state.profiles
    }

    fun getActiveProfile(prefs: SharedPreferences): FaceProfile? {
        val state = ensureInitialized(prefs)
        return state.profiles.firstOrNull { it.id == state.activeProfileId }
    }

    fun setActiveProfile(prefs: SharedPreferences, profileId: String) {
        val state = ensureInitialized(prefs)
        if (state.profiles.none { it.id == profileId }) return
        persistState(prefs, ProfileState(state.profiles, profileId))
    }

    fun updateActiveProfileCalibration(
        prefs: SharedPreferences,
        irisThreshold: Float,
        eyeOpenThreshold: Float,
        slouchThreshold: Float,
        signature: FaceSignature?
    ): FaceProfile {
        val state = ensureInitialized(prefs)
        val updatedProfiles = state.profiles.map { profile ->
            if (profile.id != state.activeProfileId) {
                profile
            } else {
                profile.copy(
                    irisThreshold = irisThreshold,
                    eyeOpenThreshold = eyeOpenThreshold,
                    slouchThreshold = slouchThreshold,
                    signature = signature,
                    calibratedAtEpochMs = System.currentTimeMillis()
                )
            }
        }
        val updatedState = ProfileState(updatedProfiles, state.activeProfileId)
        persistState(prefs, updatedState)
        return updatedState.profiles.first { it.id == updatedState.activeProfileId }
    }

    fun activeProfileSupportsIdentity(prefs: SharedPreferences): Boolean {
        return getActiveProfile(prefs)?.signature != null
    }

    private fun ensureInitialized(prefs: SharedPreferences): ProfileState {
        val storedProfilesJson = prefs.getString(PREF_FACE_PROFILES_JSON, null)
        val storedActiveId = prefs.getString(PREF_ACTIVE_FACE_PROFILE_ID, null)
        if (storedProfilesJson.isNullOrBlank()) {
            return migrateLegacyCalibration(prefs).also { persistState(prefs, it) }
        }

        val parsedProfiles = runCatching { parseProfiles(storedProfilesJson) }.getOrDefault(emptyList())
        val normalizedProfiles = normalizeProfiles(parsedProfiles)
        val activeId = storedActiveId?.takeIf { id -> normalizedProfiles.any { it.id == id } } ?: DEFAULT_PROFILE_ID
        val state = ProfileState(normalizedProfiles, activeId)
        val needsRepair = normalizedProfiles != parsedProfiles || activeId != storedActiveId
        if (needsRepair) {
            persistState(prefs, state)
        } else {
            syncLegacyCalibrationKeys(prefs, normalizedProfiles.firstOrNull { it.id == activeId })
        }
        return state
    }

    private fun migrateLegacyCalibration(prefs: SharedPreferences): ProfileState {
        val legacyIris = prefs.getFloatOrNull(CalibrationPrefs.KEY_IRIS_THRESHOLD)
        val legacyEye = prefs.getFloatOrNull(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD)
        val legacySlouch = prefs.getFloatOrNull(CalibrationPrefs.KEY_SLOUCH_THRESHOLD)
        val migratedProfiles = defaultProfiles().toMutableList()
        if (CalibrationPrefs.hasValidCalibration(
                irisThreshold = legacyIris ?: Float.NaN,
                eyeOpenThreshold = legacyEye ?: Float.NaN,
                slouchThreshold = legacySlouch ?: Float.NaN
            )
        ) {
            migratedProfiles[0] = migratedProfiles[0].copy(
                irisThreshold = legacyIris,
                eyeOpenThreshold = legacyEye,
                slouchThreshold = legacySlouch
            )
        }
        return ProfileState(
            profiles = migratedProfiles,
            activeProfileId = DEFAULT_PROFILE_ID
        )
    }

    private fun persistState(prefs: SharedPreferences, state: ProfileState) {
        prefs.edit()
            .putString(PREF_FACE_PROFILES_JSON, serializeProfiles(state.profiles).toString())
            .putString(PREF_ACTIVE_FACE_PROFILE_ID, state.activeProfileId)
            .apply()
        syncLegacyCalibrationKeys(prefs, state.profiles.firstOrNull { it.id == state.activeProfileId })
    }

    private fun syncLegacyCalibrationKeys(prefs: SharedPreferences, activeProfile: FaceProfile?) {
        if (activeProfile?.hasValidCalibration == true) {
            val iris = activeProfile.irisThreshold!!
            val eyeOpen = activeProfile.eyeOpenThreshold!!
            val slouch = activeProfile.slouchThreshold!!
            val alreadySynced =
                prefs.getFloatOrNull(CalibrationPrefs.KEY_IRIS_THRESHOLD) == iris &&
                    prefs.getFloatOrNull(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD) == eyeOpen &&
                    prefs.getFloatOrNull(CalibrationPrefs.KEY_SLOUCH_THRESHOLD) == slouch
            if (!alreadySynced) {
                prefs.edit()
                    .putFloat(CalibrationPrefs.KEY_IRIS_THRESHOLD, iris)
                    .putFloat(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD, eyeOpen)
                    .putFloat(CalibrationPrefs.KEY_SLOUCH_THRESHOLD, slouch)
                    .apply()
            }
        } else {
            val hasAnyLegacyKeys =
                prefs.contains(CalibrationPrefs.KEY_IRIS_THRESHOLD) ||
                    prefs.contains(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD) ||
                    prefs.contains(CalibrationPrefs.KEY_SLOUCH_THRESHOLD)
            if (hasAnyLegacyKeys) {
                prefs.edit()
                    .remove(CalibrationPrefs.KEY_IRIS_THRESHOLD)
                    .remove(CalibrationPrefs.KEY_EYE_OPEN_THRESHOLD)
                    .remove(CalibrationPrefs.KEY_SLOUCH_THRESHOLD)
                    .apply()
            }
        }
    }

    private fun defaultProfiles(): List<FaceProfile> = listOf(
        FaceProfile(id = DEFAULT_PROFILE_ID, label = "人臉一"),
        FaceProfile(id = "face_profile_2", label = "人臉二"),
        FaceProfile(id = "face_profile_3", label = "人臉三")
    )

    private fun normalizeProfiles(profiles: List<FaceProfile>): List<FaceProfile> {
        val byId = profiles.associateBy { it.id }
        return defaultProfiles().map { fallback ->
            byId[fallback.id]?.copy(label = byId[fallback.id]?.label ?: fallback.label) ?: fallback
        }
    }

    private fun parseProfiles(json: String): List<FaceProfile> {
        val array = JSONArray(json)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    FaceProfile(
                        id = item.optString("id"),
                        label = item.optString("label"),
                        irisThreshold = item.optDoubleOrNull("irisThreshold")?.toFloat(),
                        eyeOpenThreshold = item.optDoubleOrNull("eyeOpenThreshold")?.toFloat(),
                        slouchThreshold = item.optDoubleOrNull("slouchThreshold")?.toFloat(),
                        signature = item.optJSONObject("signature")?.toFaceSignature(),
                        calibratedAtEpochMs = item.optLong("calibratedAtEpochMs", 0L)
                    )
                )
            }
        }.filter { it.id.isNotBlank() && it.label.isNotBlank() }
    }

    private fun serializeProfiles(profiles: List<FaceProfile>): JSONArray {
        return JSONArray().apply {
            profiles.forEach { profile ->
                put(
                    JSONObject().apply {
                        put("id", profile.id)
                        put("label", profile.label)
                        profile.irisThreshold?.let { put("irisThreshold", it.toDouble()) }
                        profile.eyeOpenThreshold?.let { put("eyeOpenThreshold", it.toDouble()) }
                        profile.slouchThreshold?.let { put("slouchThreshold", it.toDouble()) }
                        put("calibratedAtEpochMs", profile.calibratedAtEpochMs)
                        profile.signature?.let { put("signature", it.toJson()) }
                    }
                )
            }
        }
    }

    private fun JSONObject.toFaceSignature(): FaceSignature? {
        val vectorJson = optJSONArray("landmarkVector") ?: return null
        val vector = buildList {
            for (index in 0 until vectorJson.length()) {
                if (!vectorJson.isNull(index)) add(vectorJson.optDouble(index).toFloat())
            }
        }
        if (vector.isEmpty()) return null
        return FaceSignature(
            boxWidthToEyeDistance = optDouble("boxWidthToEyeDistance", Double.NaN).toFloat(),
            boxHeightToEyeDistance = optDouble("boxHeightToEyeDistance", Double.NaN).toFloat(),
            landmarkVector = vector
        )
    }

    private fun FaceSignature.toJson(): JSONObject {
        return JSONObject().apply {
            put("boxWidthToEyeDistance", boxWidthToEyeDistance.toDouble())
            put("boxHeightToEyeDistance", boxHeightToEyeDistance.toDouble())
            put(
                "landmarkVector",
                JSONArray().apply {
                    landmarkVector.forEach { put(it.toDouble()) }
                }
            )
        }
    }

    private fun SharedPreferences.getFloatOrNull(key: String): Float? {
        return if (contains(key)) getFloat(key, Float.NaN).takeUnless { it.isNaN() } else null
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        return if (has(key) && !isNull(key)) optDouble(key) else null
    }

    private data class ProfileState(
        val profiles: List<FaceProfile>,
        val activeProfileId: String
    )

    private const val DEFAULT_PROFILE_ID = "face_profile_1"
}
