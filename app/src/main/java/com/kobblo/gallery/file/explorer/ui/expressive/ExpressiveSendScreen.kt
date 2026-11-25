package com.kobblo.gallery.file.explorer.ui.expressive

import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import com.kobblo.gallery.file.explorer.ui.design.ambientShadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.AppState
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.localsend.NearbyShareService
import com.kobblo.gallery.file.explorer.transfer.TransferEntry
import com.kobblo.gallery.file.explorer.transfer.TransferRoute
import com.kobblo.gallery.file.explorer.transfer.connect.NetworkInspector
import com.kobblo.gallery.file.explorer.transfer.connect.PeerDirectory
import com.kobblo.gallery.file.explorer.transfer.connect.ScanState
import com.kobblo.gallery.file.explorer.transfer.connect.SubnetScan
import com.kobblo.gallery.file.explorer.transfer.core.DeviceName
import com.kobblo.gallery.file.explorer.transfer.core.NetworkFacts
import com.kobblo.gallery.file.explorer.transfer.core.PeerMerge
import com.kobblo.gallery.file.explorer.transfer.hotspot.HotspotState
import com.kobblo.gallery.file.explorer.transfer.hotspot.LocalHotspot
import com.kobblo.gallery.file.explorer.transfer.hotspot.WifiJoinState
import com.kobblo.gallery.file.explorer.transfer.hotspot.WifiJoiner
import com.kobblo.gallery.file.explorer.transfer.send.SendPlanner
import com.kobblo.gallery.file.explorer.transfer.send.TransferFormat
import com.kobblo.gallery.file.explorer.transfer.ui.KnownDeviceSheet
import com.kobblo.gallery.file.explorer.transfer.ui.SmallCapsule
import com.kobblo.gallery.file.explorer.transfer.ui.TransferStatusCard
import com.kobblo.gallery.file.explorer.transfer.ui.WideButton
import com.kobblo.gallery.file.explorer.transfer.ui.deviceIcon
import com.kobblo.gallery.file.explorer.transfer.ui.peerSubtitle
import com.kobblo.gallery.file.explorer.transfer.web.WebShareController
import com.kobblo.gallery.file.explorer.ui.hub.Hub
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.min
import kotlin.math.sin

/**
 * Expressive Send Screen:
 * - A radar of waves leaving your device, which nearby devices light up to (respects reduce-motion)
 * - Real device alias in the centre
 * - Real discovered nearby devices (LocalSend v2 protocol) positioned on radar & listed below
 * - What's chosen to send, one tap from the full picker
 * - Devices you've used before first, one tap away, and named even when they aren't here yet
 * - When nothing appears, why that might be and what to try, having already looked around the network
 * - Real-time active transfer card with progress, speed and time left
 * - The other ways to connect, for when discovery can't work
 */
