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
}
