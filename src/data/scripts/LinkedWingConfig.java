package data.scripts;

/**
 * Linked Wing v0 — central configuration.
 *
 * Everything tunable lives here. All values can be overridden at runtime by
 * dropping a properties file at:
 *   <starsector install>/../linkedwing.properties        (preferred)
 *   or java system property -Dlinkedwing.config=/path/to/file
 *
 * Key names are listed below; leave a key out to keep the default.
 */
public class LinkedWingConfig {

    // ------------------------------------------------------------------
    // Attachment (which hull is "the assistant's ship")
    // ------------------------------------------------------------------
    /** A fleet member whose SHIP_ROLE_TAG contains this (case-insensitive) is claimed. */
    public static String TAG = "linkedwing";

    // ------------------------------------------------------------------
    // Reflex layer (runs locally at 60 Hz; the LLM never touches this path)
    // ------------------------------------------------------------------
    public static float  preferredRangePx     = 620f;   // stay roughly here from assigned target
    public static float  orbitBandMin         = 0.80f;  // fraction of preferredRange to start backing off
    public static float  orbitBandMax         = 1.25f;  // fraction to start closing in
    public static float  emergencyFluxPct     = 0.92f;  // above this flux % -> hard vent
    public static float  emergencyHullPct     = 0.28f;  // below this hull % -> withdraw unless overridden
    public static boolean autofireByDefault   = true;   // vanilla-style autofire unless brain says hold

    // ------------------------------------------------------------------
    // Brain link (the model server on localhost:8080 via your serve.server)
    // ------------------------------------------------------------------
    public static String  llmUrl              = "http://127.0.0.1:8080/v1/chat/completions";
    public static String  llmModel            = "Qwen3.8-Flash-Next NVIDIA-NVFP4";
    public static long    llmTimeoutMs        = 2500;   // hard socket timeout
    public static long    decisionPeriodMs    = 1200;   // how often we re-ask the brain
    public static int     maxEnemiesReported  = 10;     // state payload cap
    public static long    intentTtlMs         = 8000;   // stale intents are ignored; fallback rules win
    public static long    brainBackoffMs      = 4000;   // after a failure, pause this long

    // ------------------------------------------------------------------
    // Player comms & pause-for-decision
    // ------------------------------------------------------------------
    public static boolean pauseForDecision    = true;   // pause battle while awaiting a critical decision
    public static long    askTtlMs            = 20000;  // a question on screen auto-expires after this long
    public static long    consultPeriodMs     = 30000;  // campaign: how often we build a briefing for the LLM

    // ------------------------------------------------------------------
    // Debug
    // ------------------------------------------------------------------
    public static boolean logAttach           = true;
    public static boolean logDecisions        = false;  // every LLM decision to starsector.log
    public static boolean logFallback         = false;

    // ------------------------------------------------------------------
    public static void loadFromSystemProperty() {
        String path = System.getProperty("linkedwing.config");
        if (path == null) path = "../linkedwing.properties";
        java.io.File f = new java.io.File(path);
        if (!f.isFile()) return;
        java.util.Properties p = new java.util.Properties();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            p.load(in);
        } catch (Exception e) { return; }
        TAG              = p.getProperty("tag", TAG);
        preferredRangePx = floatOf(p, "preferred_range_px", preferredRangePx);
        emergencyFluxPct = floatOf(p, "emergency_flux_pct", emergencyFluxPct);
        emergencyHullPct = floatOf(p, "emergency_hull_pct", emergencyHullPct);
        llmUrl           = p.getProperty("llm_url", llmUrl);
        llmModel         = p.getProperty("llm_model", llmModel);
        llmTimeoutMs     = longOf(p, "llm_timeout_ms", llmTimeoutMs);
        decisionPeriodMs = longOf(p, "decision_period_ms", decisionPeriodMs);
        intentTtlMs      = longOf(p, "intent_ttl_ms", intentTtlMs);
        autofireByDefault= boolOf(p, "autofire_default", autofireByDefault);
        pauseForDecision = boolOf(p, "pause_for_decision", pauseForDecision);
        askTtlMs         = longOf(p, "ask_ttl_ms", askTtlMs);
        consultPeriodMs  = longOf(p, "consult_period_ms", consultPeriodMs);
        logAttach        = boolOf(p, "log_attach", logAttach);
        logDecisions     = boolOf(p, "log_decisions", logDecisions);
    }

    private static float floatOf(java.util.Properties p, String k, float d) {
        try { return p.containsKey(k) ? Float.parseFloat(p.getProperty(k).trim()) : d; } catch (Exception e) { return d; }
    }
    private static long longOf(java.util.Properties p, String k, long d) {
        try { return p.containsKey(k) ? Long.parseLong(p.getProperty(k).trim()) : d; } catch (Exception e) { return d; }
    }
    private static boolean boolOf(java.util.Properties p, String k, boolean d) {
        String v = p.getProperty(k);
        return v == null ? d : "true".equalsIgnoreCase(v.trim());
    }
}
