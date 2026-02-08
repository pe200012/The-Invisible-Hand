package theinvisiblehand

import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.SpecialForcesTask
import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.TaskType

/**
 * Subclass of SpecialForcesTask that overrides getText() to show
 * auto-trade status in the Nexerelin intel panel instead of default task text.
 */
class AutoTradeTask(priority: Float) : SpecialForcesTask(TaskType.WAIT_ORBIT, priority) {

    @Volatile
    var tradeActionText: String = "Auto-trading"

    override fun getText(): String {
        return tradeActionText
    }
}
