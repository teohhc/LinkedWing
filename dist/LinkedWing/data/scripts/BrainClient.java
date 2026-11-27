package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.ArrayList;
import java.util.List;

/**
 * Linked Wing v0 — serializes battle state to a compact JSON prompt and
 * asks the local model server (serve.server, OpenAI-compatible /v1/chat/completions)
 * for the next tactical intent. Never called from the 60 Hz frame path:
 * PilotBrain pumps this from its own timer with a hard timeout.
 */
public class BrainClient {

    private static final String SYSTEM_PROMPT =
        "You are the pilot of ONE allied ship in a Starsector battle. You are called \"the Assistant\". "
      + "Reply with ONLY a single JSON object, no prose, matching exactly: "
      + "{\"mode\":\"engage|hold|withdraw|guard\",\"target\":\"<enemy name substring or empty>\","
      + "\"autofire\":true|false,\"vent\":true|false,\"system\":true|false,\"note\":\"<max 12 words, what you are doing and why>\"}. "
      + "Tactics: keep ~600px range, broadside to target, never flux-lock, withdraw below 25% hull, "
      + "prefer soft targets, use vent when flux high, hold system for shielded-down capitals.";

    private final ShipAPI self;
    private volatile AskBridge askBridge = null;
    private volatile Intent latest = Intent.engageDefault();
    private volatile boolean inFlight = false;
    private volatile long lastAttemptMs = 0;
    private volatile long lastFailureMs = -99999;
    private volatile String lastError = "";
    private volatile int requestCount = 0;
    private volatile int failureCount = 0;

    public BrainClient(ShipAPI self) {
        this.self = self;
    }

    public BrainClient(ShipAPI self, AskBridge askBridge) {
        this.self = self;
        this.askBridge = askBridge;
    }

    public Intent current() {
        if (!latest.fresh()) return Intent.engageDefault();
        return latest;
    }

    public String statusLine() {
        return "requests=" + requestCount + " failures=" + failureCount
             + (lastError.isEmpty() ? "" : " lastErr=" + lastError)
             + " mode=" + latest.mode + " target=\"" + latest.targetName + "\"";
    }

    public boolean shouldAskNow(long now) {
        if (inFlight) return false;
        if (now - lastFailureMs < LinkedWingConfig.brainBackoffMs) return false;
        return now - lastAttemptMs >= LinkedWingConfig.decisionPeriodMs;
    }

