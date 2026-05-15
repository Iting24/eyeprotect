package com.example.eyeprotect.monitoring

enum class NightShiftMode(val prefValue: String) {
    AUTO("auto"),
    MANUAL("manual");

    companion object {
        fun fromPref(value: String?): NightShiftMode {
            return entries.firstOrNull { it.prefValue == value } ?: AUTO
        }
    }
}
