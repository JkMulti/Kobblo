package com.kobblo.gallery.file.explorer.smart

import android.Manifest
import android.content.Context
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.compat.NotificationAsk
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.GradientAlertDialog
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveSwitch
import com.kobblo.gallery.file.explorer.ui.expressive.PageHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertAction
import com.kobblo.gallery.file.explorer.ui.design.KobbloAlertCancel
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupFooter
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSegmentedControl
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.StorageMeter
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth
import com.subhashh.lumen.ai.DownloadState
import com.subhashh.lumen.ai.FeatureStatus
import com.subhashh.lumen.ai.IndexProgress
import com.subhashh.lumen.ai.IndexStatus
import com.subhashh.lumen.ai.LumenAi
import com.subhashh.lumen.ai.ModelPack
import kotlinx.coroutines.delay

/** How a feature is shown in a list: its glyph, its color, and what it's called and does. */
private class FeatureLook(val icon: ImageVector, val tint: Color, val title: Int, val body: Int)

private fun lookOf(pack: ModelPack) = when (pack) {
    ModelPack.Search -> FeatureLook(AppIcons.Sparkles, KobbloTint.Purple, R.string.smart_search, R.string.smart_search_body)
    ModelPack.Text -> FeatureLook(AppIcons.FileText, KobbloTint.Orange, R.string.smart_text, R.string.smart_text_body)
    else -> FeatureLook(AppIcons.Person, KobbloTint.Blue, R.string.smart_people, R.string.smart_people_body)
}

/** Where every feature stands, with the running download and how far reading the library has got. */
@Immutable
private data class SmartStatus(
    val features: Map<ModelPack, FeatureStatus>,
    val download: DownloadState,
    val index: IndexStatus
)

/**
 * [SmartStatus], kept current. Downloads report themselves, but the queue and what's installed
 * don't, so they're looked at again every second while this is on screen.
 */
@Composable
private fun rememberSmartStatus(): SmartStatus {
    val context = LocalContext.current
    val download by LumenAi.models.progress.collectAsState()
    val index by LumenAi.indexing.progress.collectAsState(IndexStatus(null, null, null))
    // Read once here so the page opens showing the truth, then kept current off the main thread.
    val first = remember { SmartFeatures.Offered.associateWith { LumenAi.models.status(context, it) } }
    val features by produceState(first, download) {
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SmartFeatures.Offered.associateWith { LumenAi.models.status(context, it) }
            }
            delay(1000)
        }
    }
    return SmartStatus(features, download, index)
}

private fun Context.size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

/** Download progress notifications need asking for from Android 13, once. Then the downloads start. */
@Composable
private fun rememberDownloadStarter(onStart: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStart() }
    return {
        val wanted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(context).areNotificationsEnabled() && NotificationAsk.isWanted(context)
        if (wanted) {
            NotificationAsk.rememberAsked(context)
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onStart()
        }
    }
}

