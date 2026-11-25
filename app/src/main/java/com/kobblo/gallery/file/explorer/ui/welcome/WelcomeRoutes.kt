package com.kobblo.gallery.file.explorer.ui.welcome

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kobblo.gallery.file.explorer.AppAction
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.smart.SmartFeaturesPage
import com.kobblo.gallery.file.explorer.ui.hub.HubRoute
import com.kobblo.gallery.file.explorer.ui.hub.HubScreen
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace

/** The full screens of this area, opened from Settings. Open one with `Hub.open(...)`. */
sealed interface WelcomeRoute : HubRoute {
    data object Permissions : WelcomeRoute
    data object SmartFeatures : WelcomeRoute
}

@Composable
fun WelcomeRouteContent(route: WelcomeRoute, onAction: (AppAction) -> Unit) {
    when (route) {
        WelcomeRoute.Permissions -> PermissionsPage(onRequestFiles = { onAction(AppAction.RequestStorageAccess) })
        WelcomeRoute.SmartFeatures -> SmartFeaturesPage()
    }
}

/** Settings, Permissions: the welcome's switches, for changing them later. */
@Composable
private fun PermissionsPage(onRequestFiles: () -> Unit) {
    HubScreen(title = stringResource(R.string.access_settings_row), backLabel = stringResource(R.string.settings_title)) {
        val (access, refresh) = rememberAccess()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            AccessGroups(access = access, onRefresh = refresh, onRequestFiles = onRequestFiles, inWelcome = false)
            Spacer(Modifier.height(KobbloSpace.lg + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
        }
    }
}
