package io.jenkins.plugins.interactiveinput.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Safe markdown-to-HTML rendering for the modal's context panel and free-text preview (§7.5).
 *
 * <p>Uses {@code commonmark-java} configured to <em>escape</em> raw HTML and sanitise URLs, so
 * user-supplied markdown ({@code contextMarkdown} and free text) can never inject script or raw HTML
 * into the DOM. This replaces the spec's suggested (and nonexistent) {@code StrictEscapesExtension}
 * with the library's supported {@code escapeHtml}/{@code sanitizeUrls} switches — see SESSION_NOTES.
 */
public final class MarkdownRenderer {

    private static final Parser PARSER = Parser.builder().build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .escapeHtml(true) // literal <script> etc. are escaped, not passed through
            .sanitizeUrls(true) // strips javascript: and other unsafe URL schemes
            .percentEncodeUrls(true)
            .build();

    private MarkdownRenderer() {}

    /**
     * @param markdown raw markdown (may be {@code null})
     * @return sanitised HTML safe to insert into the DOM; empty string for {@code null}/blank input
     */
    @NonNull
    public static String render(@CheckForNull String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return RENDERER.render(PARSER.parse(markdown));
    }
}
