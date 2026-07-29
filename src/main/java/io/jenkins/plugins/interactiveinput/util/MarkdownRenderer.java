package io.jenkins.plugins.interactiveinput.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Block;
import org.commonmark.node.Document;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.AttributeProvider;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Safe markdown-to-HTML rendering for the modal's context panel and free-text preview (§7.5).
 *
 * <p>Uses {@code commonmark-java} configured to <em>escape</em> raw HTML and sanitise URLs, so
 * user-supplied markdown ({@code contextMarkdown} and free text) can never inject script or raw HTML
 * into the DOM. This replaces the spec's suggested (and nonexistent) {@code StrictEscapesExtension}
 * with the library's supported {@code escapeHtml}/{@code sanitizeUrls} switches — see SESSION_NOTES.
 *
 * <p>GitHub-Flavored Markdown extensions (tables, strikethrough, autolinks) are enabled on top of the
 * CommonMark core — those constructs are <em>not</em> in the core spec, so without the extensions a
 * pipe table renders as a literal "|"-delimited paragraph (the Interactive View table bug). See
 * {@link #EXTENSIONS}. The extensions ship with the {@code markdown-formatter} plugin dependency, so
 * no additional jar is bundled, and escaping / URL sanitisation still apply to their output.
 */
public final class MarkdownRenderer {

    /**
     * GitHub-Flavored Markdown extensions enabled for every render (both variants below). Each must be
     * registered on the {@link Parser} <em>and</em> the {@link HtmlRenderer} to take effect:
     *
     * <ul>
     *   <li>{@link TablesExtension} — GFM pipe tables ({@code | a | b |}). Without it a table is parsed
     *       as one plain paragraph of literal "|"-delimited text — the reported Interactive View bug.</li>
     *   <li>{@link StrikethroughExtension} — {@code ~~struck~~} &rarr; {@code <del>}.</li>
     *   <li>{@link AutolinkExtension} — bare URLs / e-mail addresses become links (still routed through
     *       {@code sanitizeUrls}, and it only recognises http/https/mailto/www — never {@code javascript:}).</li>
     * </ul>
     *
     * <p>These come from the {@code markdown-formatter} plugin dependency (commonmark 0.29.0, compile
     * scope) — no extra artifact is bundled into this HPI. Task-list items are intentionally not enabled:
     * that extension is not shipped by {@code markdown-formatter}, and bundling a standalone jar would
     * violate the packaging convention (and {@code hpi.strictBundledArtifacts}).
     */
    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create());

    private static final Parser PARSER =
            Parser.builder().extensions(EXTENSIONS).build();

    private static final HtmlRenderer RENDERER = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true) // literal <script> etc. are escaped, not passed through
            .sanitizeUrls(true) // strips javascript: and other unsafe URL schemes
            .percentEncodeUrls(true)
            .build();

    // Variant that records block source positions so the Interactive View editor can anchor inline
    // (per-line) comments to rendered markdown, mapping each rendered block back to its source line.
    private static final Parser PARSER_WITH_SPANS = Parser.builder()
            .extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    private static final HtmlRenderer RENDERER_WITH_SPANS = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .percentEncodeUrls(true)
            .attributeProviderFactory(context -> new SourceLineAttributeProvider())
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

    /**
     * Same safe rendering as {@link #render(String)}, but additionally tags each top-level block element
     * with {@code data-source-line="<n>"} (1-based line in the markdown source).
     *
     * <p>Used only for the Interactive View document body so a reviewer can attach inline comments to a
     * rendered block and have them map to the same source line as the Source view (and vice-versa).
     * Escaping and URL sanitisation are identical to {@link #render(String)} — the attribute provider
     * only adds a numeric line hint, never markup or user text.
     *
     * @param markdown raw markdown (may be {@code null})
     * @return sanitised HTML with source-line anchors; empty string for {@code null}/blank input
     */
    @NonNull
    public static String renderWithSourceLines(@CheckForNull String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return RENDERER_WITH_SPANS.render(PARSER_WITH_SPANS.parse(markdown));
    }

    /**
     * Adds {@code data-source-line} to top-level block elements that carry a source span. Only top-level
     * blocks are annotated (parent is the {@link Document}) so there is exactly one anchor per visible
     * block, avoiding nested/duplicate markers (e.g. list items inside a list).
     */
    private static final class SourceLineAttributeProvider implements AttributeProvider {
        @Override
        public void setAttributes(Node node, String tagName, Map<String, String> attributes) {
            if (!(node instanceof Block) || node instanceof Document) {
                return;
            }
            if (!(node.getParent() instanceof Document)) {
                return;
            }
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) {
                return;
            }
            attributes.put("data-source-line", String.valueOf(spans.get(0).getLineIndex() + 1));
        }
    }
}
