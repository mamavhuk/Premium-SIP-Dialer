package com.premium.sipdialer.sip

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.premium.sipdialer.database.AppDatabase
import com.premium.sipdialer.database.CallRecordEntity
import com.premium.sipdialer.database.SipAccountEntity
import com.premium.sipdialer.security.CredentialVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.linphone.core.*
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class AccountRegStatus(
    val accountId: String,
    val accountName: String,
    val state: String, // Registered, Progress, Failed, Cleared
    val errorDetail: String = ""
)

data class LiveCallTelemetry(
    val isInCall: Boolean = false,
    val isIncomingRinging: Boolean = false,
    val remoteNumber: String = "",
    val remoteDisplayName: String = "",
    val activeAccountName: String = "",
    val activeAccountId: String = "",
    val durationSec: Int = 0,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val isOnHold: Boolean = false,
    val isRecording: Boolean = false,
    val currentRecordingPath: String? = null,
    val codecName: String = "OPUS",
    val bitrateKbps: Float = 0f,
    val currentRttMs: Float = 0f,
    val minRttMs: Float = Float.MAX_VALUE,
    val maxRttMs: Float = 0f,
    val avgRttMs: Float = 0f,
    val jitterMs: Float = 0f,
    val packetLossPercent: Float = 0f,
    val mosScore: Float = 4.5f,
    val qualityLabel: String = "Excellent",
    val estimatedUploadBytes: Long = 0L,
    val estimatedDownloadBytes: Long = 0L
)