@Composable
fun ExpressiveSendScreen(
    state: AppState,
    onAction: (AppAction) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val staged = state.stagedNearbyFiles
    val peers = state.nearbyPeers
    val context = LocalContext.current
    val localAlias = state.nearbyDeviceName.ifEmpty { DeviceName.default(context) }

    // Choosing several devices at once is a small mode of the same list.
    var choosingSeveral by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(peers.size, staged.isEmpty()) {
        if (peers.size < 2 || staged.isEmpty()) {
            choosingSeveral = false
            chosen = emptySet()
        }
    }

    val onPeer: (NearbyPeer) -> Unit = { peer ->
        when {
            choosingSeveral -> chosen = if (peer.id in chosen) chosen - peer.id else chosen + peer.id
            // Tapping a device with nothing chosen goes to the picker, which then sends straight to it.
            staged.isEmpty() -> Hub.open(TransferRoute.Picker(listOf(peer)))
            else -> onAction(AppAction.NearbyShareAction.SendToPeer(peer))
        }
    }

    val saved by PeerDirectory.saved.collectAsState()
    val absent = remember(saved, peers) { PeerMerge.absent(saved, peers) }
    var openedDevice by remember { mutableStateOf<String?>(null) }
    openedDevice?.let { KnownDeviceSheet(it, onDismiss = { openedDevice = null }) }

    // Whenever this tab is what's on screen: visible, unless the person said not to be, and not idle. Coming
    // back to it after the service stopped by itself shouldn't leave it looking on while being off.
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(lifecycle) {
        NearbyShareRepository.attach(context)
        TransferEntry.publishShortcut(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!NearbyShareRepository.isReceiving.value) NearbyShareRepository.startService(context)
            while (true) {
                NearbyShareService.touch(context)
                SubnetScan.refresh(context)
                delay(10_000)
            }
        }
    }
    // Announcing has had its chance. Networks that block it get asked directly, once per visit.
    LaunchedEffect(state.isNearbyReceiving) {
        if (!state.isNearbyReceiving) {
            SubnetScan.clear()
            return@LaunchedEffect
        }
        delay(6_000)
        if (NearbyShareRepository.peers.value.none { !it.known }) SubnetScan.start(context)
    }

    // A send is usually started from further down the list, so it comes back up to show it going.
    val listState = rememberLazyListState()
    val transferKey = state.activeNearbyTransfer?.let { it.peer.id to it.isIncoming }
    LaunchedEffect(transferKey) {
        if (transferKey != null) listState.animateScrollToItem(0)
    }
    // The same when something new is chosen, wherever that happened: what's chosen sits under the radar.
    val lastStagedSize = remember { intArrayOf(staged.size) }
    LaunchedEffect(staged.size) {
        if (staged.size > lastStagedSize[0]) listState.animateScrollToItem(0)
        lastStagedSize[0] = staged.size
    }

    // The header stays above the list, as on the other tabs, so nothing scrolls up under the status bar.
    val largeTitle = rememberLargeTitleState()
    Column(modifier = modifier.kobbloReadableWidth().nestedScroll(largeTitle.connection)) {
    ExpressiveScreenHeader(
        title = stringResource(R.string.send_and_receive),
        showSearch = false,
        largeTitle = largeTitle,
        modifier = Modifier.padding(horizontal = KobbloSpace.screen)
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = KobbloSpace.tabBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                if (state.currentAudioFile != null) KobbloSpace.miniPlayerClearance else 0.dp
        )
    ) {
        item(key = "transfer") {
            TransferStatusCard()
        }

        // Radar Box. It gives way once there's something to send, so the devices to tap are on screen.
        item(key = "radar") {
            val radarHeight by androidx.compose.animation.core.animateDpAsState(
                targetValue = if (staged.isEmpty()) 240.dp else 150.dp,
                animationSpec = com.kobblo.gallery.file.explorer.ui.design.KobbloMotion.smooth(),
                label = "radar_height"
            )
            RadarCanvas(
                ownName = localAlias,
                nearby = peers,
                visible = state.isNearbyReceiving,
                onPick = onPeer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = KobbloSpace.screen)
                    .height(radarHeight)
            )
            Spacer(Modifier.height(12.dp))
        }

        item(key = "staged") {
            StagedFiles(staged, onAction)
        }

        // Nearby Devices Section
        item(key = "nearby") {
            KobbloGroupHeader(title = stringResource(R.string.nearby_devices, peers.size))
            if (peers.isEmpty() && absent.isEmpty()) {
                NobodyHere(state.isNearbyReceiving)
            } else {
                val unencrypted = stringResource(R.string.transfer_not_encrypted)
                KobbloGroup {
                    peers.forEachIndexed { index, peer ->
                        val deviceSaved = saved.any { it.fingerprint.equals(peer.fingerprint, ignoreCase = true) }
                        KobbloRow(
                            title = peer.alias,
                            subtitle = peerSubtitle(context, peer, unencrypted, PeerMerge.nameIsShared(peer, peers)),
                            leading = {
                                Icon(deviceIcon(peer.deviceType), contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(26.dp))
                            },
                            trailing = {
                                if (peer.verified) {
                                    Icon(
                                        AppIcons.ShieldCheck,
                                        contentDescription = stringResource(R.string.transfer_verified),
                                        tint = KobbloDesign.colors.action,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (choosingSeveral) {
                                    SelectionCircle(peer.id in chosen, Modifier.size(24.dp), onSurface = true)
                                } else {
                                    SmallCapsule(stringResource(R.string.transfer_send), onClick = { onPeer(peer) }, filled = staged.isNotEmpty())
                                }
                            },
                            showDivider = index < peers.lastIndex || absent.isNotEmpty(),
                            onLongClick = if (deviceSaved) ({ openedDevice = peer.fingerprint }) else null,
                            onClick = { onPeer(peer) }
                        )
                    }
                    // Named, so the next step is plain: it's the other phone that has to open this tab.
                    absent.forEachIndexed { index, device ->
                        KobbloRow(
                            title = device.alias,
                            subtitle = stringResource(R.string.transfer_not_here),
                            titleColor = KobbloDesign.colors.smoke,
                            leading = {
                                Icon(deviceIcon(device.deviceType), contentDescription = null, tint = KobbloDesign.colors.pewter, modifier = Modifier.size(26.dp))
                            },
                            showDivider = index < absent.lastIndex,
                            onLongClick = { openedDevice = device.fingerprint },
                            onClick = { openedDevice = device.fingerprint }
                        )
                    }
                }
                if (peers.isEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    NobodyHere(state.isNearbyReceiving)
                } else if (state.isNearbyReceiving) {
                    LookAgain()
                }
                if (staged.isNotEmpty() && peers.size >= 2) {
                    Spacer(Modifier.height(12.dp))
                    if (choosingSeveral) {
                        WideButton(
                            text = pluralStringResource(R.plurals.transfer_send_to_devices, chosen.size, chosen.size),
                            enabled = chosen.isNotEmpty(),
                            onClick = {
                                NearbyShareRepository.sendStagedFiles(context, peers.filter { it.id in chosen })
                                choosingSeveral = false
                                chosen = emptySet()
                            }
                        )
                    } else {
                        WideButton(
                            text = stringResource(R.string.transfer_send_to_several),
                            secondary = true,
                            onClick = { choosingSeveral = true }
                        )
                    }
                }
            }
        }

        // Receive Row with ExpressiveSwitch
        item(key = "receive") {
            Spacer(Modifier.height(KobbloSpace.groupGap))
            KobbloGroup {
                KobbloRow(
                    title = stringResource(R.string.transfer_receive_files),
                    subtitle = stringResource(if (state.isNearbyReceiving) R.string.transfer_visible else R.string.transfer_not_visible),
                    leading = { KobbloIconBadge(AppIcons.Phone, KobbloTint.Green) },
                    trailing = {
                        ExpressiveSwitch(
                            checked = state.isNearbyReceiving,
                            onCheckedChange = { onAction(AppAction.NearbyShareAction.ToggleReceive(it)) }
                        )
                    }
                )
            }
            com.kobblo.gallery.file.explorer.transfer.ui.NotificationsOffLine()
        }

        item(key = "more") {
            MoreWaysToConnect()
        }
    }
    }
}

