package io.jenkins.plugins.interactiveinput.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Security-focused tests for {@link MarkdownRenderer}: user-supplied markdown must never inject raw
 * HTML or unsafe URL schemes into the modal DOM (§7.5).
 */
class MarkdownRendererTest {

    @Test
    void nullAndBlankRenderToEmptyString() {
        assertEquals("", MarkdownRenderer.render(null));
        assertEquals("", MarkdownRenderer.render(""));
    }

    @Test
    void rendersBasicMarkdown() {
        String html = MarkdownRenderer.render("**bold** and _em_");
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<em>em</em>"), html);
    }

    @Test
    void escapesRawHtmlSoScriptCannotExecute() {
        String html = MarkdownRenderer.render("<script>alert('xss')</script>");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped, was: " + html);
        assertTrue(html.contains("&lt;script&gt;"), "expected escaped script tag, was: " + html);
    }

    @Test
    void stripsJavascriptUrlScheme() {
        String html = MarkdownRenderer.render("[click](javascript:alert('xss'))");
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised, was: " + html);
    }

    @Test
    void keepsSafeHttpLinks() {
        String html = MarkdownRenderer.render("[docs](https://www.jenkins.io/)");
        assertTrue(html.contains("href=\"https://www.jenkins.io/\""), html);
    }

    @Test
    void renderWithSourceLinesTagsTopLevelBlocksWithTheirSourceLine() {
        // Line 1 = heading, line 2 = blank, line 3 = paragraph.
        String html = MarkdownRenderer.renderWithSourceLines("# Heading\n\nA paragraph.");
        assertTrue(html.contains("data-source-line=\"1\""), "heading anchors to line 1: " + html);
        assertTrue(html.contains("data-source-line=\"3\""), "paragraph anchors to line 3: " + html);
    }

    @Test
    void renderWithSourceLinesKeepsTheSameSanitisation() {
        String html = MarkdownRenderer.renderWithSourceLines("<script>alert(1)</script>\n\n[l](javascript:alert(1))");
        assertFalse(html.contains("<script>"), "raw <script> must be escaped: " + html);
        assertFalse(html.contains("javascript:"), "javascript: scheme must be sanitised: " + html);
    }

    @Test
    void renderWithSourceLinesHandlesNullAndBlank() {
        assertEquals("", MarkdownRenderer.renderWithSourceLines(null));
        assertEquals("", MarkdownRenderer.renderWithSourceLines(""));
    }

    // ---- GitHub-Flavored Markdown extensions (tables / strikethrough / autolink) ----
    // Regression: GFM tables are not in the CommonMark core spec, so before the TablesExtension was
    // registered a pipe table rendered as a literal "|"-delimited paragraph (the Interactive View bug).

    @Test
    void rendersGfmPipeTableAsHtmlTable() {
        String md = "| Aspect | Value |\n|---|---|\n| length | 1..255 |\n| wildcard | FF |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "expected an HTML table, was: " + html);
        assertTrue(html.contains("<thead>") && html.contains("<th>Aspect</th>"), "expected a header row: " + html);
        assertTrue(html.contains("<tbody>") && html.contains("<td>length</td>"), "expected a body cell: " + html);
        // The bug symptom was the literal separator row leaking through as text.
        assertFalse(html.contains("|---|"), "the delimiter row must not appear as literal text: " + html);
    }

    @Test
    void tableColumnAlignmentIsHonoured() {
        String md = "| L | C | R |\n|:--|:-:|--:|\n| a | b | c |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("align=\"center\""), "center column must carry align=center: " + html);
        assertTrue(html.contains("align=\"right\""), "right column must carry align=right: " + html);
    }

    @Test
    void tableCellHtmlIsStillEscaped() {
        // Extensions must not weaken escaping: a <script> inside a table cell stays inert.
        String md = "| col |\n|-----|\n| <script>alert(1)</script> |";
        String html = MarkdownRenderer.render(md);
        assertTrue(html.contains("<table>"), "still a table: " + html);
        assertFalse(html.contains("<script>"), "raw <script> in a cell must be escaped: " + html);
        assertTrue(html.contains("&lt;script&gt;"), "expected escaped script tag: " + html);
    }

    @Test
    void renderWithSourceLinesRendersTables() {
        // The document body uses the source-line variant; it must render tables too (not just render()).
        String md = "# T\n\n| A | B |\n|---|---|\n| 1 | 2 |";
        String html = MarkdownRenderer.renderWithSourceLines(md);
        // The source-line variant tags top-level blocks, so the table opens as <table data-source-line=..>.
        assertTrue(html.contains("<table"), "source-line variant must also render tables: " + html);
        assertTrue(html.contains("data-source-line"), "the table becomes a commentable, source-anchored block: " + html);
        assertFalse(html.contains("|---|"), "no literal delimiter row: " + html);
    }

    @Test
    void rendersStrikethrough() {
        String html = MarkdownRenderer.render("this is ~~struck~~ text");
        assertTrue(html.contains("<del>struck</del>"), "~~x~~ must render as <del>: " + html);
    }

    @Test
    void autolinksBareUrls() {
        String html = MarkdownRenderer.render("see https://www.jenkins.io/ for docs");
        assertTrue(html.contains("href=\"https://www.jenkins.io/\""), "bare URL must become a link: " + html);
    }

    @Test
    void autolinkStillSanitisesUnsafeSchemes() {
        // A bare, unsafe scheme must not become a clickable javascript: link.
        String html = MarkdownRenderer.render("click [here](javascript:alert(1)) or ~~nope~~");
        assertFalse(html.contains("javascript:alert"), "javascript: must still be sanitised: " + html);
    }
}
