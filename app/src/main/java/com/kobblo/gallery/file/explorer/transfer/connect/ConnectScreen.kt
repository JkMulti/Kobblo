package com.kobblo.gallery.file.explorer.transfer.connect

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.core.LocalAddresses
import com.kobblo.gallery.file.explorer.transfer.core.PairingCode
import com.kobblo.gallery.file.explorer.transfer.core.PairingParse
import com.kobblo.gallery.file.explorer.transfer.core.PairingWifi
import com.kobblo.gallery.file.explorer.transfer.core.SavedPeer
import com.kobblo.gallery.file.explorer.transfer.hotspot.WifiJoinState
import com.kobblo.gallery.file.explorer.transfer.hotspot.WifiJoiner
import com.kobblo.gallery.file.explorer.transfer.ui.KnownDeviceSheet
import com.kobblo.gallery.file.explorer.transfer.ui.NoteRow
import com.kobblo.gallery.file.explorer.transfer.ui.WideButton
import com.kobblo.gallery.file.explorer.transfer.ui.deviceIcon
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import kotlinx.coroutines.launch

/** Where connecting stands, shown the same way whichever of the three routes got here. */
private sealed interface ConnectStatus {
    data object Idle : ConnectStatus
    data object Working : ConnectStatus
    data class Done(val peer: NearbyPeer) : ConnectStatus
    data class Error(val message: String) : ConnectStatus
}

/**
 * For when two devices can't find each other. Discovery depends on multicast, which many networks
 * block. Showing and scanning a code gets past that and also proves which device is on the other end;
 * an address works where neither phone has a working camera.
 */
