package com.kobblo.gallery.file.explorer.ui.expressive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.AppBrand
import com.kobblo.gallery.file.explorer.BuildConfig
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.BrandMark
import com.kobblo.gallery.file.explorer.ui.design.KobbloChevron
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroupHeader
import com.kobblo.gallery.file.explorer.ui.design.KobbloIconBadge
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloTint
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.kobbloReadableWidth

/** What the app is, which version this is, and the three ways out to its site, its policy and its makers. */
@Composable
fun ExpressiveAboutScreen(
    appName: String = AppBrand.NAME,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val smoke = KobbloDesign.colors.smoke
    Column(modifier = modifier.kobbloReadableWidth().navigationBarsPadding()) {
        PageHeader(
            title = stringResource(R.string.about_nav),
            onBack = onClose,
            backLabel = stringResource(R.string.settings_nav)
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        ) {
            // The app itself, on the canvas with nothing around it.
            BrandMark(size = 72.dp, modifier = Modifier.padding(top = 8.dp))
            Text(
                text = appName,
                style = KobbloType.title,
                color = KobbloDesign.colors.ink,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                text = stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                style = KobbloType.footnote,
                color = smoke,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )
            Text(
                text = stringResource(R.string.screen_about_description, appName),
                style = KobbloType.callout,
                color = smoke,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            KobbloGroupHeader(title = stringResource(R.string.screen_about_links))
            AboutLinks()

            Text(
                text = stringResource(R.string.screen_about_made_with),
                style = KobbloType.footnote,
                color = smoke,
                modifier = Modifier.padding(vertical = 16.dp)
            )
            // Room for the tab bar, and the mini player above it while music plays.
            Spacer(
                Modifier.height(
                    KobbloSpace.tabBarClearance + KobbloSpace.miniPlayerClearance +
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                )
            )
        }
    }
}

/** One way out of the app from About: where it leads, and the intent that goes there. */
private class AboutLink(val title: String, val detail: String, val glyph: ImageVector, val tint: Color, val intent: Intent)

/** The website, the privacy policy and the address for feedback, all read from [AppBrand]. */
@Composable
private fun AboutLinks() {
    val context = LocalContext.current
    val links = listOf(
        AboutLink(
            title = stringResource(R.string.screen_about_website),
            detail = AppBrand.WEBSITE_LABEL,
            glyph = AppIcons.Globe,
            tint = KobbloTint.Blue,
            intent = browserIntent(AppBrand.WEBSITE_URL)
        ),
        AboutLink(
            title = stringResource(R.string.settings_privacy_policy),
            detail = stringResource(R.string.screen_about_privacy_subtitle),
            glyph = AppIcons.ShieldCheck,
            tint = KobbloTint.Green,
            intent = browserIntent(AppBrand.PRIVACY_URL)
        ),
        AboutLink(
            title = stringResource(R.string.screen_about_feedback),
            detail = AppBrand.SUPPORT_EMAIL,
            glyph = AppIcons.Mail,
            tint = KobbloTint.Indigo,
            intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + AppBrand.SUPPORT_EMAIL))
        )
    )
    KobbloGroup {
        links.forEachIndexed { index, link ->
            KobbloRow(
                title = link.title,
                subtitle = link.detail,
                leading = { KobbloIconBadge(link.glyph, link.tint) },
                trailing = { KobbloChevron() },
                showDivider = index < links.lastIndex,
                onClick = { context.openOutside(link.intent) }
            )
        }
    }
}

private fun browserIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Hands [intent] to whichever app takes it. A phone with no browser or no mail app has none, and then nothing happens. */
private fun Context.openOutside(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: Exception) {
        // Nothing to show: the row simply has nowhere to lead on this phone.
    }
}
