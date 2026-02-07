package com.fs.starfarer.api.impl.campaign.rulecmd

import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.api.Global
import exerelin.campaign.intel.specialforces.SpecialForcesIntel
import theinvisiblehand.AutoTradeIntel
import theinvisiblehand.AutoTradeTask
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
        // Assign an AutoTradeTask (subclass of SpecialForcesTask with PATROL type)
        // so the Nexerelin intel panel shows "Auto-trading" instead of "patrolling"
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet)
        if (sf != null) {
            val task = AutoTradeTask(100f)
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

        // Create and add intel
        val intel = AutoTradeIntel.getOrCreate(fleet)
        Global.getSector().intelManager.addIntel(intel, true)

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

        // Remove intel
        AutoTradeIntel.remove(fleet)

        dialog.textPanel.addPara("Auto-trade disabled. The fleet has returned to normal operations.")

        return true
    }
}
