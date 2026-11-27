package data.scripts;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.mission.FleetSide;
import org.lwjgl.input.Keyboard;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Linked Wing — battle-side orchestrator for the tagged hull.
 *
 *   1. find the tagged ship (string tag set by the hullmod), attach once
 *   2. F1 cycles authority: AUTOPILOT (vanilla AI) -> WING (we fly) -> FLEET
 *      (we fly + push aggressive AI flags on allies; strategic handover is campaign-side)
 *   3. BrainClient pump (async LLM ~1.2 Hz) -> Intent -> FlightLayer (60 Hz reflexes)
 *   4. AskBridge: questions rendered via floating text with [1]..[4] hotkeys
 *   5. pause-for-decision: we pause ONLY for our own pending question and never
 *      unpause a pause the player set (tracked via wasPausedWhenWePaused)
 *
 * Safety: if the LLM link dies the ship flies on fallback intents; if the plugin
 * detaches the hull always returns to vanilla AI with controls released.
 */
public class LinkedWingPlugin extends BaseEveryFrameCombatPlugin {

    private static LinkedWingPlugin instance;

    private CombatEngineAPI engine;
    private ShipAPI ship;
    private BrainClient brain;
    private FlightLayer flight;
    private final AskBridge ask = new AskBridge();

    private FlightLayer.Authority authority = FlightLayer.Authority.WING;

    // pause bookkeeping — never clobber a player pause
    private boolean wePaused = false;
    private boolean wasPausedWhenWePaused = false;

    // sides (owner ints) resolved lazily
    private Set<Integer> mySides, enemySides;

    // floating HUD
    private float hudTimer = 0f;
    private String lastStatus = "";

