package data.scripts;

import com.fs.starfarer.api.combat.ShipAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-way ask/reply bridge. One pending question at a time.
 *
 * OUTBOUND: BrainClient asks the LLM for a decision that may include a
 *           "ask_player" mode + 2-4 options. We render them on the battle HUD
 *           as [1]..[4] and pause the game so the player can reach for the keys.
 * INBOUND:  Player presses 1..4 (handled by LinkedWingPlugin). We forward the
 *           chosen option back to the LLM server as a follow-up message so the
 *           next decision reflects the player's veto, and adopt a provisional
 *           intent immediately so the ship never stands still waiting.
 */
public class AskBridge {

    public static class Ask {
        public final long createdAtMs;
        public final String question;
        public final List<String> options;      // max 4, displayed as [1]..[n]
        public final Intent provisional;        // fly this while waiting
        public volatile int answeredWith = -1;  // 1-based, set by hotkey
        public volatile boolean forwarded = false;

        Ask(long createdAtMs, String question, List<String> options, Intent provisional) {
            this.createdAtMs = createdAtMs;
            this.question = question;
            this.options = options;
            this.provisional = provisional;
        }

        public boolean expired(long nowMs) {
            return nowMs - createdAtMs > LinkedWingConfig.askTtlMs;
        }
    }

    private volatile Ask pending = null;
    private final AtomicInteger seq = new AtomicInteger();

    public Ask current() {
        Ask a = pending;
        if (a != null && a.answeredWith > 0 && a.forwarded) pending = null;
        return a;
    }

    /** Called from BrainClient parsing when the LLM replies mode=ask_player. */
    public void openAsk(long nowMs, String question, List<String> options, Intent provisional) {
        if (options == null || options.isEmpty()) return;
        List<String> opts = new ArrayList<String>(options.size());
        for (int i = 0; i < options.size() && i < 4; i++) opts.add(options.get(i));
        pending = new Ask(nowMs, question == null ? "Your call, commander." : question,
                          opts, provisional == null ? Intent.engageDefault() : provisional);
        seq.incrementAndGet();
    }

    /** Player pressed a number key. Returns 1-based choice or -1 if no ask open. */
    public int playerAnswer(int key) {
        Ask a = pending;
        if (a == null || key < 1 || key > a.options.size()) return -1;
        a.answeredWith = key;
        return key;
    }

    /** Text of the chosen option (1-based), or "?". */
    public String answerText(Ask a) {
        if (a == null || a.answeredWith < 1 || a.answeredWith > a.options.size()) return "?";
        return a.options.get(a.answeredWith - 1);
    }

    /** Expire stale asks (player ignored us): resume provisional autonomy. */
    public void expire(long nowMs) {
        Ask a = pending;
        if (a != null && a.answeredWith < 0 && a.expired(nowMs)) pending = null;
    }
}