/** Under a list that's missing someone: the same look around, on request. */
@Composable
private fun LookAgain() {
    val context = LocalContext.current
    val scan by SubnetScan.state.collectAsState()
    val scanning = scan is ScanState.Scanning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.content, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(if (scanning) R.string.transfer_scanning else R.string.transfer_dont_see_it),
            style = KobbloType.footnote,
            color = KobbloDesign.colors.smoke
        )
        if (!scanning) {
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.transfer_look_again),
                style = KobbloType.footnote.copy(fontWeight = FontWeight.Medium),
                color = KobbloDesign.colors.action,
                modifier = Modifier.clickable { SubnetScan.start(context) }
            )
        }
    }
}

/** Why the list is empty, as far as this phone can tell, and the next thing to try. */
@Composable
private fun NobodyHere(receiving: Boolean) {
    val context = LocalContext.current
    val scan by SubnetScan.state.collectAsState()
    // Reading the interfaces is off the main thread, and until it's done nothing is assumed to be wrong.
    val facts by produceState(NetworkFacts(addresses = listOf(""), vpnOn = false)) {
        while (true) {
            value = withContext(Dispatchers.IO) { NetworkInspector.facts(context) }
            delay(4_000)
        }
    }
    val nothing = scan is ScanState.Finished
    val (title, hint) = when {
        !receiving -> R.string.transfer_receiving_off to R.string.transfer_turn_on_to_find
        facts.addresses.isEmpty() -> R.string.transfer_no_wifi to R.string.transfer_no_wifi_hint
        facts.vpnOn -> R.string.transfer_vpn_on to R.string.transfer_vpn_hint
        nothing -> R.string.transfer_scan_nothing to R.string.transfer_scan_nothing_hint
        scan is ScanState.Scanning -> R.string.transfer_scanning to R.string.transfer_same_wifi_hint
        else -> R.string.transfer_searching to R.string.transfer_same_wifi_hint
    }
    KobbloGroup {
        KobbloRow(
            title = stringResource(title),
            titleColor = KobbloDesign.colors.smoke,
            trailing = if (receiving && facts.addresses.isNotEmpty() && nothing) {
                { SmallCapsule(stringResource(R.string.transfer_look_again), onClick = { SubnetScan.start(context) }) }
            } else null
        )
    }
    // Under the row rather than in it, because this is the part that gets someone unstuck and must be whole.
    KobbloGroupFooter(stringResource(hint))
}

