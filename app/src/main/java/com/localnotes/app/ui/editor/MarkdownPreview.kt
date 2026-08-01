package com.localnotes.app.ui.editor

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Image(val alt: String, val path: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data object Spacer : MdBlock
}

@Composable
fun MarkdownPreview(
    body: String,
    resolveImage: suspend (relativePath: String) -> Uri?,
    modifier: Modifier = Modifier
) {
    val blocks = remember(body) { parseMarkdownBlocks(body) }
    var imageUris by remember(body) { mutableStateOf<Map<String, Uri?>>(emptyMap()) }

    LaunchedEffect(body) {
        val paths = blocks.filterIsInstance<MdBlock.Image>().map { it.path }.distinct()
        val resolved = linkedMapOf<String, Uri?>()
        paths.forEach { path ->
            resolved[path] = resolveImage(path)
        }
        imageUris = resolved
    }

    if (body.isBlank()) {
        Text(
            text = "暂无内容，切换到「编辑」开始写吧。",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = modifier.padding(top = 12.dp)
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleLarge
                    }
                    Text(
                        text = renderInline(block.text),
                        style = style,
                        fontWeight = FontWeight.Bold
                    )
                }

                is MdBlock.Bullet -> {
                    Text(
                        text = buildAnnotatedString {
                            append("• ")
                            append(renderInline(block.text))
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is MdBlock.Image -> {
                    val uri = imageUris[block.path]
                    if (uri != null) {
                        AsyncImage(
                            model = uri,
                            contentDescription = block.alt.ifBlank { "图片" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 420.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "[图片无法显示: ${block.path}]",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = renderInline(block.text),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                MdBlock.Spacer -> {
                    // visual gap already handled by spacedBy
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(body: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val imageRegex = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    body.lines().forEach { raw ->
        val line = raw.trimEnd()
        when {
            line.isBlank() -> result += MdBlock.Spacer
            line.startsWith("### ") -> result += MdBlock.Heading(3, line.removePrefix("### ").trim())
            line.startsWith("## ") -> result += MdBlock.Heading(2, line.removePrefix("## ").trim())
            line.startsWith("# ") -> result += MdBlock.Heading(1, line.removePrefix("# ").trim())
            line.startsWith("- ") || line.startsWith("* ") ->
                result += MdBlock.Bullet(line.substring(2).trim())
            imageRegex.containsMatchIn(line) -> {
                // Support a line that is only an image, or text+image split simply.
                var lastIndex = 0
                imageRegex.findAll(line).forEach { match ->
                    val before = line.substring(lastIndex, match.range.first).trim()
                    if (before.isNotEmpty()) {
                        result += MdBlock.Paragraph(before)
                    }
                    result += MdBlock.Image(
                        alt = match.groupValues[1],
                        path = match.groupValues[2].trim()
                    )
                    lastIndex = match.range.last + 1
                }
                val after = line.substring(lastIndex).trim()
                if (after.isNotEmpty()) {
                    result += MdBlock.Paragraph(after)
                }
            }
            else -> result += MdBlock.Paragraph(line)
        }
    }
    return result
}

private fun renderInline(text: String) = buildAnnotatedString {
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    var last = 0
    boldRegex.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
