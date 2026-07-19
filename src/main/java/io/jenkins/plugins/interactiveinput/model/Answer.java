package io.jenkins.plugins.interactiveinput.model;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.Serializable;
import net.sf.json.JSONObject;

/**
 * An immutable record of a human's answer to a {@link Question}.
 *
 * <p>Exactly one of {@link #getChoiceId()} or {@link #getFreeText()} is normally set. The special
 * choice id {@code __deny__} is used by the modal's "Deny" action.
 */
public class Answer implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Sentinel choice id submitted by the modal's "Deny" button. */
    public static final String DENY_CHOICE_ID = "__deny__";

    @NonNull
    private final String questionId;

    @CheckForNull
    private final String choiceId;

    @CheckForNull
    private final String freeText;

    @NonNull
    private final String answeredBy;

    private final long answeredTs;

    /**
     * @param questionId the answered question's id (required)
     * @param choiceId   the picked choice id, or {@code null} if a free-text answer
     * @param freeText   the free-text answer, or {@code null} if a choice was picked
     * @param answeredBy Jenkins user id of the submitter (or {@code "SYSTEM"} for internal paths)
     * @param answeredTs epoch millis when the answer was recorded
     */
    public Answer(
            @NonNull String questionId,
            @CheckForNull String choiceId,
            @CheckForNull String freeText,
            @NonNull String answeredBy,
            long answeredTs) {
        this.questionId = questionId;
        this.choiceId = choiceId;
        this.freeText = freeText;
        this.answeredBy = answeredBy;
        this.answeredTs = answeredTs;
    }

    @NonNull
    public String getQuestionId() {
        return questionId;
    }

    @CheckForNull
    public String getChoiceId() {
        return choiceId;
    }

    @CheckForNull
    public String getFreeText() {
        return freeText;
    }

    @NonNull
    public String getAnsweredBy() {
        return answeredBy;
    }

    public long getAnsweredTs() {
        return answeredTs;
    }

    /** @return {@code true} if this answer is the sentinel "deny" answer. */
    public boolean isDeny() {
        return DENY_CHOICE_ID.equals(choiceId);
    }

    /**
     * Resolve the value handed back to the pipeline by the {@code askInteractive} step.
     *
     * @return the choice id {@link String} when a choice was picked, or a {@code {text, choice}}
     *     map when free text was used, matching the documented step contract (§6.1).
     */
    @NonNull
    public Object toStepReturnValue() {
        if (choiceId != null) {
            return choiceId;
        }
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("text", freeText);
        m.put("choice", null);
        return m;
    }

    @NonNull
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("questionId", questionId);
        o.put("choiceId", choiceId);
        o.put("freeText", freeText);
        o.put("answeredBy", answeredBy);
        o.put("answeredTs", answeredTs);
        return o;
    }
}
