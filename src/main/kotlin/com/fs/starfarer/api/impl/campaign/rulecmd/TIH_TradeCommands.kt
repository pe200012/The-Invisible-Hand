package com.fs.starfarer.api.impl.campaign.rulecmd

import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.intel.specialforces.SpecialForcesIntel
import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.SpecialForcesTask
import theinvisiblehand.TradeFleetScript

class TIH_TradeCommands : BaseCommandPlugin() {

    override fun execute(
        ruleId: String,
        dialog: InteractionDialogAPI,
        params: List<Misc.Token>,
        memoryMap: Map<String, MemoryAPI>
    ): Boolean {
        val token = dialog.interactionTarget
        val fleet = token as? CampaignFleetAPI ?: return false

        val arg = params[0].getString(memoryMap)
        return when (arg) {
            "startTrade" -> startTrade(dialog, fleet)
            "stopTrade" -> stopTrade(dialog, fleet)
            else -> false
        }
    }

    private fun startTrade(dialog: InteractionDialogAPI, fleet: CampaignFleetAPI): Boolean {
        // Set up a PATROL task with our custom flag so Nexerelin shows a meaningful status
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet)
        if (sf != null) {
            val task = SpecialForcesTask("patrol", 100f)
            task.playerIssued = true
            task.system = fleet.starSystem
            task.params["tih_auto_trade"] = true
            sf.routeAI.assignTask(task)
        }

        // Mark fleet as trading
        fleet.memoryWithoutUpdate.set(TradeFleetScript.MEM_KEY_TRADING, true)

        // Attach trade script
        val script = TradeFleetScript(fleet)
        fleet.addScript(script)

        dialog.textPanel.addPara("Auto-trade enabled. The fleet will now autonomously trade commodities between markets for profit.")

        return true
    }

    private fun stopTrade(dialog: InteractionDialogAPI, fleet: CampaignFleetAPI): Boolean {
        // Unset trading flag (this makes the script's isDone() return true)
        fleet.memoryWithoutUpdate.unset(TradeFleetScript.MEM_KEY_TRADING)

        // Find and clean up the trade script
        val scripts = fleet.scripts
        for (script in scripts) {
            if (script is TradeFleetScript) {
                script.cleanup()
                fleet.removeScript(script)
                break
            }
        }

        // Clear fleet assignments
        fleet.clearAssignments()

        dialog.textPanel.addPara("Auto-trade disabled. The fleet has returned to normal operations.")

        return true
    }
}
