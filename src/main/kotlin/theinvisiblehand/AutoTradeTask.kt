package theinvisiblehand

import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.SpecialForcesTask
import exerelin.campaign.intel.specialforces.SpecialForcesRouteAI.TaskType

/**
 * Subclass of SpecialForcesTask that overrides getText() to show
 * auto-trade status in the Nexerelin intel panel instead of "patrolling".
 */
class AutoTradeTask(priority: Float) : SpecialForcesTask(TaskType.PATROL, priority) {

    @Volatile
    var tradeActionText: String = "Auto-trading"

    override fun getText(): String {
        return tradeActionText
    }
}
