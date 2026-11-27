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
        // Starsector's ScriptClassLoader blocks java.io.File and most java.io
        // classes for mod scripts, but *system properties* are readable.
        // Configure via JVM args, e.g.:
        //   -Dlinkedwing.llm_url=http://host:port/v1/chat/completions
        String v;
        if ((v = System.getProperty("linkedwing.tag")) != null && !v.isEmpty()) TAG = v;
        if ((v = System.getProperty("linkedwing.preferred_range_px")) != null) preferredRangePx = flt(v, preferredRangePx);
        if ((v = System.getProperty("linkedwing.emergency_flux_pct")) != null) emergencyFluxPct = flt(v, emergencyFluxPct);
        if ((v = System.getProperty("linkedwing.emergency_hull_pct")) != null) emergencyHullPct = flt(v, emergencyHullPct);
        if ((v = System.getProperty("linkedwing.llm_url")) != null && !v.isEmpty()) llmUrl = v;
        if ((v = System.getProperty("linkedwing.llm_model")) != null && !v.isEmpty()) llmModel = v;
        if ((v = System.getProperty("linkedwing.llm_timeout_ms")) != null) llmTimeoutMs = lng(v, llmTimeoutMs);
        if ((v = System.getProperty("linkedwing.decision_period_ms")) != null) decisionPeriodMs = lng(v, decisionPeriodMs);
        if ((v = System.getProperty("linkedwing.intent_ttl_ms")) != null) intentTtlMs = lng(v, intentTtlMs);
        if ((v = System.getProperty("linkedwing.brain_backoff_ms")) != null) brainBackoffMs = lng(v, brainBackoffMs);
        if ((v = System.getProperty("linkedwing.autofire_default")) != null) autofireByDefault = "true".equalsIgnoreCase(v.trim());
        if ((v = System.getProperty("linkedwing.pause_for_decision")) != null) pauseForDecision = "true".equalsIgnoreCase(v.trim());
        if ((v = System.getProperty("linkedwing.ask_ttl_ms")) != null) askTtlMs = lng(v, askTtlMs);
        if ((v = System.getProperty("linkedwing.consult_period_ms")) != null) consultPeriodMs = lng(v, consultPeriodMs);
        if ((v = System.getProperty("linkedwing.log_attach")) != null) logAttach = "true".equalsIgnoreCase(v.trim());
        if ((v = System.getProperty("linkedwing.log_decisions")) != null) logDecisions = "true".equalsIgnoreCase(v.trim());
    }

    private static float flt(String s, float d) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return d; }
    }
    private static long lng(String s, long d) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return d; }
    }

}
