package com.kobblo.gallery.file.explorer.smart

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.AlbumNameSheet
import com.kobblo.gallery.file.explorer.gallery.GalleryDest
import com.kobblo.gallery.file.explorer.gallery.GalleryNav
import com.kobblo.gallery.file.explorer.gallery.GallerySheet
import com.kobblo.gallery.file.explorer.haptics.LocalHaptics
import com.kobblo.gallery.file.explorer.ui.design.quietCombinedClickable
import com.kobblo.gallery.file.explorer.ui.expressive.ExpressiveScreenHeader
import com.kobblo.gallery.file.explorer.ui.expressive.pressScale
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloGroup
import com.kobblo.gallery.file.explorer.ui.design.KobbloMessage
import com.kobblo.gallery.file.explorer.ui.design.KobbloRow
import com.kobblo.gallery.file.explorer.ui.design.KobbloSpace
import com.kobblo.gallery.file.explorer.ui.design.KobbloType
import com.kobblo.gallery.file.explorer.ui.design.TextButton
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.rememberLargeTitleState
import com.subhashh.lumen.ai.LumenAi
import com.subhashh.lumen.ai.ModelPack
import com.subhashh.lumen.ai.Person
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The people found so far, named first and then by how many photos they're in. */
internal object SmartPeople {
    /** One photo is a stranger in the background; two or a name make someone worth a place. */
    private const val MinPhotos = 2

    suspend fun all(context: Context): List<Person> {
        if (!SmartFeatures.isReady(context, ModelPack.Faces)) return emptyList()
        return try {
            LumenAi.people.all(context)
                .filter { it.name != null || it.mediaIds.size >= MinPhotos }
                .sortedWith(compareBy<Person> { it.name == null }.thenByDescending { it.mediaIds.size })
        } catch (e: Exception) {
            android.util.Log.e("SmartPeople", "Couldn't read the people", e)
            emptyList()
        }
    }

    suspend fun mediaIds(context: Context, personId: Long): Set<Long> =
        all(context).firstOrNull { it.id == personId }?.mediaIds.orEmpty()

    fun faceFile(context: Context, person: Person): File = LumenAi.people.faceImage(context, person)
}

/**
 * The people, read again while faces are still being found, since a new photo can add someone. [version]
 * is bumped after a change made here, such as a new name.
 */
@Composable
internal fun rememberPeople(version: Int = 0): List<Person>? {
    val context = LocalContext.current
    if (!SmartFeatures.isSupported) return emptyList()
    val reading by LumenAi.indexing.progress.collectAsState(null)
    val people by produceState<List<Person>?>(null, version) {
        while (true) {
            value = withContext(Dispatchers.IO) { SmartPeople.all(context) }
            // Settled people don't change by themselves, so only a running pass is worth watching.
            if (reading?.faces == null) break
            delay(4000)
        }
    }
    return people
}

/** A person's face, round. */
@Composable
internal fun FaceAvatar(person: Person, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = KobbloDesign.colors
    Box(
        modifier = modifier.size(size).clip(CapsuleShape).background(colors.mist),
        contentAlignment = Alignment.Center
    ) {
        Icon(AppIcons.Person, contentDescription = null, tint = colors.pewter, modifier = Modifier.size(size * 0.45f))
        AsyncImage(
            model = remember(person.coverFaceId) {
                ImageRequest.Builder(context).data(SmartPeople.faceFile(context, person)).size(size.value.toInt() * 3).build()
            },
            contentDescription = person.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun PersonTile(person: Person, size: Dp, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .pressScale(pressedScale = 0.94f, interactionSource = source)
            .quietCombinedClickable(source = source, onLongClick = onLongClick, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FaceAvatar(person, size)
        Spacer(Modifier.height(6.dp))
        Text(
            text = person.name ?: stringResource(R.string.smart_people_add_name),
            style = KobbloType.footnote,
            color = if (person.name != null) KobbloDesign.colors.ink else KobbloDesign.colors.action,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(size + 12.dp)
        )
    }
}

private fun open(person: Person, unnamed: String) {
    GalleryNav.open(GalleryDest.Person(person.id, person.name ?: unnamed))
}

/** Albums' People: a row of faces under its own heading, with See All. Nothing at all until someone's found. */
@Composable
internal fun PeopleRow(people: List<Person>, onNameRequest: (Person) -> Unit) {
    if (people.isEmpty()) return
    val unnamed = stringResource(R.string.smart_people_add_name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KobbloSpace.screen, end = KobbloSpace.xs, top = KobbloSpace.section, bottom = KobbloSpace.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.smart_people_title),
            style = KobbloType.title,
            color = KobbloDesign.colors.ink,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { GalleryNav.open(GalleryDest.People) }) {
            Text(stringResource(R.string.smart_people_see_all), style = KobbloType.callout, color = KobbloDesign.colors.action)
        }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = KobbloSpace.screen),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(people.take(RowLimit), key = { it.id }) { person ->
            PersonTile(person, 72.dp, onClick = { open(person, unnamed) }, onLongClick = { onNameRequest(person) })
        }
    }
}

