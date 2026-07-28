package io.jenkins.plugins.interactiveinput.view;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import net.sf.json.JSONObject;

/**
 * A single Confluence-style comment on a {@link ReviewDocument}.
 *
 * <p>A comment is either anchored to a source line ({@link #getLine()} &ge; 1) or a general,
 * document-level note ({@link #getLine()} == {@link #GENERAL}). The {@link #getBody() body} is raw
 * markdown authored by the reviewer; it is rendered to <em>sanitised</em> HTML at the REST layer
 * (never stored as HTML), so this model only ever holds the untrusted source text.
 *
 * <p>Persisted via XStream as part of the owning {@link ReviewDocument}. All transitions on the
 * comment (only {@link #setResolved(boolean)}) are performed by {@code ViewStore} while it holds the
 * owning document's monitor.
 */
public class ReviewComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Sentinel {@link #getLine()} value for a general (not line-anchored) comment. */
    public static final int GENERAL = -1;

    @NonNull
    private final String id;

    /** 1-based source line the comment is anchored to, or {@link #GENERAL} for a document-level note. */
    private final int line;

    @NonNull
    private final String body;

    @NonNull
    private final String author;

    private final long createdTs;

    private boolean resolved;

    public ReviewComment(@NonNull String id, int line, @NonNull String body, @NonNull String author, long createdTs) {
        this.id = id;
        this.line = line;
        this.body = body;
        this.author = author;
        this.createdTs = createdTs;
    }

    @NonNull
    public String getId() {
        return id;
    }

    public int getLine() {
        return line;
    }

    /** @return {@code true} if this comment is anchored to a specific source line. */
    public boolean isLineAnchored() {
        return line >= 1;
    }

    @NonNull
    public String getBody() {
        return body;
    }

    @NonNull
    public String getAuthor() {
        return author;
    }

    public long getCreatedTs() {
        return createdTs;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    /**
     * @return this comment as JSON. The {@code body} is the raw markdown source; the caller (REST
     *     layer) is responsible for adding a sanitised {@code bodyHtml} rendered via
     *     {@code MarkdownRenderer} — HTML escaping is never done here.
     */
    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("line", line);
        o.put("body", body);
        o.put("author", author);
        o.put("createdTs", createdTs);
        o.put("resolved", resolved);
        return o;
    }
}
