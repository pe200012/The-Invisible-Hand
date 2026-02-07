package theinvisiblehand

import com.fs.starfarer.api.BaseModPlugin
import com.fs.starfarer.api.Global

class InvisibleHandModPlugin : BaseModPlugin() {

    companion object {
        const val ICON_PATH = "graphics/icons/trade.png"
    }

    override fun onApplicationLoad() {
        Global.getSettings().loadTexture(ICON_PATH)
    }

    override fun onGameLoad(newGame: Boolean) {
        // Re-attach TradeFleetScript to any fleets that were trading when saved
        for (location in Global.getSector().allLocations) {
            for (fleet in location.fleets) {
                if (fleet.memoryWithoutUpdate.getBoolean(TradeFleetScript.MEM_KEY_TRADING)) {
                    // Check that the script isn't already attached (shouldn't be after load, but just in case)
                    val alreadyAttached = fleet.scripts.any { it is TradeFleetScript }
                    if (!alreadyAttached) {
                        fleet.addScript(TradeFleetScript(fleet))
                    }
                }
            }
        }
    }
}
