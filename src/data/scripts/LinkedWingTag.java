package data.scripts;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Marker hullmod — the Linked Wing badge.
 *
 * The effect class name equals the hullmod id used in variants ("linkedWingTag").
 * Carrying this mod is what makes LinkedWingCombatPlugin claim a ship:
 *   - the plugin adds the String tag LinkedWingConfig.TAG so other scripts
 *     can find the ship with HullAPI/ship.hasTag() without scanning mods
 *   - v1 (officer phase) will additionally register this hullmod's stat
 *     effects; for v0 the mod itself changes nothing.
 *
 * NOTE: hullmods serialize with the variant, so this survives save/reload
 * for free — the plugin re-attaches by detecting the tag each battle.
 */
public class LinkedWingTag extends BaseHullMod {

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize,
                                               MutableShipStatsAPI stats,
                                               String id) {
        // v0: no stat changes — identity only.
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship != null) {
            ship.addTag(LinkedWingConfig.TAG);
        }
    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "Marks this hull as the Linked Wing vessel: an external";
        if (index == 1) return "AI pilot (reflexes on the game side, tactics from the";
        if (index == 2) return "basalt LLM server) flies this ship alongside the fleet.";
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return true; // refit onto anything
    }
}
