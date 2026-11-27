package data.scripts;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

import java.util.EnumSet;
import java.util.List;

/**
 * Reflex layer — drives one tagged ShipAPI every frame from a tactical Intent.
 * Uses only verified API surface: issueCommand(ShipCommand, boolean),
 * setControlsLocked, WeaponGroupAPI.toggleOn/Off, FluxTracker, plain math.
 * No LazyLib dependency. 60 Hz, zero network latency.
 */
public class FlightLayer {

    /** Who is flying the tagged hull right now. */
    public enum Authority {
        AUTOPILOT,  // vanilla AI, we watch only
        WING,       // we fly it (controlsLocked + issueCommand)
        FLEET;      // reserved: fleet-wide handover (campaign setAIMode / battle all-hull AI)

        public Authority next() {
            return this == AUTOPILOT ? WING : this == WING ? FLEET : AUTOPILOT;
        }
    }

    private final ShipAPI ship;
    private EnumSet<ShipCommand> held = EnumSet.noneOf(ShipCommand.class);
    private boolean autofireApplied = false;

    public FlightLayer(ShipAPI ship) {
        this.ship = ship;
    }

    public boolean valid() {
        return ship != null && ship.isAlive();
    }

    public void takeControl() {
        if (valid() && !ship.controlsLocked()) ship.setControlsLocked(true);
    }

    /** Vanilla AI resumes flying this hull; used for FLEET and AUTOPILOT modes. */
    public void releaseControl() {
        if (valid() && ship.controlsLocked()) ship.setControlsLocked(false);
        releaseCommands();
    }

    private void releaseCommands() {
        // giveCommand held-frames expire by themselves; nothing to undo.
        held.clear();
    }

    // --------------------------------------------------------------- per frame

    /**
     * @param intent   current tactical intent (never null; engageDefault fallback)
     * @param target   resolved target ship or null
     * @param incoming engine missile list (we filter for hostile ones)
     */
    public void advance(Intent intent, ShipAPI target, List<MissileAPI> incoming) {
        if (!valid()) { releaseCommands(); return; }
        takeControl();

        EnumSet<ShipCommand> want = EnumSet.noneOf(ShipCommand.class);

        boolean venting = ship.getFluxTracker().isOverloadedOrVenting();
        float flux = ship.getFluxLevel();

        // 1) flux safety — vent early; movement dies mid-vent so do it at threshold
        boolean missileNear = closestMissileDistSq(incoming) < 350f * 350f;
        boolean wantVent = intent.ventAllowed && !venting
                && (flux >= LinkedWingConfig.emergencyFluxPct
                    || (missileNear && flux >= 0.6f));
        if (wantVent) want.add(ShipCommand.VENT_FLUX);

        // 2) hull safety — hard override regardless of mode
        String mode = ship.getHullLevel() < LinkedWingConfig.emergencyHullPct
                ? Intent.WITHDRAW : intent.mode;

        // 3) navigation by mode
        if (Intent.WITHDRAW.equals(mode)) {
            ShipAPI threat = target != null ? target : nearestHostile();
            if (threat != null) {
                face(want, threat.getLocation().x - ship.getLocation().x,
                        threat.getLocation().y - ship.getLocation().y, true);
            }
            want.add(ShipCommand.ACCELERATE);
        } else if (target == null) {
            // HOLD / engage without target: drift toward formation anchor
            if (Math.hypot(ship.getVelocity().x, ship.getVelocity().y) > 60f)
                want.add(ShipCommand.DECELERATE);
        } else {
            navigateAt(want, intent, target, venting);
        }

        issue(want);

        // 4) autofire per intent (idempotent, only on change)
        if (intent.autofire != autofireApplied) {
            for (WeaponGroupAPI g : ship.getWeaponGroupsCopy()) {
                if (intent.autofire && !g.isAutofiring()) g.toggleOn();
                else if (!intent.autofire && g.isAutofiring()) g.toggleOff();
            }
            autofireApplied = intent.autofire;
        }

        // 5) system: fire when ready and mode wants it
        if (intent.systemAllowed && !venting
                && ship.getSystem() != null
                && ship.getSystem().getCooldownRemaining() <= 0f
                && !ship.getSystem().isActive()) {
            ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
            
        }
    }

    /** Keep preferredRangePx band, present broadside (turn-rate agnostic: just keep facing on target). */
    private void navigateAt(EnumSet<ShipCommand> want, Intent intent, ShipAPI target, boolean venting) {
        float dx = target.getLocation().x - ship.getLocation().x;
        float dy = target.getLocation().y - ship.getLocation().y;
        float dist = (float) Math.hypot(dx, dy);

        face(want, dx, dy, false);

        if (venting) return; // can't move meaningfully while venting

        float r = LinkedWingConfig.preferredRangePx;
        if (dist > r + 80f) {
            want.add(ShipCommand.ACCELERATE);
        } else if (dist < r - 80f) {
            want.add(ShipCommand.DECELERATE);
            want.add(ShipCommand.STRAFE_LEFT); // kite backwards-ish to re-open range
        } else {
            // in band: hold station with light strafing so we're a moving target
            float v = (float) Math.hypot(ship.getVelocity().x, ship.getVelocity().y);
            if (v < 20f) want.add(ShipCommand.STRAFE_RIGHT);
        }
    }

    // -------------------------------------------------------------- geometry

    private void face(EnumSet<ShipCommand> want, float dx, float dy, boolean away) {
        float desired = (float) Math.toDegrees(Math.atan2(dy, dx));
        if (away) desired += 180f;
        float err = norm180(desired - ship.getFacing());
        if (err > 3f) want.add(ShipCommand.TURN_RIGHT);
        else if (err < -3f) want.add(ShipCommand.TURN_LEFT);
    }

    private static float norm180(float a) {
        while (a > 180f) a -= 360f;
        while (a < -180f) a += 360f;
        return a;
    }

    private float closestMissileDistSq(List<MissileAPI> incoming) {
        float best = Float.MAX_VALUE;
        if (incoming == null) return best;
        for (MissileAPI m : incoming) {
            if (m == null || m.isFizzling() || m.isFading()) continue;
            if (m.getOwner() == ship.getOwner()) continue;
            float dx = m.getLocation().x - ship.getLocation().x;
            float dy = m.getLocation().y - ship.getLocation().y;
            float d = dx * dx + dy * dy;
            if (d < best) best = d;
        }
        return best;
    }

    private ShipAPI nearestHostile() {
        return LinkedWingPlugin.nearestEnemyOf(ship);
    }

    private void issue(EnumSet<ShipCommand> want) {
        // held-frame commands expire on their own
        for (ShipCommand c : want) ship.giveCommand(c, null, 0);
        held = want;
    }
}
