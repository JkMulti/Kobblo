package com.kobblo.gallery.file.explorer.transfer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.localsend.NearbyPeer
import com.kobblo.gallery.file.explorer.transfer.send.TransferNotices
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveMotion
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons

/** A thin progress bar. Its width glides, so small steps don't flicker. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val colors = KobbloDesign.colors
    val reduced = ExpressiveMotion.isReducedMotion()
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = if (reduced) snap() else KobbloMotion.smooth(),
        label = "transfer_progress"
    )
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(colors.mist, cornerRadius = radius)
        if (animated > 0f) {
            drawRoundRect(colors.action, Offset.Zero, Size((size.width * animated).coerceAtLeast(size.height), size.height), radius)
        }
    }
}

/** The app's standard wide action, on the canvas with the screen's margins. */
@Composable
fun WideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    secondary: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null
) {
    val colors = KobbloDesign.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().padding(horizontal = KobbloSpace.screen).height(com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight),
        shape = CapsuleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary || danger) colors.snow else colors.action,
            contentColor = if (danger) colors.danger else if (secondary) colors.action else colors.onAction,
            disabledContainerColor = colors.mist,
            disabledContentColor = colors.pewter
        ),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small capsule action inside a row. */
@Composable
fun SmallCapsule(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, enabled: Boolean = true) {
    val colors = KobbloDesign.colors
    Box(
        modifier = modifier
            .clip(CapsuleShape)
            .background(if (filled) colors.action else colors.mist)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = KobbloType.callout.copy(fontWeight = FontWeight.Medium),
            color = when {
                !enabled -> colors.pewter
                filled -> colors.onAction
                else -> colors.action
            },
            maxLines = 1
        )
    }
}

/** The glyph for a kind of device. */
fun deviceIcon(deviceType: String): ImageVector = when (deviceType.lowercase()) {
    "desktop", "web", "server", "headless" -> AppIcons.Laptop
    "tablet" -> AppIcons.Tablet
    else -> AppIcons.Phone
}

/**
 * The second line of a device's row: what it is, then when it was last used or where it is, and anything
 * about how it's connected. Two with the same name also get a bit of their fingerprint, since it's the
 * one thing about them that can't be copied.
 */
fun peerSubtitle(context: android.content.Context, peer: NearbyPeer, unencrypted: String, nameIsShared: Boolean = false): String = buildString {
    if (peer.known && peer.lastUsedMs > 0) {
        append(lastUsed(context, peer.lastUsedMs))
    } else {
        append(peer.deviceModel?.takeIf { it.isNotBlank() } ?: peer.deviceType)
        append(" · ").append(peer.ip)
    }
    if (peer.protocol == "http") append(" · ").append(unencrypted)
    if (nameIsShared && peer.fingerprint.length >= 4) append(" · ").append(peer.fingerprint.takeLast(4).uppercase())
}

/** When a device was last sent to or heard from, in the words someone would use. */
fun lastUsed(context: android.content.Context, timeMs: Long): String {
    val now = System.currentTimeMillis()
    if (now - timeMs < android.text.format.DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.transfer_used_just_now)
    return context.getString(
        R.string.transfer_last_used,
        android.text.format.DateUtils.getRelativeTimeSpanString(timeMs, now, android.text.format.DateUtils.MINUTE_IN_MILLIS)
    )
}

/** A message on the canvas: an icon beside a line or two of explanation. */
@Composable
fun NoteRow(icon: ImageVector, text: String, modifier: Modifier = Modifier, danger: Boolean = false) {
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = KobbloSpace.content, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) colors.danger else colors.smoke,
            modifier = Modifier.size(18.dp).padding(top = 1.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text, style = KobbloType.footnote, color = if (danger) colors.danger else colors.smoke)
        }
    }
}

/**
 * Without notifications, a question can only be answered with the app open, and someone sending just waits. Says so
 * where Receive is, with the way to fix it. The system's own question has its one chance, from the main screen.
 */
@Composable
fun NotificationsOffLine(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var canAsk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) canAsk = TransferNotices.canAskOutsideApp(context)
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (canAsk) return
    val colors = KobbloDesign.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(start = KobbloSpace.content, end = KobbloSpace.content, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(AppIcons.Bell, contentDescription = null, tint = colors.smoke, modifier = Modifier.size(18.dp))
        Text(
            androidx.compose.ui.res.stringResource(R.string.transfer_notifications_off),
            style = KobbloType.footnote,
            color = colors.smoke,
            modifier = Modifier.weight(1f)
        )
        SmallCapsule(
            androidx.compose.ui.res.stringResource(R.string.transfer_notifications_turn_on),
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
            }
        )
    }
}
