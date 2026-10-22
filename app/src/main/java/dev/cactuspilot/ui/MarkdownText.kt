package dev.cactuspilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class MdStyle(
    val code: SpanStyle,
    val bold: SpanStyle,
    val italic: SpanStyle,
    val strike: SpanStyle,
    val link: SpanStyle,
)

private val INLINE = Regex(
    "`([^`]+)`" +                                                       // 1 code
        "|\\*\\*(.+?)\\*\\*" +                                          // 2 bold
        "|__(.+?)__" +                                                  // 3 bold
        "|~~(.+?)~~" +                                                  // 4 strike
        "|\\[([^\\]]+)]\\(([^)\\s]+)\\)" +                              // 5 link text, 6 url
        "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?![\\w*])" +      // 7 italic
        "|(?<![\\w_])_(?![\\s_])(.+?)(?<![\\s_])_(?![\\w_])",           // 8 italic
)

private fun AnnotatedString.Builder.appendInline(text: String, st: MdStyle) {
    var pos = 0
    for (m in INLINE.findAll(text)) {
        if (m.range.first < pos) continue
        append(text.substring(pos, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> withStyle(st.code) { append(g[1]) }
            g[2].isNotEmpty() -> withStyle(st.bold) { appendInline(g[2], st) }
            g[3].isNotEmpty() -> withStyle(st.bold) { appendInline(g[3], st) }
            g[4].isNotEmpty() -> withStyle(st.strike) { appendInline(g[4], st) }
            g[5].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], TextLinkStyles(st.link))) { append(g[5]) }
            g[7].isNotEmpty() -> withStyle(st.italic) { appendInline(g[7], st) }
            g[8].isNotEmpty() -> withStyle(st.italic) { appendInline(g[8], st) }
        }
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}

private fun inline(text: String, st: MdStyle): AnnotatedString = buildAnnotatedString { appendInline(text, st) }

/** Renders chat Markdown (headings, lists, bold/italic/code, fenced code blocks, quotes, links). */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onCopyCode: (String) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val st = MdStyle(
        code = SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainerHighest, fontSize = 14.sp),
        bold = SpanStyle(fontWeight = FontWeight.Bold),
        italic = SpanStyle(fontStyle = FontStyle.Italic),
        strike = SpanStyle(textDecoration = TextDecoration.LineThrough),
        link = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
    )
    val body = MaterialTheme.typography.bodyLarge.copy(color = color, lineHeight = 24.sp)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in MarkdownParser.parse(text)) {
            when (b) {
                is MdBlock.Paragraph -> Text(inline(b.text, st), style = body)
                is MdBlock.Heading -> Text(
                    inline(b.text, st),
                    style = body.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = when (b.level) { 1 -> 22.sp; 2 -> 20.sp; 3 -> 18.sp; else -> 16.sp },
                    ),
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = (14 * b.indent).dp)) {
                    Text(if (b.ordered) "${b.number}." else "•", style = body, modifier = Modifier.width(26.dp))
                    Text(inline(b.text, st), style = body, modifier = Modifier.weight(1f))
                }
                is MdBlock.Code -> Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceContainerHighest) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                b.lang.ifEmpty { "code" },
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { onCopyCode(b.code) }) { Text("Copy") }
                        }
                        Text(
                            b.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = scheme.onSurface,
                            softWrap = false,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        )
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(scheme.primary))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(b.text, st), style = body.copy(fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.85f)))
                }
                MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
        }
    }
}