/** What's chosen, or the one button to choose it. */
@Composable
private fun StagedFiles(staged: List<FileItem>, onAction: (AppAction) -> Unit) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    if (staged.isEmpty()) {
        WideButton(
            text = stringResource(R.string.transfer_choose_what_to_send),
            icon = AppIcons.Add,
            onClick = { Hub.open(TransferRoute.Picker()) }
        )
        return
    }
    // Folders only reveal their size by walking them, which happens off the main thread.
    val plan by produceState<SendPlanner.Plan?>(null, staged) {
        value = withContext(Dispatchers.IO) { SendPlanner.plan(context, staged, mayCopy = false) }
    }
    val current = plan
    // A lone note arrives as words, not as a file, so it isn't called one.
    val justANote = staged.singleOrNull()?.kind == com.kobblo.gallery.file.explorer.transfer.send.Notes.KIND
    KobbloGroup {
        Column(modifier = Modifier.padding(KobbloSpace.inset)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            justANote -> stringResource(R.string.transfer_note_title)
                            current != null -> pluralStringResource(R.plurals.transfer_file_count, current.items.size, current.items.size)
                            else -> pluralStringResource(R.plurals.transfer_item_count, staged.size, staged.size)
                        },
                        style = KobbloType.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink
                    )
                    if (current != null && !justANote) {
                        Text(TransferFormat.size(context, current.totalBytes), style = KobbloType.footnote, color = colors.smoke)
                    }
                }
                SmallCapsule(stringResource(R.string.transfer_add), onClick = { Hub.open(TransferRoute.Picker()) })
                Spacer(Modifier.width(8.dp))
                SmallCapsule(stringResource(R.string.clear_action), onClick = { onAction(AppAction.NearbyShareAction.ClearStagedFiles) })
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                items(staged, key = { it.path }) { item ->
                    Row(
                        modifier = Modifier
                            .clip(CapsuleShape)
                            .background(colors.mist)
                            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.name,
                            style = KobbloType.footnote,
                            color = colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = stringResource(R.string.transfer_remove_item, item.name),
                            tint = colors.smoke,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onAction(AppAction.NearbyShareAction.RemoveStagedFile(item)) }
                        )
                    }
                }
            }
        }
    }
    KobbloGroupFooter(stringResource(R.string.transfer_tap_device_hint))
}

