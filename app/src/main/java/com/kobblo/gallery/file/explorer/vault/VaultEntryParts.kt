package com.kobblo.gallery.file.explorer.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.ui.design.Button
import com.kobblo.gallery.file.explorer.ui.design.KobbloActionHeight
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.expressive.IconTile
import com.kobblo.gallery.file.explorer.ui.expressive.IconTileButton
import com.kobblo.gallery.file.explorer.ui.theme.LocalAppFont
import com.kyant.shapes.RoundedRectangle

// The furniture shared by the pages that stand in front of a vault: the two the setup wizard draws
// itself, and the unlock page. Recovery and the settings inside the vault have their own frame,
// `VaultFlowPage`, and borrow the few pieces marked internal.

/** Fills the screen but for the system bars, as every full-page Vault screen does. */
internal fun Modifier.vaultPageBounds(): Modifier = this
    .fillMaxSize()
    .statusBarsPadding()
    .navigationBarsPadding()

/**
 * The strip across the top of a Vault page: the way out of it at the leading edge, and for a
 * wizard its [progress] in the middle.
 */
@Composable
internal fun VaultPageBar(
    exitIcon: ImageVector,
    exitDescription: String,
    onExit: () -> Unit,
    progress: (@Composable () -> Unit)? = null
) {
    val colors = KobbloDesign.colors
    Box(modifier = Modifier.fillMaxWidth()) {
        IconTileButton(
            onClick = onExit,
            icon = exitIcon,
            bgColor = colors.snow,
            description = exitDescription,
            iconTint = colors.ink,
            size = 40.dp,
            modifier = Modifier.align(Alignment.CenterStart)
        )
        if (progress != null) {
            // In the middle of the page, whatever sits at the edges.
            Box(modifier = Modifier.align(Alignment.Center)) { progress() }
        }
    }
}

/** How far through a wizard this is: a dot per step, filled up to the current one, which is drawn long. */
@Composable
internal fun StepperIndicator(currentStep: Int, totalSteps: Int = 3) {
    val colors = KobbloDesign.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val step = index + 1
            Box(
                Modifier
                    .size(width = if (step == currentStep) 24.dp else 8.dp, height = 6.dp)
                    .clip(RoundedRectangle(3.dp))
                    .background(if (step <= currentStep) colors.action else colors.hairline)
            )
        }
    }
}

/** One thing worth knowing before trusting files to a vault: a glyph, a heading and a sentence or two. */
@Composable
internal fun SetupInfoCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    description: String
) {
    val colors = KobbloDesign.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon = icon, bgColor = colors.snow, iconTint = iconTint, size = 44.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = KobbloType.headline.copy(fontWeight = FontWeight.SemiBold), color = colors.ink)
            Text(text = description, style = KobbloType.callout, color = colors.smoke)
        }
    }
}

/** A page in front of the vault: its [bar], then [content] centered below it, scrolling if it has to. */
@Composable
internal fun VaultEntryPage(bar: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .vaultPageBounds()
            // Outside the scrolling, so what scrolls away is cut off at the margin and not at the screen's edge.
            .padding(horizontal = EntryPageMargin, vertical = EntryPageInset)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        bar()
        content()
    }
}

private val EntryPageMargin = 20.dp
private val EntryPageInset = 12.dp

/** A page's badge, its name, and the line under the name. [badgeGap] is the space between badge and name. */
@Composable
internal fun VaultEntryHeading(
    icon: ImageVector,
    badgeSize: Dp,
    badgeGap: Dp,
    title: String,
    titleStyle: TextStyle,
    subtitle: String,
    subtitleStyle: TextStyle
) {
    val colors = KobbloDesign.colors
    IconTile(icon = icon, bgColor = colors.snow, iconTint = colors.action, size = badgeSize)
    Spacer(Modifier.height(badgeGap))
    Text(text = title, style = titleStyle, fontWeight = FontWeight.Bold, color = colors.ink, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(text = subtitle, style = subtitleStyle, color = colors.smoke, textAlign = TextAlign.Center)
}

/** The wide button that ends a setup page. */
@Composable
internal fun VaultEntryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = KobbloDesign.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedRectangle(26.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.action,
            contentColor = colors.onAction,
            disabledContainerColor = colors.action.copy(alpha = 0.35f),
            disabledContentColor = colors.onAction.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(KobbloActionHeight)
    ) {
        Text(text = text, style = KobbloType.headline, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * A one-line field on an entry page. [isWrong] turns its outline to the error color whether or not
 * it has focus, for a repeat that doesn't match.
 */
@Composable
internal fun VaultEntryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    isWrong: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val colors = KobbloDesign.colors
    val font = LocalAppFont.current
    val wrong = MaterialTheme.colorScheme.error
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = font) },
        placeholder = placeholder?.let { hint -> { Text(hint, fontFamily = font) } },
        leadingIcon = leadingIcon?.let { glyph -> { Icon(glyph, contentDescription = null, tint = colors.smoke) } },
        trailingIcon = trailingIcon,
        singleLine = true,
        shape = RoundedRectangle(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.snow,
            unfocusedContainerColor = colors.snow,
            focusedBorderColor = if (isWrong) wrong else colors.action,
            unfocusedBorderColor = if (isWrong) wrong else colors.hairline,
            focusedTextColor = colors.ink,
            unfocusedTextColor = colors.ink
        ),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A [VaultEntryField] for a password: dots, with an eye at the end to show what was typed. Whether
 * it's showing is all that's saved with the activity. The password itself never is.
 */
@Composable
internal fun VaultSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    isWrong: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var isShown by rememberSaveable { mutableStateOf(false) }
    VaultEntryField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        isWrong = isWrong,
        visualTransformation = if (isShown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { isShown = !isShown }) {
                Icon(
                    imageVector = if (isShown) AppIcons.EyeOff else AppIcons.Eye,
                    contentDescription = stringResource(
                        if (isShown) R.string.viewer_pdf_password_hide else R.string.viewer_pdf_password_show
                    ),
                    tint = KobbloDesign.colors.smoke
                )
            }
        },
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions
    )
}

/** A line of small print under a field: what's wrong with it, or what to know about it. */
@Composable
internal fun VaultEntryFieldNote(text: String, color: Color, gap: Dp) {
    Spacer(Modifier.height(gap))
    Text(
        text = text,
        style = KobbloType.footnote,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    )
}
