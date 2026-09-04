package com.example.eyeprotect.monitoring

enum class MonitoringIssueType(val mask: Int) {
    TOO_CLOSE(mask = 1),
    SQUINTING(mask = 2),
    SLOUCHING(mask = 4),
    LYING(mask = 8),
}