/** Everything for when discovery can't work, out of the way but one tap from the tab. */
@Composable
private fun MoreWaysToConnect() {
    val hotspot by LocalHotspot.state.collectAsState()
    val web by WebShareController.state.collectAsState()
    val joined = WifiJoiner.state.collectAsState().value as? WifiJoinState.Joined
    KobbloGroupHeader(title = stringResource(R.string.transfer_more_ways))
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.transfer_connect_title),
            // While joined, this app's traffic all goes over a network with no internet, so it says so.
            subtitle = joined?.let { stringResource(R.string.transfer_join_joined, it.ssid) }
                ?: stringResource(R.string.transfer_connect_subtitle),
            leading = { KobbloIconBadge(AppIcons.Grid, KobbloTint.Blue) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { Hub.open(TransferRoute.Connect) }
        )
        KobbloRow(
            title = stringResource(R.string.transfer_create_title),
            subtitle = stringResource(
                if (hotspot is HotspotState.Running) R.string.transfer_create_subtitle_on else R.string.transfer_create_subtitle
            ),
            leading = { KobbloIconBadge(AppIcons.Waves, KobbloTint.Indigo) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { Hub.open(TransferRoute.CreateConnection) }
        )
        KobbloRow(
            title = stringResource(R.string.web_share_title),
            subtitle = stringResource(if (web.running) R.string.web_share_on else R.string.web_share_subtitle),
            leading = { KobbloIconBadge(AppIcons.Globe, KobbloTint.Teal) },
            trailing = { KobbloChevron() },
            showDivider = true,
            onClick = { Hub.open(TransferRoute.WebShare) }
        )
        KobbloRow(
            title = stringResource(R.string.transfer_history),
            leading = { KobbloIconBadge(AppIcons.Clock, KobbloTint.Gray) },
            trailing = { KobbloChevron() },
            onClick = { Hub.open(TransferRoute.History) }
        )
    }
}

private const val WaveCount = 4
private const val WavePeriodMillis = 4800
private const val WaveSpinMillis = 14000
private val AvatarSize = 64.dp
private val PeerSize = 46.dp
private val WaveTeal = Color(0xFF30B0C7)
private val WaveIndigo = Color(0xFF5856D6)

/** Where wave [index] is in its life at this moment of the [clock], from 0 at birth to 1 when spent. */
private fun wavePhase(clock: Float, index: Int): Float = (clock + index.toFloat() / WaveCount) % 1f

/** Waves leave quickly and slow as they spread, the way rings do on water. */
private fun waveTravel(phase: Float): Float = 1f - (1f - phase).pow(2.2f)

/** A wave appears at once and thins away as it travels. */
private fun waveAlpha(phase: Float): Float = (phase / 0.06f).coerceAtMost(1f) * (1f - phase).pow(1.5f)

/** A beat that peaks as each wave is born and dies away before the next. */
private fun waveBeat(clock: Float): Float = (1f - (clock * WaveCount) % 1f).pow(4f)

/**
 * The sense of being visible nearby. Waves leave your device in a steady rhythm, each with a soft
 * inner glow and an edge that slowly shifts color. Your avatar beats as each is born, nearby devices
 * light up as a wave reaches them, and turning receiving off settles the waves into still rings.
 *
 * Everything that moves reads its value while drawing, so the animation never recomposes this.
 */