    /** Fire-and-return. Updates latest when the response lands. */
    public void askAsync(final long now) {
        final String body = buildRequest();
        inFlight = true;
        lastAttemptMs = now;
        requestCount++;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String resp = httpPost(LinkedWingConfig.llmUrl, body, LinkedWingConfig.llmTimeoutMs);
                    Intent parsed = extractIntent(resp);
                    if (parsed != null) {
                        latest = parsed;
                        if (parsed.isAsk() && askBridge != null)
                            askBridge.openAsk(System.currentTimeMillis(), parsed.askQuestion,
                                              parsed.askOptions, Intent.engageDefault());
                    }
                } catch (Exception e) {
                    failureCount++;
                    lastFailureMs = System.currentTimeMillis();
                    lastError = e.getClass().getSimpleName() + ":" + abbrev(e.getMessage());
                } finally {
                    inFlight = false;
                }
            }
        }, "linkedwing-brain");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Send the player's answer to the last ASK back to the LLM as a chat
     * follow-up, so the next intents reflect the decision.
     */
    public void forwardAnswer(final String question, final String answer) {
        final String body = buildRequest();
        // append an assistant turn (the question we asked) + user turn (the answer)
        String withHistory = body.substring(0, body.length() - 2)
            + ",{\"role\":\"assistant\",\"content\":\"" + esc("ASK: " + question) + "\"}"
            + ",{\"role\":\"user\",\"content\":\"" + esc("Player answered: " + answer + ". Continue.") + "\"}"
            + "]}";
        inFlight = true;
        requestCount++;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    String resp = httpPost(LinkedWingConfig.llmUrl, withHistory, LinkedWingConfig.llmTimeoutMs);
                    Intent parsed = extractIntent(resp);
                    if (parsed != null) latest = parsed;
                } catch (Exception e) {
                    failureCount++;
                    lastFailureMs = System.currentTimeMillis();
                    lastError = "fwd:" + e.getClass().getSimpleName();
                } finally {
                    inFlight = false;
                }
            }
        }, "linkedwing-brain");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------
    private String buildRequest() {
        StringBuilder s = new StringBuilder(1024);
        s.append('{');
        s.append("\"model\":\"").append(LinkedWingConfig.llmModel).append("\",");
        s.append("\"max_tokens\":80,");
        s.append("\"temperature\":0.2,");
        s.append("\"messages\":[");
        s.append("{\"role\":\"system\",\"content\":\"").append(esc(SYSTEM_PROMPT)).append("\"},");
        s.append("{\"role\":\"user\",\"content\":\"").append(esc(buildState())).append("\"}");
        s.append("]}");
        return s.toString();
    }

    private String buildState() {
        StringBuilder s = new StringBuilder(512);
        s.append("SELF: ").append(nameOf(self))
         .append(", hull=").append(pct(self.getHullLevel())).append("%")
         .append(", flux=").append((int) (self.getFluxLevel() * 100)).append("%");
        if (self.getSystem() != null) {
            s.append(", systemReady=").append(self.getSystem().getCooldownRemaining() <= 0f
                                            && !self.getSystem().isActive());
        }
        s.append(", deployedSec=").append((int) self.getFullTimeDeployed())
         .append(", size=").append(self.getHullSize());

        final int mySide = self.getOwner();
        s.append(" | ENEMIES: ");
        int n = 0;
        for (ShipAPI e : Global.getCombatEngine().getShips()) {
            if (e == null || !e.isAlive() || e.getHullLevel() <= 0) continue;
            if (e.getOwner() == mySide) continue;
            if (++n > LinkedWingConfig.maxEnemiesReported) { s.append("..."); break; }
            float d = Misc.getDistance(self.getLocation(), e.getLocation());
            s.append('[').append(nameOf(e))
             .append(" hp").append(pct(e.getHullLevel()))
             .append(" d").append((int) d)
             .append(e.getShield() != null && e.getShield().isOn() ? " shield" : "")
             .append(e.getFluxTracker().isOverloadedOrVenting() ? " vent" : "")
             .append("] ");
        }
        s.append("| ALLIES: ");
        n = 0;
        for (ShipAPI a : Global.getCombatEngine().getShips()) {
            if (a == null || a == self || !a.isAlive() || a.getHullLevel() <= 0) continue;
            if (a.getOwner() != mySide) continue;
            if (++n > 6) { s.append("..."); break; }
            float d = Misc.getDistance(self.getLocation(), a.getLocation());
            s.append('[').append(nameOf(a)).append(" hp").append(pct(a.getHullLevel()))
             .append(" d").append((int) d).append("] ");
        }
        return s.toString();
    }

    /** Best display name: per-ship name, else fleet member name, else hull+class. */
    static String nameOf(ShipAPI s) {
        try {
            String n = s.getName();
            if (n != null && !n.isEmpty()) return n;
            if (s.getFleetMember() != null) {
                String m = s.getFleetMember().getShipName();
                if (m != null && !m.isEmpty()) return m;
            }
            return s.getHullSpec().getHullNameWithDashClass();
        } catch (Exception e) {
            return "ship";
        }
    }

    private static int pct(float level) { return (int) (level * 100f); }

    private static Intent extractIntent(String resp) throws Exception {
        // OpenAI-shaped response: {"choices":[{"message":{"content":"..."}}]}
        org.json.JSONObject root = new org.json.JSONObject(resp);
        String content = root.getJSONArray("choices").getJSONObject(0)
                              .getJSONObject("message").getString("content");
        int a = content.indexOf('{');
        int b = content.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        long now = System.currentTimeMillis();
        Intent in = Intent.parse(now, new org.json.JSONObject(content.substring(a, b + 1)));
        if (LinkedWingConfig.logDecisions) {
            Global.getLogger(BrainClient.class).info("linkedwing intent: " + in.mode
                + " target=\"" + in.targetName + "\" autofire=" + in.autofire + " note=" + in.note);
        }
        return in;
    }

    // ------------------------------------------------------------------
    // NOTE: the game's ScriptClassLoader only whitelists a few java.io classes
    // (InputStream/OutputStream/Reader/PrintWriter/IOException...). Stay inside
    // that list here; no File, no ByteArrayOutputStream, no reflection.
    private static String httpPost(String urlStr, String body, long timeoutMs) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        c.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        c.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] b = body.getBytes("UTF-8");
        c.setFixedLengthStreamingMode(b.length);
        java.io.OutputStream os = c.getOutputStream();
        os.write(b);
        os.close();
        int code = c.getResponseCode();
        java.io.InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0 && sb.length() < 1 << 20) {
                sb.append(new String(buf, 0, r, "UTF-8"));
            }
            in.close();
        }
        c.disconnect();
        if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code + " " + sb);
        return sb.toString();
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
