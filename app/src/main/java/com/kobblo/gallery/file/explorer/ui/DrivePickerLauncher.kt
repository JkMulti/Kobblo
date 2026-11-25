package com.kobblo.gallery.file.explorer.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest.ResourceParameter
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

/**
 * Gives back a function that opens Google's own file picker for Drive.
 *
 * Kobblo asks Google only for the `drive.file` scope, which covers nothing but the files the person
 * hands over one by one. Handing them over happens on Google's consent screen: asked the right way,
 * that screen carries a picker, and the ids of what was chosen come back with the grant. Google runs
 * the whole screen, so there is nothing of Kobblo's to show.
 *
 * [onResult] hears how it went. `success` is false when access wasn't given, whether it was refused,
 * cancelled or failed. `pickedIds` holds what was chosen when Google reports it; it's null when access
 * already stood and no picker was shown, or when the grant came back without saying.
 */
@Composable
fun rememberDrivePickerLauncher(
    onResult: (success: Boolean, pickedIds: List<String>?) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val google = remember { Identity.getAuthorizationClient(context) }

    val consentScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { answer ->
        // Reading the grant throws when the screen closed without one worth the name.
        val grant = if (answer.resultCode == Activity.RESULT_OK) {
            runCatching { google.getAuthorizationResultFromIntent(answer.data) }.getOrNull()
        } else null
        if (grant != null) onResult(true, pickedFileIds(grant, answer.data)) else onResult(false, null)
    }

    return {
        google.authorize(pickerRequest())
            .addOnSuccessListener { grant ->
                val consent = grant.pendingIntent.takeIf { grant.hasResolution() }
                if (consent != null) {
                    consentScreen.launch(IntentSenderRequest.Builder(consent.intentSender).build())
                } else {
                    // Nothing to ask: the files picked before are still Kobblo's to read.
                    onResult(true, null)
                }
            }
            .addOnFailureListener { onResult(false, null) }
    }
}

/** The name the chosen files' ids travel under, wherever Google puts them. */
private const val PickedIdsKey = "picked_file_ids"

/**
 * A request for `drive.file` that brings the picker up with the consent screen. Google only allows
 * that when it's the one scope asked for, with scopes granted earlier left out of the answer.
 */
private fun pickerRequest(): AuthorizationRequest =
    AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
        .addResourceParameter(ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
        .setOptOutIncludingGrantedScopes(true)
        .build()

/**
 * The ids of the files chosen on Google's screen, or null if it didn't say. They arrive as one
 * comma-separated string with the token's parameters. Not every version of Google's library has a
 * method for reading those, so it's looked up by name; failing that, the same string may sit on the
 * returned intent.
 */
private fun pickedFileIds(grant: AuthorizationResult, answer: Intent?): List<String>? {
    val withToken = runCatching {
        val parameters = grant.javaClass.getMethod("getTokenResponseParams").invoke(grant) as? Bundle
        parameters?.getString(PickedIdsKey)
    }.getOrNull()
    val joined = withToken ?: answer?.getStringExtra(PickedIdsKey)
    return joined?.split(",")?.filter { it.isNotBlank() }
}
