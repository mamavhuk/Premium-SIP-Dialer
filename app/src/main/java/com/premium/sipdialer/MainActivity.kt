package com.premium.sipdialer

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.premium.sipdialer.database.CallRecordEntity
import com.premium.sipdialer.database.SipAccountEntity
import com.premium.sipdialer.network.NetworkDiagnosticsEngine
import com.premium.sipdialer.network.ProximityScreenController
import com.premium.sipdialer.network.SipServerProbeResult
import com.premium.sipdialer.service.SipForegroundService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var proximityController: ProximityScreenController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SipApplication
        proximityController = ProximityScreenController(this)

        // Start foreground SIP service for multi-account persistent listening
        val svcIntent = Intent(this, SipForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svcIntent)
        } else {
            startService(svcIntent)
        }

        setContent {
            val permissionsLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {}

            LaunchedEffect(Unit) {
                val perms = mutableListOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.READ_CONTACTS
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                permissionsLauncher.launch(perms.toTypedArray())
            }

            MaterialTheme(colorScheme = darkColorScheme()) {
                PremiumSipDialerApp(app, proximityController)
            }
        }
    }

    override fun onDestroy() {
        proximityController.stopMonitoring()
        super.onDestroy()
    }
}

@Composable
fun PremiumWaveBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF070B19),
                        Color(0xFF141135),
                        Color(0xFF0A2444),
                        Color(0xFF060B16)
                    )
                )
            )
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val path1 = Path().apply {
                moveTo(0f, size.height * 0.25f)
                cubicTo(
                    size.width * 0.35f, size.height * 0.15f,
                    size.width * 0.65f, size.height * 0.38f,
                    size.width, size.height * 0.22f
                )
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(
                path = path1,
                brush = Brush.horizontalGradient(
                    listOf(Color(0x2200E5FF), Color(0x227C4DFF), Color(0x2200B0FF))
                )
            )
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val baseMod = modifier
        .clip(RoundedCornerShape(22.dp))
        .background(Color.White.copy(alpha = 0.08f))
        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(22.dp))
    val clickableMod = if (onClick != null) baseMod.clickable { onClick() } else baseMod
    Column(modifier = clickableMod.padding(16.dp), content = content)
}

