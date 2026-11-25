package com.kobblo.gallery.file.explorer.ui.design.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kobblo.gallery.file.explorer.ui.design.KobbloDesign

/** The folder: filled, two-toned blue, and the most recognizable thing on any file screen. */
private val FolderArt: ImageVector by lazy(LazyThreadSafetyMode.PUBLICATION) {
    ImageVector.Builder(
        name = "Folder",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f
    ).apply {
        // The back panel and its tab.
        addPath(
            addPathNodes("M5 16.5A5.5 5.5 0 0 1 10.5 11h12.2c1.5 0 3 .6 4 1.6l2.6 2.5c1 1 2.5 1.6 4 1.6H53.5A5.5 5.5 0 0 1 59 22.2V30H5Z"),
            fill = Brush.linearGradient(
                listOf(Color(0xFF2F8EEB), Color(0xFF2479D6)),
                start = Offset(0f, 11f),
                end = Offset(0f, 30f)
            )
        )
        // The front panel.
        addPath(
            addPathNodes("M5 26.5A4.5 4.5 0 0 1 9.5 22h45A4.5 4.5 0 0 1 59 26.5v21a5.5 5.5 0 0 1-5.5 5.5h-43A5.5 5.5 0 0 1 5 47.5Z"),
            fill = Brush.linearGradient(
                listOf(Color(0xFF6FC6FF), Color(0xFF3EA3F7)),
                start = Offset(0f, 22f),
                end = Offset(0f, 53f)
            )
        )
        // Light catching its top edge.
        addPath(
            addPathNodes("M9.5 22h45A4.5 4.5 0 0 1 59 26.5v.7A4.5 4.5 0 0 0 54.5 22.7h-45A4.5 4.5 0 0 0 5 27.2v-.7A4.5 4.5 0 0 1 9.5 22Z"),
            fill = SolidColor(Color.White),
            fillAlpha = 0.4f
        )
    }.build()
}

private const val PaperPath =
    "M12 11.5A5.5 5.5 0 0 1 17.5 6h18.8c1.5 0 2.9.6 3.9 1.6l10.2 10.2c1 1 1.6 2.4 1.6 3.9V52.5A5.5 5.5 0 0 1 46.5 58h-29A5.5 5.5 0 0 1 12 52.5Z"
private const val FoldPath = "M37.5 6.2V16a5 5 0 0 0 5 5h9.3c-.2-1.2-.8-2.3-1.6-3.2L40.2 7.6c-.8-.7-1.7-1.2-2.7-1.4Z"

private fun paperArt(paper: Color, edge: Color, fold: Color) =
    ImageVector.Builder(
        name = "Paper",
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 64f,
        viewportHeight = 64f
    ).apply {
        addPath(addPathNodes(PaperPath), fill = SolidColor(paper), stroke = SolidColor(edge), strokeLineWidth = 0.8f)
        addPath(addPathNodes(FoldPath), fill = SolidColor(fold))
    }.build()

private val LightPaper by lazy(LazyThreadSafetyMode.PUBLICATION) {
    paperArt(Color.White, Color(0x33000000), Color(0xFFE5E5EA))
}
private val DarkPaper by lazy(LazyThreadSafetyMode.PUBLICATION) {
    paperArt(Color(0xFF2C2C2E), Color(0x33FFFFFF), Color(0xFF48484A))
}

/** The glyph a well-known folder wears on its front, so it can be found at a glance. */
internal fun folderGlyphFor(name: String?): ImageVector? = when (name?.lowercase()) {
    "download", "downloads" -> AppIcons.Download
    "documents", "document" -> AppIcons.FileText
    "pictures", "photos", "images", "screenshots" -> AppIcons.Photo
    "dcim", "camera" -> AppIcons.Camera
    "movies", "videos", "video" -> AppIcons.PlayRect
    "music", "audio", "recordings", "podcasts" -> AppIcons.Music
    "android" -> AppIcons.Android
    else -> null
}

/** A folder at any size. Pass [name] to badge the folders people look for most. */
@Composable
fun FolderIcon(modifier: Modifier = Modifier, name: String? = null) {
    val glyph = remember(name) { folderGlyphFor(name) }
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(rememberVectorPainter(FolderArt), contentDescription = null, modifier = Modifier.fillMaxSize())
        if (glyph != null && maxWidth >= 40.dp) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = Color(0xB01A5FB4),
                modifier = Modifier
                    .size(maxWidth * 0.34f)
                    .offset(y = maxWidth * 0.09f)
            )
        }
    }
}

/**
 * A document with no preview: a sheet of paper carrying the file's glyph in its kind's color, and
 * its extension once there is room to read it.
 */
@Composable
fun PaperIcon(
    glyph: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    extension: String = ""
) {
    val paper = if (KobbloDesign.colors.isDark) DarkPaper else LightPaper
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(rememberVectorPainter(paper), contentDescription = null, modifier = Modifier.fillMaxSize())
        val showExtension = extension.isNotEmpty() && extension.length <= 5 && maxWidth >= 64.dp
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(maxWidth * 0.34f)
                .offset(y = if (showExtension) maxWidth * 0.03f else maxWidth * 0.07f)
        )
        if (showExtension) {
            val labelSize = with(LocalDensity.current) { (maxWidth * 0.1f).toSp() }
            Box(modifier = Modifier.fillMaxSize().padding(bottom = maxWidth * 0.14f), contentAlignment = Alignment.BottomCenter) {
                Text(
                    text = extension.uppercase(),
                    style = TextStyle(fontSize = labelSize, lineHeight = labelSize, fontWeight = FontWeight.SemiBold),
                    color = tint,
                    maxLines = 1
                )
            }
        }
    }
}