@Composable
fun ConnectScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) }
    var status by remember { mutableStateOf<ConnectStatus>(ConnectStatus.Idle) }
    var pendingWifi by remember { mutableStateOf<PairingCode?>(null) }
    val saved by PeerDirectory.saved.collectAsState()
    val joinState by WifiJoiner.state.collectAsState()

    LaunchedEffect(Unit) {
        // Showing a code and being connected to both need the server listening.
        NearbyShareRepository.turnOnReceiving(context)
    }
    // Joining by hand is now up to the person, so nothing here is still working on it.
    LaunchedEffect(joinState) {
        if (joinState is WifiJoinState.Manual && status is ConnectStatus.Working) status = ConnectStatus.Idle
    }

    fun message(result: ConnectResult, label: String): ConnectStatus = when (result) {
        is ConnectResult.Connected -> ConnectStatus.Done(result.peer)
        ConnectResult.Unreachable -> ConnectStatus.Error(context.getString(R.string.transfer_connect_unreachable, label))
        ConnectResult.IdentityMismatch -> ConnectStatus.Error(context.getString(R.string.transfer_connect_mismatch, label))
        ConnectResult.NotADevice -> ConnectStatus.Error(context.getString(R.string.transfer_connect_not_device, label))
        ConnectResult.IsThisDevice -> ConnectStatus.Error(context.getString(R.string.transfer_connect_self))
    }

    fun connectCode(code: PairingCode) {
        status = ConnectStatus.Working
        scope.launch {
            val local = NearbyShareRepository.localDevice(context)
            status = message(PeerDirectory.connect(code, local), code.alias ?: code.hosts.first())
        }
    }

    val joinPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val code = pendingWifi ?: return@rememberLauncherForActivityResult
        val wifi = code.wifi ?: return@rememberLauncherForActivityResult
        if (results.values.all { it }) {
            status = ConnectStatus.Working
            WifiJoiner.join(context, wifi) { connectCode(code) }
        } else {
            WifiJoiner.manualFallback(wifi)
            status = ConnectStatus.Idle
        }
    }

    fun handleScanned(text: String?) {
        if (text == null) {
            status = ConnectStatus.Error(context.getString(R.string.transfer_qr_none_found))
            return
        }
        when (val parsed = PairingCode.parse(text)) {
            PairingParse.NotPairing -> status = ConnectStatus.Error(context.getString(R.string.transfer_qr_not_ours))
            is PairingParse.Invalid -> status = ConnectStatus.Error(context.getString(R.string.transfer_qr_invalid, context.getString(parsed.reason)))
            is PairingParse.Ok -> {
                val wifi = parsed.code.wifi
                if (wifi != null) {
                    pendingWifi = parsed.code
                    joinPermissions.launch(WifiJoiner.requiredPermissions())
                } else {
                    connectCode(parsed.code)
                }
            }
        }
    }

    HubScreen(title = stringResource(R.string.transfer_connect_title), backLabel = stringResource(R.string.transfer_send_tab)) {
        KobbloSegmentedControl(
            options = listOf(
                stringResource(R.string.transfer_connect_tab_code),
                stringResource(R.string.transfer_connect_tab_scan),
                stringResource(R.string.transfer_connect_tab_address)
            ),
            selectedIndex = tab,
            onSelect = {
                tab = it
                if (status !is ConnectStatus.Working) status = ConnectStatus.Idle
            },
            modifier = Modifier.padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            StatusBanner(status)
            (joinState as? WifiJoinState.Manual)?.let { manual ->
                ManualJoin(manual, onJoined = {
                    WifiJoiner.leave(context)
                    pendingWifi?.let(::connectCode)
                })
            }
            (joinState as? WifiJoinState.Joining)?.let {
                NoteRow(AppIcons.Waves, stringResource(R.string.transfer_join_joining, it.ssid))
            }
            (joinState as? WifiJoinState.Joined)?.let { joined ->
                KobbloGroup(modifier = Modifier.padding(bottom = 12.dp)) {
                    KobbloRow(
                        title = stringResource(R.string.transfer_join_joined, joined.ssid),
                        trailing = {
                            Text(
                                stringResource(R.string.transfer_join_leave),
                                style = KobbloType.callout,
                                color = KobbloDesign.colors.action
                            )
                        },
                        onClick = { WifiJoiner.leave(context) }
                    )
                }
            }
            when (tab) {
                0 -> MyCodeTab()
                1 -> ScanTab(onScanned = ::handleScanned, onReadFailed = { handleScanned(null) })
                else -> AddressTab(
                    busy = status is ConnectStatus.Working,
                    onConnect = { host, port ->
                        status = ConnectStatus.Working
                        scope.launch {
                            val local = NearbyShareRepository.localDevice(context)
                            status = message(PeerDirectory.connect(host, port, local), host)
                        }
                    }
                )
            }
            if (saved.isNotEmpty()) {
                SavedDevices(saved)
            }
        }
    }
}

