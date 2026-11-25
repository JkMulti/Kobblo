package com.kobblo.gallery.file.explorer.transfer.hotspot

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyShareRepository
import com.kobblo.gallery.file.explorer.transfer.connect.QrImage
import com.kobblo.gallery.file.explorer.transfer.connect.myPairingCode
import com.kobblo.gallery.file.explorer.transfer.ui.NoteRow
import com.kobblo.gallery.file.explorer.transfer.ui.WideButton
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/**
 * For two phones with no Wi-Fi in common. This phone makes a small network of its own, and the other
 * scans a code to join it. Nothing here goes near the internet, and it doesn't use mobile data.
 */
@Composable
fun CreateConnectionScreen() {
    val context = LocalContext.current
    val state by LocalHotspot.state.collectAsState()
    val isReceiving by NearbyShareRepository.isReceiving.collectAsState()

    LaunchedEffect(Unit) {
        // The other phone is going to connect to this one, so the server has to be up.
        NearbyShareRepository.turnOnReceiving(context)
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) LocalHotspot.start(context)
        else {
            LocalHotspot.clearFailure()
            LocalHotspot.start(context) // Lets the system answer, so the failure shown is the real one.
        }
    }

    HubScreen(title = stringResource(R.string.transfer_create_title), backLabel = stringResource(R.string.transfer_send_tab)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
        ) {
            when (val current = state) {
                HotspotState.Off -> {
                    KobbloGroupFooter(stringResource(R.string.transfer_create_intro), modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(8.dp))
                    WideButton(
                        text = stringResource(R.string.transfer_create_start),
                        icon = AppIcons.Waves,
                        onClick = { permissions.launch(LocalHotspot.requiredPermissions()) }
                    )
                }
                HotspotState.Starting -> Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action)
                }
                is HotspotState.Failed -> {
                    NoteRow(AppIcons.Alert, failureText(current.reason), danger = true, modifier = Modifier.padding(top = 8.dp))
                    if (current.reason == HotspotState.Reason.LocationOff) {
                        WideButton(
                            text = stringResource(R.string.transfer_open_location_settings),
                            secondary = true,
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    WideButton(
                        text = stringResource(R.string.transfer_try_again),
                        onClick = {
                            LocalHotspot.clearFailure()
                            permissions.launch(LocalHotspot.requiredPermissions())
                        }
                    )
                    KobbloGroupFooter(stringResource(R.string.transfer_create_fallback), modifier = Modifier.padding(top = 8.dp))
                }
                is HotspotState.Running -> RunningHotspot(current, isReceiving)
            }
        }
    }
}

@Composable
private fun RunningHotspot(hotspot: HotspotState.Running, isReceiving: Boolean) {
    val context = LocalContext.current
    val wifi = hotspot.wifi
    val code = remember(hotspot, isReceiving) { if (isReceiving) myPairingCode(context, wifi) else null }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(8.dp))
        if (code != null) {
            QrImage(
                text = code.encode(),
                description = stringResource(R.string.transfer_my_code_description),
                modifier = Modifier.padding(horizontal = 48.dp).fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.transfer_create_scan_prompt),
                style = KobbloType.callout,
                color = KobbloDesign.colors.smoke,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = KobbloSpace.screen)
            )
        } else {
            com.kobblo.gallery.file.explorer.ui.design.KobbloLoader(color = KobbloDesign.colors.action, modifier = Modifier.padding(32.dp))
        }
    }
    Spacer(Modifier.height(KobbloSpace.groupGap))
    KobbloGroup {
        KobbloRow(title = stringResource(R.string.transfer_network_name), showDivider = wifi.password != null, trailing = {
            Text(wifi.ssid, style = KobbloType.callout, color = KobbloDesign.colors.smoke, maxLines = 1)
        })
        wifi.password?.let { password ->
            KobbloRow(title = stringResource(R.string.transfer_network_password), trailing = {
                Text(password, style = KobbloType.callout, color = KobbloDesign.colors.smoke, maxLines = 1)
            })
        }
    }
    KobbloGroupFooter(stringResource(R.string.transfer_create_running_note))
    Spacer(Modifier.height(8.dp))
    WideButton(text = stringResource(R.string.transfer_create_stop), danger = true, onClick = LocalHotspot::stop)
}

@Composable
private fun failureText(reason: HotspotState.Reason): String = stringResource(
    when (reason) {
        HotspotState.Reason.Incompatible -> R.string.transfer_hotspot_incompatible
        HotspotState.Reason.NoChannel -> R.string.transfer_hotspot_no_channel
        HotspotState.Reason.Disallowed -> R.string.transfer_hotspot_disallowed
        HotspotState.Reason.LocationOff -> R.string.transfer_hotspot_location_off
        HotspotState.Reason.NeedsPermission ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.transfer_hotspot_needs_nearby
            else R.string.transfer_hotspot_needs_location
        HotspotState.Reason.NoCredentials -> R.string.transfer_hotspot_no_credentials
        HotspotState.Reason.Unknown -> R.string.transfer_hotspot_unknown
    }
)
