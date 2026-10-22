package dev.cactuspilot.ui

/** Block-level Markdown model. Kept free of Compose imports so it can be unit-tested on the JVM. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class ListItem(val text: String, val ordered: Boolean, val number: Int, val indent: Int) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

/**
 * Small, forgiving Markdown block parser for chat replies. It tolerates half-written text because replies
 * are rendered while they stream (for example an unterminated code fence is treated as code until the end).
 */
object MarkdownParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
    private val RULE = Regex("^([-*_])(\\s*\\1){2,}$")
    private val BULLET = Regex("^(\\s*)([-*+•])\\s+(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val lines = src.replace("\r\n", "\n").split("\n")
        val para = StringBuilder()

        fun flush() {
            if (para.isNotEmpty()) {
                out += MdBlock.Paragraph(para.toString().trimEnd())
                para.clear()
            }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val lang = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    out += MdBlock.Code(lang, code.toString())
                }
                trimmed.isEmpty() -> flush()
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                }
                RULE.matches(trimmed) -> {
                    flush()
                    out += MdBlock.Rule
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    var j = i
                    while (j < lines.size && lines[j].trim().startsWith(">")) {
                        q.append(lines[j].trim().removePrefix(">").trim()).append('\n')
                        j++
                    }
                    out += MdBlock.Quote(q.toString().trim())
                    i = j - 1
                }
                trimmed.startsWith("|") -> {
                    flush()
                    val t = StringBuilder()
                    var j = i
                    while (j < lines.size && lines[j].trim().startsWith("|")) {
                        if (t.isNotEmpty()) t.append('\n')
                        t.append(lines[j].trim())
                        j++
                    }
                    out += MdBlock.Code("table", t.toString())
                    i = j - 1
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!
                    out += MdBlock.ListItem(m.groupValues[3].trim(), false, 0, indentOf(m.groupValues[1]))
                }
                ORDERED.matches(line) -> {
                    flush()
                    val m = ORDERED.find(line)!!
                    out += MdBlock.ListItem(
                        m.groupValues[3].trim(), true, m.groupValues[2].toInt(), indentOf(m.groupValues[1]),
                    )
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(trimmed)
                }
            }
            i++
        }
        flush()
        return out
    }

    private fun indentOf(spaces: String) = (spaces.length / 2).coerceIn(0, 3)
}
