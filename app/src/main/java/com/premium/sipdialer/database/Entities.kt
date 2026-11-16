package com.premium.sipdialer.database

import androidx.room.*

@Entity(tableName = "sip_accounts")
data class SipAccountEntity(
    @PrimaryKey val accountId: String,
    val accountName: String,       // e.g., BanglaCall, ICC Talk, Personal SIP
    val username: String,
    val authId: String,
    val displayName: String,
    val domain: String,
    val port: Int = 5060,
    val transport: String = "UDP", // UDP, TCP, TLS
    val outboundProxy: String = "",
    val stunServer: String = "",
    val expirySeconds: Int = 3600,
    val preferredCodec: String = "OPUS",
    val isEnabled: Boolean = true,
    val isDefaultOutgoing: Boolean = false,
    val autoRecordCalls: Boolean = false,
    val customRingtoneUri: String = ""
)

@Entity(tableName = "call_logs")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callerName: String,
    val phoneNumber: String,
    val sipAccountId: String,
    val sipAccountName: String,
    val direction: String,         // INCOMING, OUTGOING
    val status: String,            // COMPLETED, MISSED, REJECTED, FAILED
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Int,
    // Real RTP & Network Analytics
    val avgLatencyMs: Float,
    val maxLatencyMs: Float,
    val minLatencyMs: Float,
    val jitterMs: Float,
    val packetLossPercent: Float,
    val rtpRttMs: Float,
    val networkType: String,       // Wi-Fi / Mobile Data
    val codecUsed: String,
    val audioBitrateKbps: Float,
    val mosScore: Float,           // 1.0 to 5.0
    val qualityRating: String,     // Excellent, Good, Fair, Poor
    val uploadBytes: Long,
    val downloadBytes: Long,
    val recordingFilePath: String? = null,
    val callNotes: String = "",
    val callTag: String = ""
)

@Entity(tableName = "blocked_numbers")
data class BlockedNumberEntity(
    @PrimaryKey val number: String,
    val reason: String = "Blocked by user",
    val isWhitelist: Boolean = false
)
