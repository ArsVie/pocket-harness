package ph.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {

    // ---- block level -------------------------------------------------------------------------

    @Test
    fun plainLineIsAParagraph() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan.Text("hello world")))),
            Markdown.parse("hello world"),
        )
    }

    @Test
    fun consecutiveLinesJoinIntoOneParagraph() {
        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan.Text("one two"))),
                MdBlock.Paragraph(listOf(MdSpan.Text("three"))),
            ),
            Markdown.parse("one\n     two\n\nthree"),
        )
    }

    @Test
    fun headingsCarryTheirLevelAndInlineSpans() {
        assertEquals(
            listOf(
                MdBlock.Heading(1, listOf(MdSpan.Text("One"))),
                MdBlock.Heading(3, listOf(MdSpan.Text("a "), MdSpan.Bold(listOf(MdSpan.Text("b"))))),
            ),
            Markdown.parse("# One\n\n### a **b**"),
        )
    }

    @Test
    fun hashWithoutSpaceIsNotAHeading() {
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan.Text("#tag")))), Markdown.parse("#tag"))
    }

    @Test
    fun bulletsCarryDepth() {
        assertEquals(
            listOf(
                MdBlock.Bullet(0, listOf(MdSpan.Text("top"))),
                MdBlock.Bullet(1, listOf(MdSpan.Text("nested"))),
                MdBlock.Bullet(0, listOf(MdSpan.Text("again"))),
            ),
            Markdown.parse("- top\n  - nested\n+ again"),
        )
    }

    @Test
    fun orderedListsKeepTheirPunctuation() {
        assertEquals(
            listOf(
                MdBlock.Numbered(0, "1.", listOf(MdSpan.Text("first"))),
                MdBlock.Numbered(0, "2)", listOf(MdSpan.Text("second"))),
            ),
            Markdown.parse("1. first\n2) second"),
        )
    }

    @Test
    fun fencedCodeKeepsItsBodyAndLanguage() {
        assertEquals(
            listOf(MdBlock.Code("a && b\nc", "text")),
            Markdown.parse("```text\na && b\nc\n```"),
        )
    }

    @Test
    fun unterminatedFenceRunsToTheEnd() {
        assertEquals(listOf(MdBlock.Code("tail", null)), Markdown.parse("```\ntail"))
    }

    @Test
    fun quotesJoinConsecutiveLines() {
        assertEquals(
            listOf(MdBlock.Quote(listOf(MdSpan.Text("one two")))),
            Markdown.parse("> one\n> two"),
        )
    }

    @Test
    fun rulesBecomeHorizontalRules() {
        assertEquals(listOf(MdBlock.Rule), Markdown.parse("---"))
        assertEquals(listOf(MdBlock.Rule), Markdown.parse("- - -"))
    }

    @Test
    fun emptyInputIsNoBlocks() {
        assertEquals(emptyList(), Markdown.parse(""))
        assertEquals(emptyList(), Markdown.parse("  \n\n "))
    }

    @Test
    fun crlfIsNormalized() {
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan.Text("a b")))), Markdown.parse("a\r\nb"))
    }

    @Test
    fun realisticReplyParsesIntoTheExpectedShape() {
        val reply = listOf(
            "Implemented `tail N FILE` in `../llm-test2/tsvkit`.",
            "",
            "### Changes made",
            "",
            "- **Added `lib/cmd-tail.awk`**",
            "  - Uses a circular buffer.",
            "",
            "Final test run:",
            "",
            "```text",
            "ok   01-count",
            "",
            "12 passed, 0 failed",
            "```",
        ).joinToString("\n")
        val kinds = Markdown.parse(reply).map { it::class.simpleName }
        assertEquals(listOf("Paragraph", "Heading", "Bullet", "Bullet", "Paragraph", "Code"), kinds)
    }

    // ---- inline level ------------------------------------------------------------------------

    @Test
    fun inlineBoldItalicAndCode() {
        assertEquals(
            listOf(
                MdSpan.Text("a "),
                MdSpan.Bold(listOf(MdSpan.Text("b"))),
                MdSpan.Text(" "),
                MdSpan.Italic(listOf(MdSpan.Text("c"))),
                MdSpan.Text(" "),
                MdSpan.Code("d"),
            ),
            Markdown.parseInline("a **b** *c* `d`"),
        )
    }

    @Test
    fun underlineBoldFormWorks() {
        assertEquals(listOf(MdSpan.Bold(listOf(MdSpan.Text("b")))), Markdown.parseInline("__b__"))
    }

    @Test
    fun boldMayContainCode() {
        assertEquals(
            listOf(MdSpan.Bold(listOf(MdSpan.Text("x "), MdSpan.Code("y")))),
            Markdown.parseInline("**x `y`**"),
        )
    }

    @Test
    fun codeWinsOverEmphasisMarkers() {
        assertEquals(listOf(MdSpan.Code("*not italic*")), Markdown.parseInline("`*not italic*`"))
    }

    @Test
    fun snakeCaseAndMathStayLiteral() {
        assertEquals(
            listOf(MdSpan.Text("char_count and 2*3 * 4")),
            Markdown.parseInline("char_count and 2*3 * 4"),
        )
    }

    @Test
    fun unmatchedMarkersStayVisible() {
        assertEquals(listOf(MdSpan.Text("**lonely")), Markdown.parseInline("**lonely"))
        assertEquals(listOf(MdSpan.Text("a `b")), Markdown.parseInline("a `b"))
    }

    @Test
    fun emptyCodeSpanStaysLiteral() {
        assertEquals(listOf(MdSpan.Text("``")), Markdown.parseInline("``"))
    }

    @Test
    fun linksParse() {
        assertEquals(
            listOf(MdSpan.Text("see "), MdSpan.Link("docs", "https://x.y")),
            Markdown.parseInline("see [docs](https://x.y)"),
        )
    }

    @Test
    fun malformedLinkStaysLiteral() {
        assertEquals(listOf(MdSpan.Text("[docs](broken")), Markdown.parseInline("[docs](broken"))
    }

    @Test
    fun underscoreItalicAtWordBoundaryWorks() {
        assertEquals(
            listOf(MdSpan.Text("say "), MdSpan.Italic(listOf(MdSpan.Text("this")))),
            Markdown.parseInline("say _this_"),
        )
    }
}
