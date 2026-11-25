package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.ConnectionFormSheet
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloFormField
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton

/** Logging in to a cloud service: the same form as any other location, with its two links beneath. */
@Composable
fun CloudLoginBottomSheet(
    providerName: String,
    providerIcon: ImageVector,
    providerCategory: String = "archives",
    errorMessage: String? = null,
    registerUrl: String? = null,
    recoveryUrl: String? = null,
    onLogin: (email: String, password: String) -> Unit,
    onDismissRequest: () -> Unit
) {
    val hasLinks = registerUrl != null || recoveryUrl != null
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    ConnectionFormSheet(
        title = stringResource(R.string.polish_log_in_to, providerName),
        subtitle = null,
        error = errorMessage?.takeIf { it.isNotBlank() },
        confirmLabel = stringResource(R.string.login_action),
        confirmEnabled = email.isNotBlank() && password.isNotBlank(),
        onConfirm = { onLogin(email.trim(), password) },
        onDismiss = onDismissRequest,
        below = if (hasLinks) {
            { AccountLinks(registerUrl, recoveryUrl) }
        } else null
    ) {
        KobbloFormField(
            label = stringResource(R.string.email_label),
            value = email,
            onValueChange = { email = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next)
        )
        KobbloFormField(
            label = stringResource(R.string.password_label),
            value = password,
            onValueChange = { password = it },
            secret = true,
            showDivider = false,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
        )
    }
}

/** The service's own pages for making an account and for a lost password, each opened in the browser. */
@Composable
private fun AccountLinks(registerUrl: String?, recoveryUrl: String?) {
    val browser = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = KobbloSpace.screen, vertical = KobbloSpace.xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (registerUrl != null) AccountLink(stringResource(R.string.create_account_action)) { browser.openUri(registerUrl) }
        if (recoveryUrl != null) AccountLink(stringResource(R.string.forgot_password)) { browser.openUri(recoveryUrl) }
    }
}

@Composable
private fun AccountLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
        Text(label, style = KobbloType.footnote, color = KobbloDesign.colors.action)
    }
}