    public static LinkedWingPlugin get() { return instance; }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        instance = this;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        try {
            advanceSafe(amount, events);
        } catch (Throwable t) {
            // never crash a battle over our own bug; log once per second at most
            long now = System.currentTimeMillis();
            if (now - lastErrorLogMs > 1000) {
                lastErrorLogMs = now;
                Global.getLogger(LinkedWingPlugin.class).warn("LinkedWing advance error", t);
            }
            disabled = true; // stay quiet but keep HUD-free cleanup below
            cleanup();
        }
    }
    private long lastErrorLogMs = 0;
    private boolean disabled = false;

    private void advanceSafe(float amount, List<InputEventAPI> events) {
        if (disabled) return;
        if (engine == null) engine = Global.getCombatEngine();
        if (engine == null || engine.isCombatOver()) { cleanup(); return; }

        // locate our hull every frame (cheap: getShips is small; also survives ship swaps)
        ShipAPI tagged = findTaggedShip();
        if (tagged != null && tagged != ship) {
            ship = tagged;
            flight = new FlightLayer(ship);
            brain = new BrainClient(ship, ask);
            if (authority != FlightLayer.Authority.AUTOPILOT) flight.takeControl();
            if (LinkedWingConfig.logAttach)
                Global.getLogger(LinkedWingPlugin.class).info("LinkedWing claimed "
                        + BrainClient.nameOf(ship) + " [" + ship.getHullSpec().getHullNameWithDashClass() + "]");
        }
        if (ship == null || !ship.isAlive()) { cleanup(); return; }

        long now = System.currentTimeMillis();
        ask.expire(now);

        if (authority == FlightLayer.Authority.AUTOPILOT) {
            if (flight != null) flight.releaseControl();
            maybeUnpause();
            return;
        }

        flight.takeControl();
        Intent intent = ask.current() != null ? ask.current().provisional : brain.current();
        ShipAPI target = resolveTarget(intent);
        flight.advance(intent, target, engine.getMissiles());

        if (brain.shouldAskNow(now)) brain.askAsync(now);
        maybeForwardAnswer();
        handlePause();

        // HUD: floating status above our ship ~1.5 s cadence; ask options every 2 s
        hudTimer -= amount;
        if (hudTimer <= 0f) {
            hudTimer = 1.5f;
            String line = "LW " + authority.name() + " " + intent.mode
                    + " " + (brain != null ? brain.statusLine() : "--")
                    + (ship.controlsLocked() ? "" : " [idle]");
            if (!line.equals(lastStatus)) {
                addText(ship.getLocation(), line, 13f, cyan(), 1.6f);
                lastStatus = line;
            }
        }
        AskBridge.Ask a = ask.current();
        if (a != null && System.currentTimeMillis() - a.createdAtMs < LinkedWingConfig.askTtlMs) {
            hudAskTimer -= amount;
            if (hudAskTimer <= 0f) {
                hudAskTimer = 2f;
                addText(ship.getLocation(), "COMMANDER: " + a.question, 15f, Color.yellow, 2f);
                for (int i = 0; i < a.options.size(); i++)
                    addText(ship.getLocation(),
                            "[" + (i + 1) + "] " + a.options.get(i)
                                    + (a.answeredWith == i + 1 ? "  << CHOSEN" : ""),
                            13f, a.answeredWith == i + 1 ? Color.white : cyan(), 2f);
            }
        }
    }

    private float hudAskTimer = 0f;

    private void cleanup() {
        if (flight != null) flight.releaseControl();
        maybeUnpause();
        ship = null; flight = null; brain = null;
    }

    private ShipAPI findTaggedShip() {
        for (ShipAPI s : engine.getShips()) {
            if (s == null || !s.isAlive() || s.isFighter() || s.isDrone()) continue;
            if (s.hasTag(LinkedWingConfig.TAG)) return s;
            // robust fallback: the hullmod id is installed on the variant
            try {
                if (s.getVariant() != null && s.getVariant().getHullMods().contains("linked_wing_co_pilot"))
                    return s;
            } catch (Throwable t) { /* non-ship entity or obfuscated edge */ }
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        // raw key state for F1/F2 (char-based matching only works for keys that produce a char)
        if (Keyboard.isCreated()) {
            boolean f1 = Keyboard.isKeyDown(Keyboard.KEY_F1);
            boolean f2 = Keyboard.isKeyDown(Keyboard.KEY_F2);
            if ((f1 || f2) && !fKeyHeld) {
                fKeyHeld = true;
                if (f1) cycleAuthority(); else togglePauseForDecision();
            } else if (!f1 && !f2) {
                fKeyHeld = false;
            }
        }
        for (InputEventAPI e : events) {
            if (!e.isKeyboardEvent() || e.isRepeat() || e.isKeyUpEvent()) continue;
            char c = e.getEventChar();
            if (c >= '1' && c <= '4' && ask.current() != null) {
                int picked = ask.playerAnswer(c - '0');
                if (picked > 0) {
                    e.consume();
                    addText(ship == null ? new Vector2f(960, 540) : ship.getLocation(),
                            "CMDR: option " + picked, 14f, Color.white, 2f);
                    maybeUnpause();
                }
            }
        }
    }

    private boolean fKeyHeld = false;

    private void cycleAuthority() {
        if (ship == null) return;
        authority = authority.next();
        switch (authority) {
            case AUTOPILOT:
                flight.releaseControl();
                ship.resetDefaultAI();
                addText(ship.getLocation(), "LW -> AUTOPILOT (vanilla AI)", 15f, Color.orange, 2.5f);
                break;
            case WING:
                flight.takeControl();
                addText(ship.getLocation(), "LW -> WING (I have the conn)", 15f, cyan(), 2.5f);
                break;
            case FLEET:
                flight.takeControl();
                for (ShipAPI a : allies())
                    a.getAIFlags().setFlag(ShipwideAIFlags.AIFlags.HARASS_MOVE_IN, 10f);
                addText(ship.getLocation(), "LW -> FLEET(battle): allies told to push; strategic handover: campaign", 15f, cyan(), 3f);
                break;
        }
    }

    private void togglePauseForDecision() {
        LinkedWingConfig.pauseForDecision = !LinkedWingConfig.pauseForDecision;
        if (!LinkedWingConfig.pauseForDecision) maybeUnpause();
        if (ship != null) addText(ship.getLocation(),
                "LW pause-for-decision " + (LinkedWingConfig.pauseForDecision ? "ON" : "OFF"),
                14f, cyan(), 2f);
    }

    // ------------------------------------------------------------------ pause (safe)

    private void handlePause() {
        if (!LinkedWingConfig.pauseForDecision) { maybeUnpause(); return; }
        long now = System.currentTimeMillis();
        AskBridge.Ask a = ask.current();
        boolean awaitingPlayer = a != null && a.answeredWith < 0
                && now - a.createdAtMs < LinkedWingConfig.askTtlMs;
        if (awaitingPlayer && !wePaused && !engine.isPaused()) {
            engine.setPaused(true);
            wePaused = true;
        } else if (!awaitingPlayer || (a != null && a.answeredWith > 0)) {
            maybeUnpause();
        }
    }

    /** Un-pause ONLY if the game was NOT already paused when we paused it. */
    private void maybeUnpause() {
        if (wePaused && engine != null) {
            if (engine.isPaused() && !wasPausedWhenWePaused) engine.setPaused(false);
        }
        wePaused = false;
        wasPausedWhenWePaused = false;
    }

    /** handlePause sets the flag remembering whether the player had already paused. */

    // ------------------------------------------------------------------ sides / targeting

    private Set<Integer> sides(FleetSide side) {
        Set<Integer> out = new HashSet<Integer>();
        try {
            addOwners(out, engine.getFleetManager(side).getDeployedCopy());
            addOwners(out, engine.getFleetManager(side).getReservesCopy());
        } catch (Throwable t) { /* mission without a fleet on this side */ }
        return out;
    }

    private static void addOwners(Set<Integer> out, List<FleetMemberAPI> members) {
        if (members == null) return;
        for (FleetMemberAPI m : members) {
            if (m == null) continue;
            out.add(m.getOwner());
        }
    }

    private void resolveSidesIfNeeded() {
        if (mySides != null && enemySides != null) return;
        mySides = sides(FleetSide.PLAYER);
        enemySides = sides(FleetSide.ENEMY);
        // if the tagged ship's owner isn't on the player side, swap
        if (!mySides.contains(ship.getOwner())) {
            Set<Integer> t = mySides; mySides = enemySides; enemySides = t;
        }
    }

    private boolean hostile(ShipAPI s) {
        resolveSidesIfNeeded();
        return enemySides.contains(s.getOwner());
    }

    private boolean friendly(ShipAPI s) {
        resolveSidesIfNeeded();
        return mySides.contains(s.getOwner());
    }

    private List<ShipAPI> hostiles(String nameFragment) {
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        for (ShipAPI s : engine.getShips()) {
            if (s == null || !s.isAlive() || s.isDrone()) continue;
            if (!hostile(s)) continue;
            if (nameFragment != null && !nameFragment.isEmpty()
                    && !BrainClient.nameOf(s).toLowerCase().contains(nameFragment.toLowerCase()))
                continue;
            out.add(s);
        }
        return out;
    }

    private List<ShipAPI> allies() {
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        for (ShipAPI s : engine.getShips()) {
            if (s == null || s == ship || !s.isAlive() || s.isFighter() || s.isDrone()) continue;
            if (!friendly(s)) continue;
            out.add(s);
        }
        return out;
    }

    /** Nearest hostile, or name-preferring pick. null if none. */
    private ShipAPI resolveTarget(Intent intent) {
        ShipAPI best = nearest(hostiles(intent.targetName));
        if (best == null && intent.targetName != null && !intent.targetName.isEmpty())
            best = nearest(hostiles("")); // name unmatched -> nearest anything hostile
        if (best != null) ship.setShipTarget(best);
        return best;
    }

    static ShipAPI nearestOf(List<ShipAPI> list, ShipAPI from) {
        ShipAPI best = null;
        float bd = Float.MAX_VALUE;
        for (ShipAPI s : list) {
            float dx = s.getLocation().x - from.getLocation().x;
            float dy = s.getLocation().y - from.getLocation().y;
            float d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    private ShipAPI nearest(List<ShipAPI> list) {
        return nearestOf(list, ship);
    }

    static ShipAPI nearestEnemyOf(ShipAPI from) {
        LinkedWingPlugin p = instance;
        if (p == null || p.engine == null || from == null) return null;
        List<ShipAPI> l = new ArrayList<ShipAPI>();
        for (ShipAPI s : p.engine.getShips())
            if (s != null && s.isAlive() && !s.isDrone() && p.hostile(s)) l.add(s);
        return nearestOf(l, from);
    }

    public static java.util.List<ShipAPI> enemiesOf(ShipAPI from) {
        LinkedWingPlugin p = instance;
        List<ShipAPI> l = new ArrayList<ShipAPI>();
        if (p == null || p.engine == null) return l;
        for (ShipAPI s : p.engine.getShips())
            if (s != null && s.isAlive() && p.hostile(s)) l.add(s);
        return l;
    }

    public static java.util.List<ShipAPI> alliesOf(ShipAPI from) {
        LinkedWingPlugin p = instance;
        List<ShipAPI> l = new ArrayList<ShipAPI>();
        if (p == null || p.engine == null) return l;
        for (ShipAPI s : p.engine.getShips())
            if (s != null && s != from && s.isAlive() && p.friendly(s)) l.add(s);
        return l;
    }

    // ------------------------------------------------------------------ answers -> brain

    private void maybeForwardAnswer() {
        AskBridge.Ask a = ask.current();
        if (a != null && a.answeredWith > 0 && !a.forwarded && brain != null) {
            a.forwarded = true;
            brain.forwardAnswer(a.question, ask.answerText(a));
        }
    }

    // ------------------------------------------------------------------ HUD / util

    private void addText(Vector2f loc, String s, float size, Color c, float life) {
        try { engine.addFloatingTextAlways(loc, s, size, c, ship, 20f, 0.3f, 0f, 0f, life, 0f); }
        catch (Throwable t) { /* cosmetic only */ }
    }

    private static Color cyan() { return new Color(80, 230, 210); }

    @Override
    public void renderInUICoords(ViewportAPI viewport) { /* floating text covers v0 HUD */ }
}
