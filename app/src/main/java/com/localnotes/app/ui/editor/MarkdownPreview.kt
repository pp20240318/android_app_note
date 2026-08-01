package com.localnotes.app.ui.editor

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: Int, val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
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
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(body) {
        val paths = blocks.filterIsInstance<MdBlock.Image>().map { it.path }.distinct()
        val resolved = linkedMapOf<String, Uri?>()
        paths.forEach { path -> resolved[path] = resolveImage(path) }
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
                    Text(text = renderInline(block.text), style = style, fontWeight = FontWeight.Bold)
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

                is MdBlock.Ordered -> {
                    Text(
                        text = buildAnnotatedString {
                            append("${block.index}. ")
                            append(renderInline(block.text))
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                is MdBlock.Code -> {
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    val annotated = renderInline(block.text)
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.clickable(enabled = annotated.getStringAnnotations("URL", 0, annotated.length).isNotEmpty()) {
                            annotated.getStringAnnotations("URL", 0, annotated.length)
                                .firstOrNull()
                                ?.let { uriHandler.openUri(it.item) }
                        }
                    )
                }

                MdBlock.Spacer -> Unit
            }
        }
    }
}

private fun parseMarkdownBlocks(body: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val imageRegex = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    val orderedRegex = Regex("""^(\d+)\.\s+(.*)$""")
    val lines = body.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        when {
            line.isBlank() -> result += MdBlock.Spacer
            line.startsWith("```") -> {
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                result += MdBlock.Code(code.toString())
            }
            line.startsWith("### ") -> result += MdBlock.Heading(3, line.removePrefix("### ").trim())
            line.startsWith("## ") -> result += MdBlock.Heading(2, line.removePrefix("## ").trim())
            line.startsWith("# ") -> result += MdBlock.Heading(1, line.removePrefix("# ").trim())
            line.startsWith("- ") || line.startsWith("* ") ->
                result += MdBlock.Bullet(line.substring(2).trim())
            orderedRegex.matches(line) -> {
                val m = orderedRegex.matchEntire(line)!!
                result += MdBlock.Ordered(m.groupValues[1].toInt(), m.groupValues[2])
            }
            imageRegex.containsMatchIn(line) -> {
                var lastIndex = 0
                imageRegex.findAll(line).forEach { match ->
                    val before = line.substring(lastIndex, match.range.first).trim()
                    if (before.isNotEmpty()) result += MdBlock.Paragraph(before)
                    result += MdBlock.Image(match.groupValues[1], match.groupValues[2].trim())
                    lastIndex = match.range.last + 1
                }
                val after = line.substring(lastIndex).trim()
                if (after.isNotEmpty()) result += MdBlock.Paragraph(after)
            }
            else -> result += MdBlock.Paragraph(line)
        }
        i++
    }
    return result
}

private fun renderInline(text: String) = buildAnnotatedString {
    // Process images already handled as blocks; here: links, bold, italic, code.
    val pattern = Regex(
        """(\*\*(.+?)\*\*)|(\*(.+?)\*)|(`(.+?)`)|(\[([^\]]+)]\(([^)]+)\))"""
    )
    var last = 0
    pattern.findAll(text).forEach { match ->
        append(text.substring(last, match.range.first))
        when {
            match.groups[2] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(match.groupValues[2])
            }
            match.groups[4] != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                append(match.groupValues[4])
            }
            match.groups[6] != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x22000000))
            ) {
                append(match.groupValues[6])
            }
            match.groups[8] != null -> {
                val label = match.groupValues[8]
                val url = match.groupValues[9]
                pushStringAnnotation("URL", url)
                withStyle(
                    SpanStyle(
                        color = Color(0xFF1B4D3E),
                        textDecoration = TextDecoration.Underline
                    )
                ) { append(label) }
                pop()
            }
        }
        last = match.range.last + 1
    }
    append(text.substring(last))
}
