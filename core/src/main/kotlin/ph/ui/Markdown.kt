package ph.ui

/**
 * The small Markdown subset the chat renderer understands. Pure: text in, blocks out; no Compose,
 * no Android. `:app` renders [MdBlock]s; the parsing rules and their tests live here.
 *
 * Supported: ATX headings (#..###), fenced code blocks (```), bullet lists (- * +, nested),
 * ordered lists (1. / 1)), blockquotes (>), horizontal rules, and the inline forms **bold**,
 * *italic* / _italic_, `code` and [text](url). Anything else is literal text: an unmatched marker
 * stays visible instead of being swallowed.
 *
 * Emphasis markers require word boundaries (CommonMark's rule for `_`): `snake_case_names` and
 * `2*3` stay literal — model replies are full of code identifiers, and mangling them would be
 * worse than not rendering markdown at all.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Code(val text: String, val language: String?) : MdBlock
    data class Bullet(val depth: Int, val spans: List<MdSpan>) : MdBlock
    data class Numbered(val depth: Int, val marker: String, val spans: List<MdSpan>) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    data object Rule : MdBlock
}

sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val spans: List<MdSpan>) : MdSpan
    data class Italic(val spans: List<MdSpan>) : MdSpan
    data class Code(val text: String) : MdSpan
    data class Link(val text: String, val url: String) : MdSpan
}

object Markdown {

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paragraph = StringBuilder()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(parseInline(paragraph.toString()))
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val indent = (line.length - line.trimStart().length) / INDENT_SPACES
            val heading = HEADING.find(trimmed)
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            when {
                trimmed.startsWith(FENCE) -> {
                    flushParagraph()
                    val language = trimmed.removePrefix(FENCE).trim().ifEmpty { null }
                    val body = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(FENCE)) {
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // consume the closing fence; a missing one is fine at EOF
                    blocks += MdBlock.Code(body.toString(), language)
                }
                trimmed.isEmpty() -> {
                    flushParagraph()
                    i++
                }
                RULE.matches(trimmed) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                    i++
                }
                heading != null -> {
                    flushParagraph()
                    blocks += MdBlock.Heading(heading.groupValues[1].length, parseInline(heading.groupValues[2].trim()))
                    i++
                }
                bullet != null -> {
                    flushParagraph()
                    blocks += MdBlock.Bullet(indent, parseInline(bullet.groupValues[4].trim()))
                    i++
                }
                numbered != null -> {
                    flushParagraph()
                    val marker = numbered.groupValues[2] + numbered.groupValues[3]
                    blocks += MdBlock.Numbered(indent, marker, parseInline(numbered.groupValues[4].trim()))
                    i++
                }
                trimmed.startsWith(">") -> {
                    flushParagraph()
                    val quoted = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        if (quoted.isNotEmpty()) quoted.append(' ')
                        quoted.append(lines[i].trim().removePrefix(">").trim())
                        i++
                    }
                    blocks += MdBlock.Quote(parseInline(quoted.toString()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                    i++
                }
            }
        }
        flushParagraph()
        return blocks
    }

    /**
     * Inline forms of one line of text. Unmatched or boundary-violating markers become literal
     * characters — the input is always preserved, only styled.
     */
    fun parseInline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        val literal = StringBuilder()
        var i = 0

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                spans += MdSpan.Text(literal.toString())
                literal.clear()
            }
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flushLiteral()
                        spans += MdSpan.Code(text.substring(i + 1, end))
                        i = end + 1
                    } else {
                        literal.append(ch)
                        i++
                    }
                }
                (text.startsWith("**", i) || text.startsWith("__", i)) && opensAtWordBoundary(text, i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = closeAt(text, marker, i + 2, 2)
                    if (end >= 0) {
                        flushLiteral()
                        spans += MdSpan.Bold(parseInline(text.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        literal.append(ch)
                        i++
                    }
                }
                (ch == '*' || ch == '_') && opensAtWordBoundary(text, i) -> {
                    val end = closeAt(text, ch.toString(), i + 1, 1)
                    if (end > i + 1) {
                        flushLiteral()
                        spans += MdSpan.Italic(parseInline(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        literal.append(ch)
                        i++
                    }
                }
                ch == '[' -> {
                    val close = text.indexOf(']', i + 1)
                    val open = if (close > i + 1 && close + 1 < text.length && text[close + 1] == '(') {
                        text.indexOf(')', close + 2)
                    } else {
                        -1
                    }
                    if (open > close) {
                        flushLiteral()
                        spans += MdSpan.Link(text.substring(i + 1, close), text.substring(close + 2, open))
                        i = open + 1
                    } else {
                        literal.append(ch)
                        i++
                    }
                }
                else -> {
                    literal.append(ch)
                    i++
                }
            }
        }
        flushLiteral()
        return spans
    }

    /** A marker only opens emphasis at a word boundary, so identifiers survive. */
    private fun opensAtWordBoundary(text: String, index: Int): Boolean =
        index == 0 || !text[index - 1].isLetterOrDigit()

    /** A closing marker must also end at a word boundary; -1 when no valid close exists. */
    private fun closeAt(text: String, marker: String, from: Int, length: Int): Int {
        var at = text.indexOf(marker, from)
        while (at >= 0) {
            val after = at + length
            if (after >= text.length || !text[after].isLetterOrDigit()) return at
            at = text.indexOf(marker, at + 1)
        }
        return -1
    }

    private const val FENCE = "```"
    private const val INDENT_SPACES = 2

    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^(\\s*)([-*+])(\\s+)(.+)$")
    private val NUMBERED = Regex("^(\\s*)(\\d{1,9})([.)])\\s+(.+)$")
    private val RULE = Regex("^([-*_])( *\\1){2,}$")
}
