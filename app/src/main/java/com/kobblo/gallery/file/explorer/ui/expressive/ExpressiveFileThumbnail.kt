package com.kobblo.gallery.file.explorer.ui.expressive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.kobblo.gallery.file.explorer.FileItem
import com.kobblo.gallery.file.explorer.R
import com.kobblo.gallery.file.explorer.gallery.PhotoFlight
import com.kobblo.gallery.file.explorer.gallery.flightCell
import com.kobblo.gallery.file.explorer.ui.design.CapsuleShape
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign
import com.kobblo.gallery.file.explorer.ui.design.KobbloShapes
import com.kobblo.gallery.file.explorer.ui.design.concentricInset
import com.kobblo.gallery.file.explorer.ui.design.icons.AppIcons
import com.kobblo.gallery.file.explorer.ui.design.icons.FolderIcon
import com.kobblo.gallery.file.explorer.ui.design.icons.PaperIcon
import com.kobblo.gallery.file.explorer.viewer.FileKinds
import java.io.File
import java.util.Locale

private fun extensionOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.getDefault())

/** A page of markup or a sheet's raw text says less about a file than a clear glyph does. */
private val GlyphOnly = setOf("html", "htm", "doc", "xls", "ppt", "rtf", "epub", "csv", "tsv")

/** Plain text, whose first lines can be drawn as a page. */
private val TextPages = setOf("txt", "log", "md", "json", "xml", "kt", "java", "py", "css", "js")

/** Documents and packages that carry a first page, a cover or an icon of their own. */
private val Covers = setOf("pdf", "apk", "docx", "pptx", "xlsx", "odt", "ods", "odp")

/** The kinds a listing may already have recognised, whatever the name ends in. */
private val PicturedTypes = setOf("image", "video", "doc")

/** Below this many bytes a text file's page is all but blank, which reads as a broken tile. */
private const val ShortestTextPage = 512

/**
 * Whether a file's tile shows a picture of what is in it, rather than the glyph of its kind.
 *
 * Some files never do: a folder, a locked file (its picture is what the lock keeps out of sight,
 * wherever the row is), anything named like a list of passwords, and the kinds in [GlyphOnly]. The
 * rest do when a service has supplied a thumbnail, or when the file can be read and is a kind that
 * can be drawn.
 */
fun hasThumbnail(file: FileItem): Boolean {
    val ext = extensionOf(file.name)
    val keptOutOfSight = file.isLocked || file.name.contains("password", ignoreCase = true)
    val nearlyBlankPage = ext in TextPages && file.thumbnailLink == null && file.sizeBytes < ShortestTextPage
    return when {
        file.type == "folder" || keptOutOfSight || ext in GlyphOnly || nearlyBlankPage -> false
        file.thumbnailLink != null -> true
        file.path.isEmpty() -> false
        else -> file.type in PicturedTypes || ext in FileKinds.Images || ext in FileKinds.Videos || ext in Covers || ext in TextPages
    }
}