@Composable
private fun RadarCanvas(
    ownName: String,
    nearby: List<NearbyPeer>,
    /** Whether this device is receiving, which is what the waves stand for. */
    visible: Boolean,
    onPick: (NearbyPeer) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val density = LocalDensity.current

    val transition = rememberInfiniteTransition(label = "radar")
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(WavePeriodMillis, easing = LinearEasing)),
        label = "radar_clock"
    )
    val spin = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(WaveSpinMillis, easing = LinearEasing)),
        label = "radar_spin"
    )
    // Reduced motion holds the waves still, spread evenly, instead of removing them.
    val time = { if (reduced) 0.125f else clock.value }

    val strength by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(600, easing = com.kobblo.gallery.file.explorer.ui.design.KobbloMotion.EaseInOut),
        label = "radar_strength"
    )
    val avatarTop = lerp(colors.silver, Color(0xFF4DA3FF), strength)
    val avatarBottom = lerp(colors.silver, colors.action, strength)

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val avatarRadiusPx = with(density) { AvatarSize.toPx() / 2f }
        val maxRadiusPx = min(widthPx / 2f, heightPx * 0.62f)

        fun waveRadius(phase: Float) = avatarRadiusPx + (maxRadiusPx - avatarRadiusPx) * waveTravel(phase)

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val glow = Brush.radialGradient(
                        0f to colors.action.copy(alpha = 0f),
                        0.72f to colors.action.copy(alpha = 0f),
                        0.97f to colors.action.copy(alpha = 0.2f),
                        1f to colors.action.copy(alpha = 0f),
                        center = center,
                        radius = maxRadiusPx
                    )
                    val edge = Brush.sweepGradient(
                        listOf(colors.action, WaveTeal, WaveIndigo, colors.action),
                        center = center
                    )
                    val haloRadius = avatarRadiusPx * 2.6f
                    val halo = Brush.radialGradient(
                        0f to colors.action.copy(alpha = 0.34f),
                        1f to colors.action.copy(alpha = 0f),
                        center = center,
                        radius = haloRadius
                    )
                    val edgeWidth = 1.5.dp.toPx()
                    val ringWidth = 1.dp.toPx()
                    onDrawBehind {
                        val now = time()

                        // With receiving off, still rings stand in for the waves.
                        val ringAlpha = (1f - strength) * 0.7f
                        if (ringAlpha > 0.004f) {
                            drawCircle(colors.hairline, maxRadiusPx * 0.5f, center, ringAlpha, Stroke(ringWidth))
                            drawCircle(colors.hairline, maxRadiusPx * 0.78f, center, ringAlpha, Stroke(ringWidth))
                        }

                        repeat(WaveCount) { index ->
                            val phase = wavePhase(now, index)
                            val alpha = waveAlpha(phase) * strength
                            val travel = waveRadius(phase) / maxRadiusPx
                            if (alpha > 0.004f) {
                                // One gradient is scaled to each wave rather than rebuilt every frame.
                                scale(travel, pivot = center) {
                                    drawCircle(glow, radius = maxRadiusPx, center = center, alpha = alpha)
                                    rotate(spin.value, pivot = center) {
                                        drawCircle(
                                            brush = edge,
                                            radius = maxRadiusPx,
                                            center = center,
                                            alpha = alpha,
                                            style = Stroke(edgeWidth / travel)
                                        )
                                    }
                                }
                            }
                        }

                        val beat = if (reduced) 0f else waveBeat(now)
                        drawCircle(
                            brush = halo,
                            radius = haloRadius,
                            center = center,
                            alpha = strength * (0.5f + 0.5f * beat)
                        )
                    }
                }
        )

        // Peers sit behind you, and come and go with a spring.
        nearby.forEachIndexed { index, peer ->
            key(peer.id) {
                val ringFraction = if (index % 2 == 0) 0.56f else 0.82f
                PeerNode(
                    peer = peer,
                    angleDegrees = index * (360f / nearby.size) - 60f,
                    radiusPx = maxRadiusPx * ringFraction,
                    // There's more room across than down, more so once the radar has given way.
                    stretchX = ((widthPx / 2f - with(density) { PeerSize.toPx() }) / (maxRadiusPx * 0.82f)).coerceIn(1f, 2.2f),
                    driftSeed = index,
                    time = time,
                    spin = { if (reduced) 0f else spin.value },
                    pingAt = { now, radius ->
                        var ping = 0f
                        repeat(WaveCount) { wave ->
                            val phase = wavePhase(now, wave)
                            val distance = (waveRadius(phase) - radius) / with(density) { 22.dp.toPx() }
                            ping = maxOf(ping, exp(-distance * distance) * waveAlpha(phase).pow(0.4f))
                        }
                        ping * strength
                    },
                    onClick = { onPick(peer) }
                )
            }
        }

        // You, always at the exact center the waves leave from.
        Box(
            modifier = Modifier
                .size(AvatarSize)
                .graphicsLayer {
                    val bump = 1f + 0.05f * (if (reduced) 0f else waveBeat(time())) * strength
                    scaleX = bump
                    scaleY = bump
                }
                .ambientShadow(CapsuleShape, colors.action.copy(alpha = 0.32f * strength))
                .clip(CapsuleShape)
                .background(Brush.verticalGradient(listOf(avatarTop, avatarBottom))),
            contentAlignment = Alignment.Center
        ) {
            Icon(AppIcons.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
        RadarName(
            name = ownName,
            style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.footnote.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.offset(y = AvatarSize / 2 + 16.dp).widthIn(max = 130.dp)
        )
    }
}

