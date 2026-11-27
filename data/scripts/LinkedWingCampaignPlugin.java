package data.scripts;

import com.fs.starfarer.api.BaseModPlugin;

/**
 * LinkedWing mod plugin.
 *
 * v0/v1: the whole experience lives in the combat plugin
 * (com.fs.starfarer.api.EveryFrameCombatPlugin registered via
 * settings.json + a hullmod that tags the chosen hull), so this class only
 * carries config loading at startup and is the anchor for v2 quartermaster
 * campaign scripts.
 */
public class LinkedWingCampaignPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() throws Exception {
        LinkedWingConfig.loadFromSystemProperty();
    }

    @Override
    public void onNewGame() {
        // v2: register the quartermaster EveryFrameScript here.
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // v2: re-attach quartermaster script (scripts are saved with the
        // sector, but config/state must be refreshed).
    }
}