private val SourceCode = setOf(
    "kt", "kts", "java", "py", "js", "jsx", "mjs", "ts", "tsx", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "php", "rb", "swift",
    "sh", "bash", "zsh", "bat", "cmd", "ps1", "css", "scss", "less", "vue", "svelte", "dart", "lua", "pl", "r", "gradle", "diff", "patch", "tex"
)
private val DataFiles = setOf("json", "jsonc", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg", "env", "properties", "plist", "sql", "log")
private val Fonts = setOf("ttf", "otf", "ttc", "woff", "woff2")
private val Playlists = setOf("m3u", "m3u8", "pls", "cue")
private val Subtitles = setOf("srt", "vtt", "ass", "ssa", "sub", "lrc")
private val Maps = setOf("gpx", "kml", "kmz", "geojson")
private val Databases = setOf("db", "db3", "sqlite", "sqlite3")
private val Packages = setOf("exe", "msi", "deb", "rpm", "dmg", "pkg", "iso", "img", "bin")
private val Reading = setOf(
    "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp", "epub", "md", "markdown",
    "csv", "tsv", "html", "htm", "vcf", "ics", "eml"
)
private val AppPackages = setOf("apk", "xapk", "apks")

/** Each category with the extensions that belong to it. The first one an extension is found in wins. */
private val CategoryExtensions: List<Pair<String, Set<String>>> = listOf(
    "images" to FileKinds.Images,
    "videos" to FileKinds.Videos,
    "audio" to FileKinds.Audio,
    "docs" to Reading,
    "audio" to Playlists,
    "apps" to AppPackages,
    "archives" to FileKinds.Archives
)

/** The category a file's name puts it in, as [ExpressiveTokens.categoryTint] names them. Anything unrecognised is "large". */
fun fileCategoryForName(name: String): String {
    val ext = extensionOf(name)
    return CategoryExtensions.firstOrNull { (_, extensions) -> ext in extensions }?.first ?: "large"
}

fun fileIconForFile(type: String, ext: String): ImageVector {
    return when {
        type == "folder" -> AppIcons.Folder
        type == "image" || ext in FileKinds.Images -> AppIcons.Photo
        type == "video" || ext in FileKinds.Videos -> AppIcons.PlayRect
        type == "audio" || ext in FileKinds.Audio -> AppIcons.Music
        ext == "pdf" -> AppIcons.FileText
        ext in setOf("doc", "docx", "txt", "rtf", "odt", "md", "markdown") -> AppIcons.FileText
        ext in setOf("xls", "xlsx", "csv", "tsv", "ods") -> AppIcons.Table
        ext in setOf("ppt", "pptx", "odp") -> AppIcons.Presentation
        ext in AppPackages -> AppIcons.Android
        ext in FileKinds.Archives -> AppIcons.Archive
        ext == "epub" -> AppIcons.Book
        ext == "html" || ext == "htm" -> AppIcons.Globe
        ext == "vcf" -> AppIcons.Person
        ext == "ics" -> AppIcons.Calendar
        ext == "eml" -> AppIcons.Mail
        ext == "torrent" -> AppIcons.Download
        ext in SourceCode -> AppIcons.Code
        ext in DataFiles -> AppIcons.Braces
        ext in Fonts -> AppIcons.FontLetter
        ext in Playlists -> AppIcons.Playlist
        ext in Subtitles -> AppIcons.Lyrics
        ext in Maps -> AppIcons.Location
        ext in Databases -> AppIcons.Server
        ext in Packages -> AppIcons.Box
        else -> AppIcons.File
    }
}

/** What stands for a file with no picture: its folder, or its sheet of paper with its kind's glyph. */
@Composable
fun FileTypeArt(file: FileItem, modifier: Modifier = Modifier) {
    if (file.type == "folder") {
        FolderIcon(name = file.name, modifier = modifier)
        return
    }
    val ext = remember(file.name) { extensionOf(file.name) }
    val glyph = remember(file.type, ext) { fileIconForFile(file.type, ext) }
    val indicator = remember(file.name) { ExpressiveTokens.categoryTint(fileCategoryForName(file.name)) }
    PaperIcon(
        glyph = glyph,
        tint = if (indicator == ExpressiveTokens.Neutral) KobbloDesign.colors.smoke else indicator,
        extension = ext,
        modifier = modifier
    )
}

/** True while the screen is selecting, so every item can show its selection circle. */
val LocalSelectionActive = compositionLocalOf { false }

/**
 * A file's tile: its picture where it has one to show, else the art for its kind, with a lock or a
 * selection circle in its corner when either applies.
 */
@Composable
fun ExpressiveFileThumbnail(
    file: FileItem,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    shape: Shape? = null,
    /**
     * The thumbnail's corner radius, given where opening the file should grow out of this thumbnail
     * and closing it fly back. Left out where another copy of the same file is on screen, such as a menu.
     */
    flightCorner: Dp? = null,
    /** The corner radius of a custom [shape], for the marks that sit in its corner. */
    cornerRadius: Dp? = null,
) {
    val selecting = LocalSelectionActive.current
    val corner = cornerRadius ?: flightCorner ?: (size * TileCornerShare)
    val flight = if (flightCorner == null) Modifier else Modifier.flightCell(file.path, flightCorner / size)

    Box(modifier = modifier.size(size).then(flight), contentAlignment = Alignment.Center) {
        val source = remember(file.path, file.thumbnailLink) { pictureSource(file) }
        var unreadable by remember(file.path, file.thumbnailLink) { mutableStateOf(false) }
        if (source != null && !unreadable && hasThumbnail(file)) {
            FilePicture(
                file = file,
                source = source,
                shape = shape ?: KobbloShapes.tile,
                tileSize = size,
                feedsFlight = flightCorner != null,
                onUnreadable = { unreadable = true }
            )
        } else {
            FileTypeArt(file, Modifier.fillMaxSize())
        }

        // Whichever mark shows shares the corner's center, so its curve follows the corner's with an
        // even gap rather than one that is wider at the corner than along the sides.
        if (selecting) {
            // No rounder than the corner, so it never bulges out of the curve it sits in.
            val diameter = (size * 0.3f).coerceIn(22.dp, 28.dp).coerceAtMost(maxOf(corner * 2, 22.dp))
            SelectionCircle(selected = isSelected, modifier = cornerSeat(corner, diameter))
        } else if (file.isLocked) {
            val diameter = (size * 0.34f).coerceIn(16.dp, 24.dp)
            LockBadge(diameter, cornerSeat(corner, diameter))
        }
    }
}

/** Where a tile's picture is loaded from: a service's thumbnail if it gave one, else the file itself. */
private fun pictureSource(file: FileItem): Any? = file.thumbnailLink ?: file.path.takeIf { it.isNotEmpty() }?.let { File(it) }

/** Where a round mark of [diameter] sits in a tile's bottom corner of radius [corner]. */
private fun BoxScope.cornerSeat(corner: Dp, diameter: Dp): Modifier {
    val inset = concentricInset(corner, diameter / 2)
    return Modifier
        .align(Alignment.BottomEnd)
        .padding(end = inset, bottom = inset)
        .size(diameter)
}

/** How far into a video its picture is taken. The very first frame is often black. */
private const val VideoPictureMillis = 1000L

@Composable
private fun FilePicture(
    file: FileItem,
    source: Any,
    shape: Shape,
    tileSize: Dp,
    feedsFlight: Boolean,
    onUnreadable: () -> Unit
) {
    val colors = KobbloDesign.colors
    val context = LocalContext.current
    val isVideo = file.type == "video"
    val request = remember(source) {
        val builder = ImageRequest.Builder(context).data(source).listener(onError = { _, _ -> onUnreadable() })
        if (isVideo) builder.videoFrameMillis(VideoPictureMillis)
        builder.build()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().clip(shape).background(colors.mist).border(0.5.dp, colors.hairline, shape)
    ) {
        AsyncImage(
            model = request,
            contentDescription = file.name,
            contentScale = ContentScale.Crop,
            // The shape a photo opened from here grows into, before the viewer has measured it.
            onSuccess = {
                val shown = it.painter.intrinsicSize
                if (feedsFlight && shown.width > 0f && shown.height > 0f) {
                    PhotoFlight.rememberAspect(file.path, shown.width, shown.height)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isVideo) {
            Box(
                modifier = Modifier.size(tileSize * 0.36f).background(Color.Black.copy(alpha = 0.5f), CapsuleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(AppIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(tileSize * 0.2f))
            }
        }
    }
}

@Composable
private fun LockBadge(diameter: Dp, seat: Modifier) {
    val colors = KobbloDesign.colors
    Box(modifier = seat.clip(CapsuleShape).background(colors.snow), contentAlignment = Alignment.Center) {
        Icon(
            AppIcons.Lock,
            contentDescription = stringResource(R.string.locked_badge),
            tint = colors.action,
            modifier = Modifier.size(diameter * 0.62f)
        )
    }
}

/**
 * The round mark each item wears while selecting. The check pops in when it is chosen. [onSurface] is for
 * a row on a plain surface, where the white ring meant for pictures would vanish and leave a gray dot.
 */
@Composable
fun SelectionCircle(selected: Boolean, modifier: Modifier = Modifier, onSurface: Boolean = false) {
    val colors = ExpressiveTheme.colors
    val design = com.kobblo.gallery.file.explorer.ui.design.KobbloDesign.colors
    val motion = com.kobblo.gallery.file.explorer.ui.design.KobbloMotion
    val progress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (ExpressiveMotion.isReducedMotion()) androidx.compose.animation.core.snap() else motion.snappy(),
        label = "selection_circle"
    )
    Box(
        modifier = modifier
            .clip(CapsuleShape)
            .background(if (onSurface) design.silver else Color.White)
            .padding(1.5.dp)
            .clip(CapsuleShape)
            .background(if (onSurface) design.snow else Color.Black.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = progress.coerceAtLeast(0f)
                    scaleX = scale
                    scaleY = scale
                    alpha = progress.coerceIn(0f, 1f)
                }
                .clip(CapsuleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = if (selected) stringResource(R.string.tools_selected) else null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize().padding(4.dp)
            )
        }
    }
}