/** A device's name hung beneath its place on the radar: one centered line, cut short if it is too long for it. */
@Composable
private fun RadarName(name: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier) {
    val ink = com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors.ink
    Text(name, style = style, color = ink, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, maxLines = 1, modifier = modifier)
}

@Composable
private fun PeerNode(
    peer: NearbyPeer,
    angleDegrees: Float,
    radiusPx: Float,
    stretchX: Float,
    driftSeed: Int,
    time: () -> Float,
    spin: () -> Float,
    pingAt: (Float, Float) -> Float,
    onClick: () -> Unit
) {
    val colors = com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors
    val haptics = com.kobblo.gallery.file.explorer.haptics.LocalHaptics.current
    val motion = com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
    val source = remember { MutableInteractionSource() }
    val driftPx = with(LocalDensity.current) { 5.dp.toPx() }

    // A device that joins slides its neighbours to their new places, rather than jumping them.
    val angle by animateFloatAsState(angleDegrees, motion.smooth(), label = "peer_angle")
    val radius by animateFloatAsState(radiusPx, motion.smooth(), label = "peer_radius")
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, motion.snappy())
    }

    Box(
        modifier = Modifier
            .size(PeerSize)
            .offset {
                val radians = Math.toRadians(angle.toDouble())
                IntOffset((cos(radians) * radius * stretchX).roundToInt(), (sin(radians) * radius).roundToInt())
            }
            .graphicsLayer {
                val ping = pingAt(time(), radius)
                val settle = entrance.value
                val driftAngle = Math.toRadians((spin() * 2f + driftSeed * 67f).toDouble())
                translationX = (cos(driftAngle) * driftPx).toFloat()
                translationY = (sin(driftAngle) * driftPx).toFloat()
                scaleX = settle * (1f + 0.08f * ping)
                scaleY = settle * (1f + 0.08f * ping)
                alpha = settle.coerceIn(0f, 1f)
            }
            .drawBehind {
                val ping = pingAt(time(), radius)
                if (ping > 0.01f) {
                    drawCircle(
                        color = colors.action.copy(alpha = 0.34f * ping),
                        radius = size.minDimension / 2f * (1f + 0.42f * ping)
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pressScale(pressedScale = 0.9f, interactionSource = source)
                .ambientShadow(CapsuleShape, colors.ambient)
                .clip(CapsuleShape)
                .background(colors.snow)
                .clickable(interactionSource = source, indication = null) {
                    haptics.tap()
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = deviceIcon(peer.deviceType),
                contentDescription = peer.alias,
                tint = colors.action,
                modifier = Modifier.size(24.dp)
            )
        }
        RadarName(
            name = peer.alias,
            style = com.kobblo.gallery.file.explorer.ui.design.KobbloType.caption,
            // Free of the node's own narrow bounds, so a name is as wide as it needs up to its limit.
            modifier = Modifier.offset(y = PeerSize / 2 + 11.dp).wrapContentSize(unbounded = true).widthIn(max = 84.dp)
        )
    }
}