@Composable
fun PremiumSipDialerApp(app: SipApplication, proximityController: ProximityScreenController) {
    val accounts by app.database.dao().observeSipAccounts().collectAsState(initial = emptyList())
    val callLogs by app.database.dao().observeCallLogs().collectAsState(initial = emptyList())
    val regStatuses by app.sipEngine.regStatuses.collectAsState()
    val liveCall by app.sipEngine.liveCall.collectAsState()
    val postCallSummary by app.sipEngine.postCallSummary.collectAsState()

    var currentTab by remember { mutableStateOf(0) }
    var selectedAccountIndex by remember { mutableStateOf(0) }
    var googleUserEmail by remember { mutableStateOf<String?>(null) }

    // Manage Proximity Sensor automatically during active calls
    LaunchedEffect(liveCall.isInCall, liveCall.isSpeaker, liveCall.isIncomingRinging) {
        if (liveCall.isInCall && !liveCall.isIncomingRinging) {
            proximityController.startMonitoring(isSpeakerOrBluetoothActive = liveCall.isSpeaker)
        } else {
            proximityController.stopMonitoring()
        }
    }

    PremiumWaveBackground {
        if (liveCall.isInCall) {
            ActiveOrIncomingCallScreen(
                liveCall = liveCall,
                onAccept = { app.sipEngine.acceptIncomingCall() },
                onHangup = { app.sipEngine.hangupOrRejectCall() },
                onToggleMute = { app.sipEngine.toggleMute() },
                onToggleSpeaker = { app.sipEngine.toggleSpeaker() },
                onToggleHold = { app.sipEngine.toggleHold() },
                onToggleRecord = { app.sipEngine.toggleManualRecording() },
                onSendDtmf = { app.sipEngine.sendDtmf(it) }
            )
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xCC0B1124),
                        tonalElevation = 8.dp
                    ) {
                        val navItems = listOf(
                            Triple(0, "Keypad", Icons.Default.Dialpad),
                            Triple(1, "Recents", Icons.Default.History),
                            Triple(2, "SIP Accounts", Icons.Default.Sip),
                            Triple(3, "Diagnostics", Icons.Default.NetworkCheck),
                            Triple(4, "Settings", Icons.Default.Settings)
                        )
                        navItems.forEach { (idx, label, icon) ->
                            NavigationBarItem(
                                selected = currentTab == idx,
                                onClick = { currentTab = idx },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                    when (currentTab) {
                        0 -> DialerScreen(
                            accounts = accounts,
                            regStatuses = regStatuses,
                            selectedAccountIndex = selectedAccountIndex,
                            onSelectAccount = { selectedAccountIndex = it },
                            onPlaceCall = { number ->
                                val chosenAcc = accounts.getOrNull(selectedAccountIndex) ?: accounts.firstOrNull()
                                app.sipEngine.startOutgoingCall(number, chosenAcc)
                            }
                        )
                        1 -> CallHistoryScreen(
                            callLogs = callLogs,
                            onRedial = { number ->
                                val chosenAcc = accounts.getOrNull(selectedAccountIndex) ?: accounts.firstOrNull()
                                app.sipEngine.startOutgoingCall(number, chosenAcc)
                            }
                        )
                        2 -> SipAccountsScreen(
                            app = app,
                            accounts = accounts,
                            regStatuses = regStatuses
                        )
                        3 -> NetworkDiagnosticsScreen(
                            app = app,
                            accounts = accounts,
                            regStatuses = regStatuses
                        )
                        4 -> SettingsBackupAndAboutScreen(
                            app = app,
                            googleUserEmail = googleUserEmail,
                            onUpdateGoogleUser = { googleUserEmail = it }
                        )
                    }
                }
            }
        }

        // Post-Call Real-Time Analytics Popup
        postCallSummary?.let { summary ->
            PostCallAnalyticsDialog(
                record = summary,
                onDismiss = { app.sipEngine.dismissPostCallSummary() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    accounts: List<SipAccountEntity>,
    regStatuses: Map<String, com.premium.sipdialer.sip.AccountRegStatus>,
    selectedAccountIndex: Int,
    onSelectAccount: (Int) -> Unit,
    onPlaceCall: (String) -> Unit
) {
    var dialedNumber by remember { mutableStateOf("") }
    val activeAccount = accounts.getOrNull(selectedAccountIndex)
    val regState = activeAccount?.let { regStatuses[it.accountId]?.state } ?: "No Account Added"

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Multi-Account Selector Chip Bar
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = activeAccount?.accountName ?: "Add SIP Account in Tab 3",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Status: $regState",
                        color = if (regState == "Registered") Color(0xFF00E676) else Color(0xFFFFB74D),
                        fontSize = 12.sp
                    )
                }
                if (accounts.size > 1) {
                    TextButton(onClick = { onSelectAccount((selectedAccountIndex + 1) % accounts.size) }) {
                        Text("Switch Line (${selectedAccountIndex + 1}/${accounts.size})", color = Color(0xFF00E5FF))
                    }
                }
            }
        }

        // Large Number Display
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = dialedNumber.ifEmpty { "Enter Number" },
                fontSize = if (dialedNumber.length > 12) 28.sp else 36.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (dialedNumber.isEmpty()) Color.White.copy(alpha = 0.35f) else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            if (dialedNumber.isNotEmpty()) {
                IconButton(onClick = { dialedNumber = dialedNumber.dropLast(1) }) {
                    Icon(Icons.Default.Backspace, contentDescription = "Backspace", tint = Color(0xFF00E5FF))
                }
            }
        }

        // Modern Glassmorphic Numeric Keypad (Long-press 0 for +)
        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to "")
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { (digit, sub) ->
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.10f))
                                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
                                .combinedClickable(
                                    onClick = { dialedNumber += digit },
                                    onLongClick = {
                                        if (digit == "0") dialedNumber += "+"
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                if (sub.isNotEmpty()) {
                                    Text(sub, fontSize = 10.sp, color = Color.White.copy(alpha = 0.65f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Circular Call Button
        Button(
            onClick = { if (dialedNumber.isNotBlank()) onPlaceCall(dialedNumber) },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
            modifier = Modifier.size(76.dp)
        ) {
            Icon(Icons.Default.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
fun ActiveOrIncomingCallScreen(
    liveCall: com.premium.sipdialer.sip.LiveCallTelemetry,
    onAccept: () -> Unit,
    onHangup: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleHold: () -> Unit,
    onToggleRecord: () -> Unit,
    onSendDtmf: (Char) -> Unit
) {
    var showDtmfPad by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Line: ${liveCall.activeAccountName}",
                color = Color(0xFF00E5FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = liveCall.remoteDisplayName.ifBlank { liveCall.remoteNumber },
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = liveCall.remoteNumber,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 16.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (liveCall.isIncomingRinging) "Incoming SIP Call..." else String.format(
                    "%02d:%02d • HD (%s)",
                    liveCall.durationSec / 60,
                    liveCall.durationSec % 60,
                    liveCall.codecName
                ),
                color = Color(0xFF00E676),
                fontSize = 15.sp
            )
        }

        // Live Real-Time Network & RTP Quality Card during call
        if (!liveCall.isIncomingRinging) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Live RTP Telemetry (${liveCall.qualityLabel})", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ping/RTT: ${liveCall.currentRttMs.toInt()} ms", color = Color.White, fontSize = 12.sp)
                    Text("Jitter: ${"%.1f".format(liveCall.jitterMs)} ms", color = Color.White, fontSize = 12.sp)
                    Text("Loss: ${"%.1f".format(liveCall.packetLossPercent)}%", color = Color.White, fontSize = 12.sp)
                    Text("MOS: ${"%.1f".format(liveCall.mosScore)}", color = Color(0xFF00E676), fontSize = 12.sp)
                }
            }
        }

        if (liveCall.isIncomingRinging) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = onHangup,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Reject", tint = Color.White)
                }
                Button(
                    onClick = onAccept,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                    modifier = Modifier.size(76.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallActionChip("Mute", Icons.Default.MicOff, liveCall.isMuted, onToggleMute)
                    CallActionChip("Speaker", Icons.Default.VolumeUp, liveCall.isSpeaker, onToggleSpeaker)
                    CallActionChip("Hold", Icons.Default.Pause, liveCall.isOnHold, onToggleHold)
                    CallActionChip("Record", Icons.Default.FiberManualRecord, liveCall.isRecording, onToggleRecord)
                }

                AnimatedVisibility(visible = showDtmfPad) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#').chunked(6).forEach { group ->
                            Column {
                                group.forEach { d ->
                                    TextButton(onClick = { onSendDtmf(d) }) {
                                        Text(d.toString(), color = Color.White, fontSize = 18.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showDtmfPad = !showDtmfPad }) {
                        Text(if (showDtmfPad) "Hide Keypad" else "DTMF Keypad", color = Color(0xFF00E5FF))
                    }
                    Button(
                        onClick = onHangup,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD50000)),
                        modifier = Modifier.size(74.dp)
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Call", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
fun CallActionChip(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.14f))
        ) {
            Icon(icon, contentDescription = label, tint = if (active) Color.Black else Color.White)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
fun PostCallAnalyticsDialog(record: CallRecordEntity, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Post-Call Network & RTP Summary", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text("Number: ${record.phoneNumber} (${record.direction})", color = Color.White, fontSize = 14.sp)
            Text("SIP Account: ${record.sipAccountName}", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
            Text("Duration: ${record.durationSeconds}s • Status: ${record.status}", color = Color.White, fontSize = 13.sp)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Color.White.copy(alpha = 0.2f))
            Text("Network: ${record.networkType} • Codec: ${record.codecUsed} (${record.audioBitrateKbps.toInt()} kbps)", color = Color.White, fontSize = 13.sp)
            Text("Latency (Avg/Min/Max): ${record.avgLatencyMs.toInt()} / ${record.minLatencyMs.toInt()} / ${record.maxLatencyMs.toInt()} ms", color = Color.White, fontSize = 13.sp)
            Text("Jitter: ${"%.2f".format(record.jitterMs)} ms • Packet Loss: ${"%.2f".format(record.packetLossPercent)}%", color = Color.White, fontSize = 13.sp)
            Text("Call Quality: ${record.qualityRating} (MOS: ${"%.2f".format(record.mosScore)})", color = Color(0xFF00E676), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("RTP Data (Up / Down): ${record.uploadBytes / 1024} KB / ${record.downloadBytes / 1024} KB", color = Color.White, fontSize = 13.sp)
            if (record.recordingFilePath != null) {
                Text("Saved Recording: ${record.recordingFilePath}", color = Color(0xFF80D8FF), fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close & Save to History")
            }
        }
    }
}

@Composable
fun SipAccountsScreen(
    app: SipApplication,
    accounts: List<SipAccountEntity>,
    regStatuses: Map<String, com.premium.sipdialer.sip.AccountRegStatus>
) {
    val scope = rememberCoroutineScope()
    var accountName by remember { mutableStateOf("BanglaCall") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5060") }
    var transport by remember { mutableStateOf("UDP") }
    var stunServer by remember { mutableStateOf("stun.l.google.com:19302") }
    var autoRecord by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Multi-SIP Account Manager", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Passwords are encrypted in Android Keystore", color = Color(0xFF00E5FF), fontSize = 12.sp)
        }

        item {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Add / Register SIP Account", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = accountName, onValueChange = { accountName = it }, label = { Text("Account Label (e.g. BanglaCall, ICC Talk)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("SIP Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("SIP Password (Keystore Protected)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = domain, onValueChange = { domain = it }, label = { Text("SIP Server / Domain") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = transport, onValueChange = { transport = it }, label = { Text("UDP/TCP/TLS") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = stunServer, onValueChange = { stunServer = it }, label = { Text("STUN Server") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoRecord, onCheckedChange = { autoRecord = it })
                    Text("Auto-Record Calls on this SIP Line", color = Color.White, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        if (username.isNotBlank() && domain.isNotBlank()) {
                            val id = UUID.randomUUID().toString()
                            val entity = SipAccountEntity(
                                accountId = id,
                                accountName = accountName,
                                username = username,
                                authId = username,
                                displayName = accountName,
                                domain = domain,
                                port = port.toIntOrNull() ?: 5060,
                                transport = transport.uppercase(),
                                stunServer = stunServer,
                                autoRecordCalls = autoRecord
                            )
                            scope.launch {
                                app.vault.saveSipPassword(id, password)
                                app.database.dao().upsertSipAccount(entity)
                                app.sipEngine.registerOrUpdateSipAccount(entity)
                                username = ""
                                password = ""
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Register Simultaneously")
                }
            }
        }

        items(accounts) { acc ->
            val st = regStatuses[acc.accountId]
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${acc.accountName} (${acc.username}@${acc.domain})", color = Color.White, fontWeight = FontWeight.Bold)
                        Text(
                            text = "Status: ${st?.state ?: "Registered/Idle"} ${st?.errorDetail ?: ""}",
                            color = if (st?.state == "Registered") Color(0xFF00E676) else Color(0xFFFFB74D),
                            fontSize = 12.sp
                        )
                        Text("Transport: ${acc.transport}:${acc.port} • Auto-Record: ${acc.autoRecordCalls}", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            app.sipEngine.removeSipAccount(acc.accountId)
                            app.vault.deleteSipPassword(acc.accountId)
                            app.database.dao().deleteSipAccount(acc)
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFFF5252))
                    }
                }
            }
        }
    }
}

@Composable
fun CallHistoryScreen(callLogs: List<CallRecordEntity>, onRedial: (String) -> Unit) {
    var selectedFilter by remember { mutableStateOf("ALL") }
    val filtered = callLogs.filter {
        when (selectedFilter) {
            "INCOMING" -> it.direction == "INCOMING"
            "OUTGOING" -> it.direction == "OUTGOING"
            "MISSED" -> it.status == "MISSED"
            "RECORDED" -> it.recordingFilePath != null
            else -> true
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Call History & RTP Reports", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ALL", "INCOMING", "OUTGOING", "MISSED", "RECORDED").forEach { tab ->
                FilterChip(
                    selected = selectedFilter == tab,
                    onClick = { selectedFilter = tab },
                    label = { Text(tab, fontSize = 11.sp) }
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered) { log ->
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { onRedial(log.phoneNumber) }) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("${log.phoneNumber} (${log.sipAccountName})", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("${log.direction} • ${log.status} • ${log.durationSeconds}s", color = Color(0xFF00E5FF), fontSize = 12.sp)
                            Text(
                                "RTT Avg/Max: ${log.avgLatencyMs.toInt()}/${log.maxLatencyMs.toInt()} ms • Jitter: ${"%.1f".format(log.jitterMs)} ms • Loss: ${"%.1f".format(log.packetLossPercent)}%",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 11.sp
                            )
                            Text(
                                "Quality: ${log.qualityRating} (MOS ${"%.1f".format(log.mosScore)}) • Data: ${(log.uploadBytes + log.downloadBytes) / 1024} KB",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp
                            )
                            if (log.recordingFilePath != null) {
                                Text("Recorded Audio: ${log.recordingFilePath}", color = Color(0xFF80D8FF), fontSize = 10.sp)
                            }
                        }
                        Icon(Icons.Default.Call, contentDescription = "Redial", tint = Color(0xFF00E676))
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkDiagnosticsScreen(
    app: SipApplication,
    accounts: List<SipAccountEntity>,
    regStatuses: Map<String, com.premium.sipdialer.sip.AccountRegStatus>
) {
    val scope = rememberCoroutineScope()
    var probeTarget by remember { mutableStateOf(accounts.firstOrNull()?.domain ?: "sip.linphone.org") }
    var probeResult by remember { mutableStateOf<SipServerProbeResult?>(null) }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Network & SIP Diagnostics", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Active Connection: ${app.sipEngine.detectActiveNetworkType()}", color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
            Text("Local IPv4: ${NetworkDiagnosticsEngine.getLocalIpAddress()}", color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = probeTarget,
                onValueChange = { probeTarget = it },
                label = { Text("SIP Domain / Host to Probe") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    isRunning = true
                    scope.launch {
                        probeResult = NetworkDiagnosticsEngine.runFullDiagnostics(probeTarget)
                        isRunning = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isRunning) "Probing DNS, NAT & RTT..." else "Run Live SIP Reachability & Ping Test")
            }
        }

        probeResult?.let { res ->
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostic Report", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                Text("Public IP (NAT): ${res.publicIp}", color = Color.White)
                Text("Resolved SIP Server IP: ${res.resolvedDnsIp}", color = Color.White)
                Text("Server Reachable: ${if (res.isReachable) "YES" else "NO"}", color = if (res.isReachable) Color(0xFF00E676) else Color(0xFFFF5252))
                Text("Server Response Time (RTT): ${if (res.tcpPingMs >= 0) "${res.tcpPingMs} ms" else "Timeout"}", color = Color.White)
            }
        }
    }
}

@Composable
fun SettingsBackupAndAboutScreen(
    app: SipApplication,
    googleUserEmail: String?,
    onUpdateGoogleUser: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    var backupStatusMsg by remember { mutableStateOf("Last Backup: ${app.vault.getLastBackupTime()}") }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            onUpdateGoogleUser(task.result?.email)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Cloud Backup, Security & About", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)

        // Google Account & Guest Mode Section
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("Google Account & Cloud Sync", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = googleUserEmail?.let { "Signed in as: $it" } ?: "Mode: Optional Guest Mode (SIP works without login)",
                color = Color.White,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .build()
                    val client = GoogleSignIn.getClient(app, gso)
                    googleSignInLauncher.launch(client.signInIntent)
                }) {
                    Text(if (googleUserEmail == null) "Google Sign-In" else "Switch Account")
                }
                if (googleUserEmail != null) {
                    OutlinedButton(onClick = { onUpdateGoogleUser(null) }) {
                        Text("Logout", color = Color.White)
                    }
                }
            }
        }

        // Encrypted Backup & Restore
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("AES-256 Encrypted Backup & Restore", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
            Text(backupStatusMsg, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val file = app.backupManager.createEncryptedBackupFile()
                        backupStatusMsg = "Encrypted Backup Saved (${file.length() / 1024} KB) at ${app.vault.getLastBackupTime()}"
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Backup SIP Accounts, Call History & Settings")
            }
        }

        // About Section
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("About Premium SIP Dialer", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("App Name: Premium SIP Dialer", color = Color.White)
            Text("Version: 1.0.0 (Android 10 - Android 15+)", color = Color.White)
            Text("Engine: Linphone Native VoIP Core + Android Keystore AES-256", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "✨ This App Is Made With AI ✨",
                color = Color(0xFF00E676),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}
