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
}