@Composable
private fun StatusBanner(status: ConnectStatus) {
    when (status) {
        ConnectStatus.Idle -> Unit
        ConnectStatus.Working -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.transfer_connecting), style = KobbloType.footnote, color = KobbloDesign.colors.smoke)
        }
        is ConnectStatus.Error -> NoteRow(AppIcons.Alert, status.message, danger = true)
        is ConnectStatus.Done -> {
            val staged by NearbyShareRepository.stagedFiles.collectAsState()
            val context = LocalContext.current
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                KobbloGroup {
                    KobbloRow(
                        title = stringResource(R.string.transfer_connected_to, status.peer.alias),
                        subtitle = stringResource(
                            if (status.peer.verified) R.string.transfer_connected_verified else R.string.transfer_connected_unverified
                        ),
                        leading = {
                            Icon(
                                if (status.peer.verified) AppIcons.ShieldCheck else deviceIcon(status.peer.deviceType),
                                contentDescription = null,
                                tint = KobbloDesign.colors.action,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (staged.isNotEmpty()) {
                    WideButton(
                        text = stringResource(R.string.transfer_send_to_peer, status.peer.alias),
                        icon = AppIcons.Send,
                        onClick = {
                            NearbyShareRepository.sendStagedFiles(context, status.peer)
                            Hub.back()
                        }
                    )
                } else {
                    WideButton(
                        text = stringResource(R.string.transfer_choose_what_to_send),
                        icon = AppIcons.Send,
                        onClick = {
                            Hub.back()
                            Hub.open(TransferRoute.Picker(listOf(status.peer)))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MyCodeTab() {
    val context = LocalContext.current
    val isReceiving by NearbyShareRepository.isReceiving.collectAsState()
    if (!isReceiving) {
        Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action)
        }
        return
    }
    val code = remember(isReceiving) { myPairingCode(context, wifi = null) }
    if (code == null) {
        NoteRow(AppIcons.Alert, stringResource(R.string.transfer_no_address))
        return
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        QrImage(
            text = code.encode(),
            description = stringResource(R.string.transfer_my_code_description),
            modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${code.hosts.first()}:${code.port}",
            style = KobbloType.code,
            color = KobbloDesign.colors.ink,
            textAlign = TextAlign.Center
        )
        code.fingerprint?.let { fingerprint ->
            Text(
                text = stringResource(R.string.transfer_fingerprint_short, shortFingerprint(fingerprint)),
                style = KobbloType.footnote,
                color = KobbloDesign.colors.smoke,
                textAlign = TextAlign.Center
            )
        }
    }
    KobbloGroupFooter(stringResource(R.string.transfer_my_code_note), modifier = Modifier.padding(top = 8.dp))
}

/** What to show so another device can find and trust this one, or null with no usable address. */
internal fun myPairingCode(context: Context, wifi: PairingWifi?): PairingCode? {
    val device = NearbyShareRepository.localDevice(context)
    val hosts = LocalAddresses.current(preferHotspot = wifi != null)
    if (hosts.isEmpty()) return null
    return PairingCode(
        hosts = hosts.take(4),
        port = device.port,
        protocol = device.protocol,
        fingerprint = device.fingerprint,
        alias = device.alias,
        wifi = wifi
    )
}

internal fun shortFingerprint(fingerprint: String): String =
    fingerprint.take(16).chunked(4).joinToString(" ")

@Composable
private fun ScanTab(onScanned: (String) -> Unit, onReadFailed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var cameraUnavailable by remember { mutableStateOf(false) }
    // A new key restarts the camera, so a code that didn't work can be replaced by another.
    var cameraKey by remember { mutableStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = QrImageReader.read(context, uri)
                if (text != null) onScanned(text) else onReadFailed()
            }
        }
    }

    Column {
        Box(
            modifier = Modifier
                .padding(horizontal = KobbloSpace.screen, vertical = 8.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(KobbloShapes.lg)
                .background(KobbloDesign.colors.mist),
            contentAlignment = Alignment.Center
        ) {
            when {
                !cameraGranted -> CameraMessage(
                    stringResource(R.string.transfer_camera_needed),
                    actionLabel = stringResource(R.string.transfer_camera_allow),
                    onAction = { permission.launch(Manifest.permission.CAMERA) }
                )
                cameraUnavailable -> CameraMessage(stringResource(R.string.transfer_camera_unavailable))
                else -> androidx.compose.runtime.key(cameraKey) {
                    QrCameraView(
                        onText = { text ->
                            onScanned(text)
                            cameraKey++
                        },
                        onCamera = { works -> cameraUnavailable = !works },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (cameraGranted && !cameraUnavailable) {
            KobbloGroupFooter(stringResource(R.string.transfer_scan_hint))
        }
        Spacer(Modifier.height(8.dp))
        WideButton(
            text = stringResource(R.string.transfer_scan_from_photo),
            icon = AppIcons.Photo,
            secondary = true,
            onClick = {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        )
    }
}

@Composable
private fun CameraMessage(text: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Icon(AppIcons.Camera, contentDescription = null, tint = KobbloDesign.colors.pewter, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = KobbloType.callout, color = KobbloDesign.colors.smoke, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            Spacer(Modifier.height(12.dp))
            com.kobblo.gallery.file.explorer.transfer.ui.SmallCapsule(actionLabel, onClick = onAction, filled = true)
        }
    }
}

@Composable
private fun AddressTab(busy: Boolean, onConnect: (String, Int?) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    val hostOk = PairingCode.isValidHost(host.trim())
    val portValue = port.trim().toIntOrNull()
    val portOk = port.isBlank() || portValue in 1..65535

    KobbloGroup(modifier = Modifier.padding(top = 8.dp)) {
        FieldRow(
            label = stringResource(R.string.transfer_address_label),
            value = host,
            onValue = { host = it },
            placeholder = "192.168.1.20",
            keyboard = KeyboardType.Uri,
            showDivider = true
        )
        FieldRow(
            label = stringResource(R.string.transfer_port_label),
            value = port,
            onValue = { port = it.filter(Char::isDigit).take(5) },
            placeholder = stringResource(R.string.transfer_port_automatic),
            keyboard = KeyboardType.Number,
            showDivider = false
        )
    }
    KobbloGroupFooter(stringResource(R.string.transfer_address_note))
    Spacer(Modifier.height(8.dp))
    WideButton(
        text = stringResource(R.string.transfer_connect_action),
        enabled = hostOk && portOk && !busy,
        onClick = { onConnect(host.trim(), portValue) }
    )
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboard: KeyboardType,
    showDivider: Boolean
) {
    val colors = KobbloDesign.colors
    KobbloRow(
        title = label,
        showDivider = showDivider,
        trailing = {
            Box(modifier = Modifier.width(180.dp), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) {
                    Text(placeholder, style = KobbloType.body, color = colors.pewter, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValue,
                    singleLine = true,
                    textStyle = KobbloType.body.copy(color = colors.ink, textAlign = TextAlign.End),
                    cursorBrush = SolidColor(colors.action),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

@Composable
private fun ManualJoin(manual: WifiJoinState.Manual, onJoined: () -> Unit) {
    val context = LocalContext.current
    val wifi = manual.wifi
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        NoteRow(
            AppIcons.Waves,
            stringResource(if (manual.failed) R.string.transfer_join_failed else R.string.transfer_join_manual, wifi.ssid),
            danger = manual.failed
        )
        KobbloGroup {
            KobbloRow(title = stringResource(R.string.transfer_network_name), showDivider = wifi.password != null, trailing = {
                Text(wifi.ssid, style = KobbloType.callout, color = KobbloDesign.colors.smoke)
            })
            wifi.password?.let { password ->
                KobbloRow(
                    title = stringResource(R.string.transfer_network_password),
                    subtitle = password,
                    trailing = {
                        Text(stringResource(R.string.transfer_copy), style = KobbloType.callout, color = KobbloDesign.colors.action)
                    },
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Wi-Fi", password))
                    }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        WideButton(
            text = stringResource(R.string.transfer_open_wifi_settings),
            secondary = true,
            onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        )
        Spacer(Modifier.height(8.dp))
        WideButton(text = stringResource(R.string.transfer_join_done), onClick = onJoined)
    }
}

@Composable
private fun SavedDevices(saved: List<SavedPeer>) {
    val online by PeerDirectory.online.collectAsState()
    var opened by remember { mutableStateOf<String?>(null) }
    opened?.let { KnownDeviceSheet(it, onDismiss = { opened = null }) }
    KobbloGroupHeader(title = stringResource(R.string.transfer_saved_devices))
    KobbloGroup {
        saved.forEachIndexed { index, peer ->
            val isOnline = online.any { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) }
            KobbloRow(
                title = peer.alias,
                subtitle = listOf(
                    "${peer.host}:${peer.port}",
                    stringResource(if (isOnline) R.string.transfer_device_online else R.string.transfer_device_offline)
                ).joinToString(" · "),
                leading = {
                    Icon(
                        if (peer.verified) AppIcons.ShieldCheck else deviceIcon(peer.deviceType),
                        contentDescription = null,
                        tint = KobbloDesign.colors.action,
                        modifier = Modifier.size(24.dp)
                    )
                },
                trailing = { KobbloChevron() },
                showDivider = index < saved.lastIndex,
                onClick = { opened = peer.fingerprint }
            )
        }
    }
    KobbloGroupFooter(stringResource(R.string.transfer_saved_note))
}