private const val RowLimit = 12

/** Everyone found, as a grid of faces. Holding one names it or hides it. */
@Composable
internal fun PeopleScreen(bottomPadding: Dp) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val people = rememberPeople(version)
    val largeTitle = rememberLargeTitleState(height = 64.dp)
    var acting by remember { mutableStateOf<Person?>(null) }
    var naming by remember { mutableStateOf<Person?>(null) }
    val scope = rememberCoroutineScope()
    val unnamed = stringResource(R.string.smart_people_add_name)

    Column(Modifier.fillMaxSize().nestedScroll(largeTitle.connection)) {
        ExpressiveScreenHeader(
            title = stringResource(R.string.smart_people_title),
            modifier = Modifier.padding(horizontal = KobbloSpace.screen),
            onBack = { GalleryNav.back() },
            backLabel = stringResource(R.string.gallery_albums),
            largeTitle = largeTitle
        )
        when {
            people == null -> Unit
            people.isEmpty() -> KobbloMessage(
                icon = AppIcons.Person,
                title = stringResource(R.string.smart_people_empty),
                body = stringResource(R.string.smart_people_empty_body)
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(start = KobbloSpace.screen, end = KobbloSpace.screen, top = 8.dp, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(people, key = { it.id }) { person ->
                    PersonTile(person, 84.dp, onClick = { open(person, unnamed) }, onLongClick = { acting = person })
                }
            }
        }
    }

    acting?.let { person ->
        GallerySheet(onDismiss = { acting = null }) {
            KobbloGroup {
                KobbloRow(
                    title = stringResource(if (person.name == null) R.string.smart_people_name_title else R.string.smart_people_rename_title),
                    trailing = { Icon(AppIcons.Pencil, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp)) },
                    showDivider = true,
                    onClick = {
                        acting = null
                        naming = person
                    }
                )
                KobbloRow(
                    title = stringResource(R.string.smart_people_hide),
                    trailing = { Icon(AppIcons.EyeOff, contentDescription = null, tint = KobbloDesign.colors.action, modifier = Modifier.size(22.dp)) },
                    onClick = {
                        acting = null
                        scope.launch {
                            LumenAi.people.setHidden(context, person, true)
                            version++
                        }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    naming?.let { person ->
        NamePersonSheet(person, onDone = {
            naming = null
            version++
        })
    }
}

/** Asks for a person's name, and saves it. */
@Composable
internal fun NamePersonSheet(person: Person, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    AlbumNameSheet(
        title = stringResource(if (person.name == null) R.string.smart_people_name_title else R.string.smart_people_rename_title),
        initial = person.name.orEmpty(),
        confirmLabel = stringResource(R.string.smart_people_name_action),
        hint = stringResource(R.string.smart_people_add_name),
        onConfirm = { name ->
            val trimmed = name.trim()
            // Not the sheet's own scope, which ends the moment it's dismissed.
            com.kobblo.gallery.file.explorer.gallery.GalleryWorkScope.launch {
                if (trimmed.isNotEmpty()) LumenAi.people.name(context, person, trimmed)
                onDone(trimmed.ifEmpty { null })
            }
        },
        onDismiss = { onDone(null) }
    )
}