class SipEngineManager(
    private val context: Context,
    private val vault: CredentialVault,
    private val db: AppDatabase
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var core: Core? = null
    private var telemetryJob: Job? = null
    private var rttSamples = mutableListOf<Float>()
    private var callStartTimestamp = 0L

    private val _regStatuses = MutableStateFlow>(emptyMap())
    val regStatuses: StateFlow> = _regStatuses

    private val _liveCall = MutableStateFlow(LiveCallTelemetry())
    val liveCall: StateFlow<LiveCallTelemetry> = _liveCall

    private val _postCallSummary = MutableStateFlow<CallRecordEntity?>(null)
    val postCallSummary: StateFlow<CallRecordEntity?> = _postCallSummary

    private val coreListener = object : CoreListenerStub() {
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            val accId = account.params.customParam("accountId") ?: account.params.identityAddress?.username ?: "unknown"
            val accName = account.params.customParam("accountName") ?: accId
            val mappedState = when (state) {
                RegistrationState.Ok -> "Registered"
                RegistrationState.Progress -> "Connecting..."
                RegistrationState.Failed -> "Failed"
                else -> "Disabled"
            }
            val currentMap = _regStatuses.value.toMutableMap()
            currentMap[accId] = AccountRegStatus(
                accountId = accId,
                accountName = accName,
                state = mappedState,
                errorDetail = if (state == RegistrationState.Failed) message else ""
            )
            _regStatuses.value = currentMap
        }

        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val remoteUser = call.remoteAddress.username ?: "Unknown"
            val remoteDisplay = call.remoteAddress.displayName ?: remoteUser
            val toUser = call.toAddress.username ?: ""
            val matchedAcc = core.accountList.find { it.params.identityAddress?.username == toUser }
                ?: core.defaultAccount
            val accId = matchedAcc?.params?.customParam("accountId") ?: "default"
            val accName = matchedAcc?.params?.customParam("accountName") ?: "SIP Account"

            when (state) {
                Call.State.IncomingReceived -> {
                    scope.launch {
                        if (db.dao().isNumberBlocked(remoteUser)) {
                            call.decline(Reason.Declined)
                            return@launch
                        }
                        callStartTimestamp = System.currentTimeMillis()
                        rttSamples.clear()
                        _liveCall.value = LiveCallTelemetry(
                            isInCall = true,
                            isIncomingRinging = true,
                            remoteNumber = remoteUser,
                            remoteDisplayName = remoteDisplay,
                            activeAccountName = accName,
                            activeAccountId = accId
                        )
                    }
                }
                Call.State.OutgoingInit, Call.State.OutgoingProgress, Call.State.OutgoingRinging -> {
                    callStartTimestamp = System.currentTimeMillis()
                    rttSamples.clear()
                    _liveCall.value = LiveCallTelemetry(
                        isInCall = true,
                        isIncomingRinging = false,
                        remoteNumber = remoteUser,
                        remoteDisplayName = remoteDisplay,
                        activeAccountName = accName,
                        activeAccountId = accId
                    )
                }
                Call.State.StreamsRunning, Call.State.Connected -> {
                    _liveCall.value = _liveCall.value.copy(isIncomingRinging = false, isOnHold = false)
                    startRealTimeRtpTelemetry(call)
                }
                Call.State.Paused -> {
                    _liveCall.value = _liveCall.value.copy(isOnHold = true)
                }
                Call.State.End, Call.State.Released, Call.State.Error -> {
                    telemetryJob?.cancel()
                    if (_liveCall.value.isInCall && callStartTimestamp > 0L) {
                        finalizeCallAndSaveAnalytics(call, state)
                        callStartTimestamp = 0L
                    }
                    _liveCall.value = LiveCallTelemetry(isInCall = false)
                }
                else -> {}
            }
        }
    }

    fun initializeEngine() {
        if (core != null) return
        val factory = Factory.instance()
        core = factory.createCore(null, null, context).apply {
            isNetworkReachable = true
            isKeepAliveEnabled = true
            addListener(coreListener)
            start()
        }
        scope.launch {
            val savedAccounts = db.dao().getAllSipAccounts()
            savedAccounts.forEach { registerOrUpdateSipAccount(it) }
        }
    }

    fun registerOrUpdateSipAccount(entity: SipAccountEntity) {
        val c = core ?: return
        // Remove old instance of same accountId if exists
        c.accountList.find { it.params.customParam("accountId") == entity.accountId }?.let { oldAcc ->
            c.removeAccount(oldAcc)
        }
        val password = vault.getSipPassword(entity.accountId)
        val authInfo = Factory.instance().createAuthInfo(
            entity.username,
            entity.authId.ifBlank { entity.username },
            password,
            null,
            null,
            entity.domain
        )
        c.addAuthInfo(authInfo)

        val accountParams = c.createAccountParams()
        val identity = Factory.instance().createAddress("sip:${entity.username}@${entity.domain}")
        identity?.displayName = entity.displayName.ifBlank { entity.accountName }
        accountParams.identityAddress = identity

        val transportType = when (entity.transport.uppercase()) {
            "TCP" -> TransportType.Tcp
            "TLS" -> TransportType.Tls
            else -> TransportType.Udp
        }
        val serverAddr = Factory.instance().createAddress("sip:${entity.domain}:${entity.port}")
        serverAddr?.transport = transportType
        accountParams.serverAddress = serverAddr
        accountParams.isRegisterEnabled = entity.isEnabled
        accountParams.expires = entity.expirySeconds
        accountParams.setCustomParam("accountId", entity.accountId)
        accountParams.setCustomParam("accountName", entity.accountName)

        if (entity.outboundProxy.isNotBlank()) {
            val proxyAddr = Factory.instance().createAddress("sip:${entity.outboundProxy}")
            proxyAddr?.transport = transportType
            accountParams.routesAddresses = arrayOf(proxyAddr)
        }

        if (entity.stunServer.isNotBlank()) {
            val natPolicy = c.createNatPolicy()
            natPolicy.stunServer = entity.stunServer
            natPolicy.isStunEnabled = true
            natPolicy.isIceEnabled = true
            accountParams.natPolicy = natPolicy
        }

        val account = c.createAccount(accountParams)
        c.addAccount(account)
        if (entity.isDefaultOutgoing || c.defaultAccount == null) {
            c.defaultAccount = account
        }
    }

    fun removeSipAccount(accountId: String) {
        val c = core ?: return
        c.accountList.find { it.params.customParam("accountId") == accountId }?.let {
            c.removeAccount(it)
        }
        val updated = _regStatuses.value.toMutableMap()
        updated.remove(accountId)
        _regStatuses.value = updated
    }

    fun startOutgoingCall(destinationNumber: String, accountEntity: SipAccountEntity?) {
        val c = core ?: return
        val selectedAcc = accountEntity?.let { target ->
            c.accountList.find { it.params.customParam("accountId") == target.accountId }
        } ?: c.defaultAccount

        val domain = accountEntity?.domain
            ?: selectedAcc?.params?.identityAddress?.domain
            ?: return

        val remoteAddr = c.interpretUrl("sip:$destinationNumber@$domain") ?: return
        val params = c.createCallParams(null) ?: return
        params.account = selectedAcc
        params.mediaEncryption = MediaEncryption.None

        // Prepare Recording File inside App Storage (Direct SIP Media Engine Recording)
        val recDir = File(context.getExternalFilesDir(null), "Recordings").apply { mkdirs() }
        val filePath = File(recDir, "SIP_${destinationNumber}_${System.currentTimeMillis()}.wav").absolutePath
        params.recordingFile = filePath

        val call = c.inviteAddressWithParams(remoteAddr, params)
        if (accountEntity?.autoRecordCalls == true) {
            call?.startRecording()
            _liveCall.value = _liveCall.value.copy(isRecording = true, currentRecordingPath = filePath)
        } else {
            _liveCall.value = _liveCall.value.copy(currentRecordingPath = filePath)
        }
    }

    fun acceptIncomingCall() {
        val call = core?.currentCall ?: return
        val params = core?.createCallParams(call) ?: return
        val recDir = File(context.getExternalFilesDir(null), "Recordings").apply { mkdirs() }
        val filePath = File(recDir, "SIP_IN_${System.currentTimeMillis()}.wav").absolutePath
        params.recordingFile = filePath
        call.acceptWithParams(params)
        _liveCall.value = _liveCall.value.copy(isIncomingRinging = false, currentRecordingPath = filePath)
    }

    fun hangupOrRejectCall() {
        val call = core?.currentCall ?: return
        if (call.state == Call.State.IncomingReceived) {
            call.decline(Reason.Declined)
        } else {
            call.terminate()
        }
    }

    fun toggleMute() {
        val c = core ?: return
        c.isMicEnabled = !c.isMicEnabled
        _liveCall.value = _liveCall.value.copy(isMuted = !c.isMicEnabled)
    }

    fun toggleSpeaker() {
        val c = core ?: return
        val currentCall = c.currentCall ?: return
        val devices = c.audioDevices
        val isCurrentlySpeaker = _liveCall.value.isSpeaker
        val targetType = if (isCurrentlySpeaker) AudioDevice.Type.Earpiece else AudioDevice.Type.Speaker
        devices.firstOrNull { it.type == targetType }?.let {
            currentCall.outputAudioDevice = it
            _liveCall.value = _liveCall.value.copy(isSpeaker = !isCurrentlySpeaker)
        }
    }

    fun toggleHold() {
        val call = core?.currentCall ?: return
        if (_liveCall.value.isOnHold) call.resume() else call.pause()
    }

    fun toggleManualRecording() {
        val call = core?.currentCall ?: return
        if (_liveCall.value.isRecording) {
            call.stopRecording()
            _liveCall.value = _liveCall.value.copy(isRecording = false)
        } else {
            call.startRecording()
            _liveCall.value = _liveCall.value.copy(isRecording = true)
        }
    }

    fun sendDtmf(digit: Char) {
        core?.currentCall?.sendDtmf(digit)
    }

    fun transferCall(targetUri: String) {
        val c = core ?: return
        val call = c.currentCall ?: return
        val addr = c.interpretUrl(targetUri) ?: return
        call.transferTo(addr)
    }

    private fun startRealTimeRtpTelemetry(call: Call) {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (isActive && call.state == Call.State.StreamsRunning) {
                val audioStats = call.audioStats
                val rtt = (audioStats?.roundTripDelay ?: 0f) * 1000f // Convert sec to ms
                if (rtt > 0f) rttSamples.add(rtt)
                val minRtt = if (rttSamples.isEmpty()) 0f else rttSamples.minOrNull() ?: 0f
                val maxRtt = if (rttSamples.isEmpty()) 0f else rttSamples.maxOrNull() ?: 0f
                val avgRtt = if (rttSamples.isEmpty()) 0f else rttSamples.average().toFloat()

                val jitter = (audioStats?.receiverInterarrivalJitter ?: 0f) * 1000f
                val loss = audioStats?.receiverLossRate ?: 0f
                val upBwKbps = audioStats?.uploadBandwidth ?: 64f
                val downBwKbps = audioStats?.downloadBandwidth ?: 64f
                val duration = call.duration
                val mos = call.currentQuality.coerceIn(1.0f, 5.0f)
                val codec = call.currentParams.usedAudioPayloadType?.mimeType ?: "OPUS"

                // Calculate cumulative RTP transmitted/received bytes from bandwidth kbps * duration
                val upBytes = ((upBwKbps * 1000f / 8f) * duration).toLong()
                val downBytes = ((downBwKbps * 1000f / 8f) * duration).toLong()

                val rating = when {
                    mos >= 4.0f -> "Excellent"
                    mos >= 3.3f -> "Good"
                    mos >= 2.6f -> "Fair"
                    else -> "Poor"
                }

                _liveCall.value = _liveCall.value.copy(
                    durationSec = duration,
                    codecName = codec,
                    bitrateKbps = downBwKbps,
                    currentRttMs = rtt,
                    minRttMs = minRtt,
                    maxRttMs = maxRtt,
                    avgRttMs = avgRtt,
                    jitterMs = jitter,
                    packetLossPercent = loss,
                    mosScore = mos,
                    qualityLabel = rating,
                    estimatedUploadBytes = upBytes,
                    estimatedDownloadBytes = downBytes
                )
                delay(1000L)
            }
        }
    }

    private fun finalizeCallAndSaveAnalytics(call: Call, finalState: Call.State?) {
        val snap = _liveCall.value
        val endMillis = System.currentTimeMillis()
        val direction = if (call.dir == Call.Dir.Incoming) "INCOMING" else "OUTGOING"
        val status = when {
            snap.durationSec > 0 -> "COMPLETED"
            call.dir == Call.Dir.Incoming -> "MISSED"
            finalState == Call.State.Error -> "FAILED"
            else -> "REJECTED"
        }

        val record = CallRecordEntity(
            callerName = snap.remoteDisplayName,
            phoneNumber = snap.remoteNumber,
            sipAccountId = snap.activeAccountId,
            sipAccountName = snap.activeAccountName,
            direction = direction,
            status = status,
            startTimeMillis = callStartTimestamp,
            endTimeMillis = endMillis,
            durationSeconds = snap.durationSec,
            avgLatencyMs = snap.avgRttMs,
            maxLatencyMs = snap.maxRttMs,
            minLatencyMs = if (snap.minRttMs == Float.MAX_VALUE) 0f else snap.minRttMs,
            jitterMs = snap.jitterMs,
            packetLossPercent = snap.packetLossPercent,
            rtpRttMs = snap.currentRttMs,
            networkType = detectActiveNetworkType(),
            codecUsed = snap.codecName,
            audioBitrateKbps = snap.bitrateKbps,
            mosScore = snap.mosScore,
            qualityRating = snap.qualityLabel,
            uploadBytes = snap.estimatedUploadBytes,
            downloadBytes = snap.estimatedDownloadBytes,
            recordingFilePath = if (snap.currentRecordingPath != null && File(snap.currentRecordingPath).exists()) snap.currentRecordingPath else null
        )

        scope.launch {
            db.dao().insertCallLog(record)
            _postCallSummary.value = record
        }
    }

    fun dismissPostCallSummary() {
        _postCallSummary.value = null
    }

    fun detectActiveNetworkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "VPN / Other"
        }
    }
}