/** Settings, Smart Features: each feature's download and state, and when the library is read. */
@Composable
fun SmartFeaturesPage() {
    com.kobblo.gallery.file.explorer.ui.hub.HubScreen(
        title = stringResource(R.string.smart_title),
        backLabel = stringResource(R.string.settings_title)
    ) {
        if (!SmartFeatures.isSupported) {
            KobbloMessage(
                icon = AppIcons.Sparkles,
                title = stringResource(R.string.smart_title),
                body = stringResource(R.string.smart_unsupported)
            )
            return@HubScreen
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SmartFeaturesSettings()
            Spacer(Modifier.height(KobbloSpace.lg + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }
}

@Composable
private fun ColumnScope.SmartFeaturesSettings() {
    val context = LocalContext.current
    val prefs = remember { SmartPrefs(context) }
    val status = rememberSmartStatus()
    var quality by remember { mutableStateOf(prefs.searchQuality) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var overnight by remember { mutableStateOf(prefs.overnight) }
    var removing by remember { mutableStateOf<ModelPack?>(null) }
    var pending by remember { mutableStateOf<ModelPack?>(null) }
    val start = rememberDownloadStarter {
        pending?.let { LumenAi.models.download(context, it, wifiOnly) }
        pending = null
    }

    Text(
        text = stringResource(R.string.smart_subtitle),
        style = KobbloType.footnote,
        color = KobbloDesign.colors.smoke,
        modifier = Modifier.padding(horizontal = KobbloSpace.content, vertical = 8.dp)
    )

    KobbloGroupHeader(stringResource(R.string.smart_features_header))
    KobbloGroup {
        SmartFeatures.Offered.forEachIndexed { index, pack ->
            val feature = status.features[pack] ?: FeatureStatus.NotDownloaded
            val on = feature !is FeatureStatus.NotDownloaded
            FeatureRow(
                pack = pack,
                subtitle = statusText(context, pack, feature, status, wifiOnly),
                on = on,
                showDivider = index < SmartFeatures.Offered.lastIndex,
                onToggle = {
                    when {
                        !on -> {
                            pending = pack
                            start()
                        }
                        feature is FeatureStatus.Queued || feature is FeatureStatus.Downloading ->
                            LumenAi.models.cancel(context, pack, wifiOnly)
                        else -> removing = pack
                    }
                },
                below = {
                    val downloading = feature as? FeatureStatus.Downloading
                    if (downloading != null && downloading.total > 0) {
                        StorageMeter(
                            fraction = (downloading.done.toFloat() / downloading.total).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                    if (feature is FeatureStatus.UpdateAvailable) {
                        TextButton(onClick = { LumenAi.models.download(context, pack, wifiOnly) }) {
                            Text(stringResource(R.string.smart_update), color = KobbloDesign.colors.action)
                        }
                    }
                    if (pack == ModelPack.Search) {
                        QualityPicker(
                            quality = quality,
                            onChange = {
                                quality = it
                                prefs.searchQuality = it
                                // Search that's already on fetches the model it now needs.
                                if (on) LumenAi.models.download(context, ModelPack.Search, wifiOnly)
                            },
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                }
            )
        }
    }
    KobbloGroupFooter(stringResource(R.string.smart_quality_footer))

    KobbloGroupHeader(stringResource(R.string.smart_downloads_header))
    KobbloGroup {
        KobbloRow(
            title = stringResource(R.string.smart_wifi_only),
            subtitle = stringResource(R.string.smart_wifi_only_body),
            trailing = {
                ExpressiveSwitch(checked = wifiOnly, onCheckedChange = {
                    wifiOnly = it
                    prefs.wifiOnly = it
                })
            }
        )
    }
    KobbloGroupFooter(stringResource(R.string.smart_source_footer))

    KobbloGroupHeader(stringResource(R.string.smart_indexing_header))
    KobbloGroup {
        ScheduleRow(R.string.smart_while_charging, R.string.smart_while_charging_body, selected = !overnight, showDivider = true) {
            overnight = false
            prefs.overnight = false
            LumenAi.indexing.start(context)
        }
        ScheduleRow(R.string.smart_overnight, R.string.smart_overnight_body, selected = overnight, showDivider = true) {
            overnight = true
            prefs.overnight = true
            LumenAi.indexing.start(context)
        }
        KobbloRow(
            title = stringResource(R.string.smart_index_now),
            titleColor = KobbloDesign.colors.action,
            enabled = status.features.values.any { it is FeatureStatus.Ready || it is FeatureStatus.UpdateAvailable },
            onClick = { LumenAi.indexing.runNow(context) }
        )
    }
    KobbloGroupFooter(stringResource(R.string.smart_index_footer))
    Spacer(Modifier.height(KobbloSpace.lg))

    removing?.let { pack ->
        val name = stringResource(lookOf(pack).title)
        GradientAlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.smart_remove_title, name)) },
            text = {
                Text(stringResource(if (pack == ModelPack.Faces) R.string.smart_remove_people_body else R.string.smart_remove_body))
            },
            dismissButton = { KobbloAlertCancel(onClick = { removing = null }) },
            confirmButton = {
                KobbloAlertAction(
                    label = stringResource(R.string.smart_remove),
                    destructive = true,
                    onClick = {
                        LumenAi.models.remove(context, pack, wifiOnly)
                        removing = null
                    }
                )
            }
        )
    }
}

/** What a feature is doing, in a few words. */
private fun statusText(context: Context, pack: ModelPack, feature: FeatureStatus, status: SmartStatus, wifiOnly: Boolean): String {
    val reading: IndexProgress? = when (pack) {
        ModelPack.Search -> status.index.search
        ModelPack.Text -> status.index.text
        else -> status.index.faces
    }
    return when (feature) {
        FeatureStatus.NotDownloaded -> context.getString(R.string.smart_status_off, context.size(SmartFeatures.downloadBytes(context, pack)))
        FeatureStatus.Queued ->
            context.getString(if (status.download is DownloadState.Waiting && wifiOnly) R.string.smart_status_waiting else R.string.smart_status_queued)
        is FeatureStatus.Downloading ->
            context.getString(R.string.smart_status_downloading, context.size(feature.done), context.size(feature.total))
        FeatureStatus.UpdateAvailable -> context.getString(R.string.smart_status_update)
        FeatureStatus.Ready ->
            if (reading != null && reading.indexed < reading.total) {
                context.getString(R.string.smart_status_reading, "%,d".format(reading.indexed), "%,d".format(reading.total))
            } else {
                context.getString(R.string.smart_status_ready)
            }
    }
}

@Composable
private fun FeatureRow(
    pack: ModelPack,
    subtitle: String,
    on: Boolean,
    showDivider: Boolean,
    onToggle: () -> Unit,
    below: @Composable ColumnScope.() -> Unit = {}
) {
    val haptics = LocalHaptics.current
    val look = lookOf(pack)
    val toggle = {
        haptics.tap()
        onToggle()
    }
    KobbloRow(
        title = stringResource(look.title),
        subtitle = subtitle,
        leading = { KobbloIconBadge(icon = look.icon, color = look.tint) },
        trailing = { ExpressiveSwitch(checked = on, onCheckedChange = { toggle() }) },
        below = below,
        showDivider = showDivider,
        onClick = toggle
    )
}

/** Accurate or Fast, with what the one chosen downloads beneath. */
@Composable
private fun QualityPicker(quality: SearchQuality, onChange: (SearchQuality) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recommended = remember { SearchQuality.recommended(context) }
    val sizes = remember { SearchQuality.entries.associateWith { context.size(SmartFeatures.searchBytes(context, it)) } }
    Column(modifier) {
        KobbloSegmentedControl(
            options = listOf(stringResource(R.string.smart_quality_accurate), stringResource(R.string.smart_quality_fast)),
            selectedIndex = quality.ordinal,
            onSelect = { onChange(SearchQuality.entries[it]) }
        )
        Text(
            text = stringResource(
                if (quality == recommended) R.string.smart_quality_size_recommended else R.string.smart_quality_size,
                sizes.getValue(quality)
            ),
            style = KobbloType.caption,
            color = KobbloDesign.colors.smoke,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun ScheduleRow(title: Int, body: Int, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    KobbloRow(
        title = stringResource(title),
        subtitle = stringResource(body),
        trailing = {
            if (selected) Icon(AppIcons.Check, contentDescription = null, tint = KobbloDesign.colors.action)
        },
        showDivider = showDivider,
        onClick = onClick
    )
}

/** The welcome's Smart Features choices, made before anything downloads. */
@androidx.compose.runtime.Stable
internal class SmartChoices(context: Context) {
    val recommended = SearchQuality.recommended(context)
    var quality by mutableStateOf(recommended)
    var wanted by mutableStateOf(SmartFeatures.Offered.toSet())
    var wifiOnly by mutableStateOf(SmartPrefs(context).wifiOnly)

    /** What the chosen features download. Sizes depend on the search quality, so the library is told it first. */
    fun total(context: Context): Long {
        SmartPrefs(context).searchQuality = quality
        return wanted.sumOf { SmartFeatures.downloadBytes(context, it) }
    }
}

@Composable
internal fun rememberSmartChoices(): SmartChoices {
    val context = LocalContext.current
    return remember { SmartChoices(context) }
}

/** Starts the chosen downloads, asking for notifications first where that's needed, then calls [onStarted]. */
@Composable
internal fun rememberSmartDownload(choices: SmartChoices, onStarted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    return rememberDownloadStarter {
        val prefs = SmartPrefs(context)
        prefs.wifiOnly = choices.wifiOnly
        prefs.searchQuality = choices.quality
        for (pack in SmartFeatures.Offered) if (pack in choices.wanted) LumenAi.models.download(context, pack, choices.wifiOnly)
        onStarted()
    }
}

/** The primary button's words: what downloading the choices costs, or just Continue when that's nothing. */
@Composable
internal fun buttonLabel(total: Long): String {
    val context = LocalContext.current
    return if (total == 0L) stringResource(R.string.welcome_continue)
    else stringResource(R.string.smart_welcome_download, context.size(total))
}

/** The choices themselves: each feature, search quality, and Wi-Fi only. */
@Composable
internal fun SmartWelcomeChoices(choices: SmartChoices) {
    Column(Modifier.fillMaxWidth()) {
        KobbloGroup {
            SmartFeatures.Offered.forEachIndexed { index, pack ->
                val on = pack in choices.wanted
                FeatureRow(
                    pack = pack,
                    subtitle = stringResource(lookOf(pack).body),
                    on = on,
                    showDivider = index < SmartFeatures.Offered.lastIndex,
                    onToggle = { choices.wanted = if (on) choices.wanted - pack else choices.wanted + pack },
                    below = {
                        if (pack == ModelPack.Search && on) {
                            QualityPicker(
                                quality = choices.quality,
                                onChange = { choices.quality = it },
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                    }
                )
            }
        }
        Spacer(Modifier.height(KobbloSpace.groupGap))
        KobbloGroup {
            KobbloRow(
                title = stringResource(R.string.smart_wifi_only),
                subtitle = stringResource(R.string.smart_wifi_only_body),
                trailing = { ExpressiveSwitch(checked = choices.wifiOnly, onCheckedChange = { choices.wifiOnly = it }) }
            )
        }
        KobbloGroupFooter(stringResource(R.string.smart_source_footer))
    }
}
