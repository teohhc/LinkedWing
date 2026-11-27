package data.scripts;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked Wing v0 — the brain's answer for one tick.
 * Immutable value object; produced by BrainClient (LLM) or by fallback defaults.
 *
 * Modes are ordered by priority: a response may carry ASK only together with a
 * fallback mode (engage/hold/...) which then flies the ship while the question
 * sits on screen.
 */
public class Intent {

    public static final String ENGAGE   = "engage";    // push to preferred range and fight
    public static final String HOLD     = "hold";      // hold current range, minimal advance
    public static final String WITHDRAW = "withdraw";  // disengage away from target
    public static final String GUARD    = "guard";     // orbit, cover allies, engage threats only
    public static final String ASK      = "ask";       // needs a player decision (options in askOptions)

    public final long   createdAtMs;
    public final String mode;
    public final String targetName;     // substring match on enemy ship name/id; "" = pick nearest
    public final boolean autofire;
    public final boolean ventAllowed;
    public final boolean systemAllowed;
    public final String note;           // short; shown on HUD / combat log
    public final String askQuestion;    // non-empty when a player question is embedded
    public final List<String> askOptions; // up to 4 choices, rendered [1]..[n]

    public Intent(long createdAtMs, String mode, String targetName,
                  boolean autofire, boolean ventAllowed, boolean systemAllowed, String note,
                  String askQuestion, List<String> askOptions) {
        this.createdAtMs = createdAtMs;
        this.mode = mode == null ? ENGAGE : mode;
        this.targetName = targetName == null ? "" : targetName;
        this.autofire = autofire;
        this.ventAllowed = ventAllowed;
        this.systemAllowed = systemAllowed;
        this.note = note == null ? "" : note;
        this.askQuestion = askQuestion == null ? "" : askQuestion;
        this.askOptions = askOptions == null ? new ArrayList<String>() : askOptions;
    }

    public boolean isAsk() {
        return ASK.equals(mode) || !askQuestion.isEmpty();
    }

    public static Intent engageDefault() {
        return new Intent(System.currentTimeMillis(), ENGAGE, "",
                LinkedWingConfig.autofireByDefault, true, true, "fallback: default engage",
                "", null);
    }

    public boolean fresh() {
        return System.currentTimeMillis() - createdAtMs <= LinkedWingConfig.intentTtlMs;
    }

    /**
     * Parse an LLM JSON object. Accepted keys:
     *   mode (engage|hold|withdraw|guard|ask), target, autofire, vent, system,
     *   note, question, options (array of <=4 strings).
     * ask without question/options degrades to engage (never a null flight plan).
     */
    public static Intent parse(long now, org.json.JSONObject o) throws Exception {
        String mode = o.optString("mode", ENGAGE).toLowerCase();
        if (!mode.equals(HOLD) && !mode.equals(WITHDRAW) && !mode.equals(GUARD) && !mode.equals(ASK))
            mode = ENGAGE;

        String question = o.optString("question", "");
        List<String> options = new ArrayList<String>();
        org.json.JSONArray arr = o.optJSONArray("options");
        if (arr != null)
            for (int i = 0; i < arr.length() && options.size() < 4; i++)
                options.add(String.valueOf(arr.opt(i)));

        // ask mode without payload is meaningless -> degrade to engage
        if (mode.equals(ASK) && question.isEmpty() && options.isEmpty()) mode = ENGAGE;

        return new Intent(now, mode, o.optString("target", ""),
                !o.has("autofire") || o.optBoolean("autofire", LinkedWingConfig.autofireByDefault),
                !o.has("vent") || o.optBoolean("vent", true),
                o.optBoolean("system", true),
                o.optString("note", ""),
                question, options);
    }
}
