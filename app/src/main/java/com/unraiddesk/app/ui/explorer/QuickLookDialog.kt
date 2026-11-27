package com.unraiddesk.app.ui.explorer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.unraiddesk.app.files.vfs.FileEntry
import com.unraiddesk.app.files.vfs.FileSystemProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface PreviewContent {
    data object Loading : PreviewContent
    data class TextPreview(val text: String, val extension: String) : PreviewContent
    data class ImagePreview(val bitmap: Bitmap) : PreviewContent
    data class ErrorPreview(val message: String) : PreviewContent
}

/**
 * Space-bar Quick Look dialog supporting images, syntax-colored text/JSON/YAML/logs, and PDFs.
 */
@Composable
fun QuickLookDialog(
    entry: FileEntry,
    provider: FileSystemProvider,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var preview by remember(entry.path) { mutableStateOf<PreviewContent>(PreviewContent.Loading) }

    LaunchedEffect(entry.path) {
        preview = withContext(Dispatchers.IO) {
            try {
                val ext = entry.name.substringAfterLast('.', "").lowercase()
                when {
                    ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp") -> {
                        val bytes = provider.openInputStream(entry.path).use { it.readBytes() }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            PreviewContent.ImagePreview(bmp)
                        } else {
                            PreviewContent.ErrorPreview("Unable to decode image.")
                        }
                    }
                    ext == "pdf" -> {
                        val tempFile = File.createTempFile("quicklook_", ".pdf", context.cacheDir)
                        try {
                            provider.openInputStream(entry.path).use { input ->
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { renderer ->
                                    if (renderer.pageCount == 0) {
                                        PreviewContent.ErrorPreview("PDF has no pages.")
                                    } else {
                                        renderer.openPage(0).use { page ->
                                            val bmp = androidx.core.graphics.createBitmap(
                                                page.width.coerceAtMost(1200),
                                                page.height.coerceAtMost(1600)
                                            )
                                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            PreviewContent.ImagePreview(bmp)
                                        }
                                    }
                                }
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }
                    else -> {
                        val maxBytes = 64 * 1024
                        val bytes = provider.openInputStream(entry.path).use { input ->
                            val buf = ByteArray(maxBytes)
                            val read = input.read(buf)
                            if (read > 0) buf.copyOf(read) else ByteArray(0)
                        }
                        PreviewContent.TextPreview(String(bytes, Charsets.UTF_8), ext)
                    }
                }
            } catch (e: Exception) {
                PreviewContent.ErrorPreview(e.message ?: "Unable to preview file.")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 440.dp),
                contentAlignment = Alignment.Center
            ) {
                when (val content = preview) {
                    is PreviewContent.Loading -> CircularProgressIndicator()
                    is PreviewContent.ErrorPreview -> Text(
                        text = content.message,
                        color = MaterialTheme.colorScheme.error
                    )
                    is PreviewContent.ImagePreview -> Image(
                        bitmap = content.bitmap.asImageBitmap(),
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth()
                    )
                    is PreviewContent.TextPreview -> {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = highlightSyntax(content.text, content.extension),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun highlightSyntax(text: String, ext: String): AnnotatedString {
    if (ext !in setOf("json", "yaml", "yml", "log", "xml", "conf", "ini", "sh")) {
        return AnnotatedString(text)
    }
    return buildAnnotatedString {
        append(text)
        val keyRegex = Regex("(\"[^\"]+\"\\s*:|^[a-zA-Z0-9_.-]+:)", RegexOption.MULTILINE)
        keyRegex.findAll(text).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFF4FC3F7)), match.range.first, match.range.last + 1)
        }
        val errorRegex = Regex("\\b(ERROR|FATAL|WARN|EXCEPTION)\\b", RegexOption.IGNORE_CASE)
        errorRegex.findAll(text).forEach { match ->
            addStyle(SpanStyle(color = Color(0xFFEF5350)), match.range.first, match.range.last + 1)
        }
    }
}
