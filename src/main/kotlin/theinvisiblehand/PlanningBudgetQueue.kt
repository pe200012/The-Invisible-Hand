package theinvisiblehand

import java.io.Serializable
import java.util.ArrayDeque

internal class PlanningBudgetQueue : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }

    private val queue = ArrayDeque<String>()
    private val members = HashSet<String>()

    fun requestTurn(fleetId: String): Boolean {
        if (members.add(fleetId)) {
            queue.addLast(fleetId)
        }
        return queue.peekFirst() == fleetId
    }

    fun onReservationSucceeded(fleetId: String) {
        remove(fleetId)
    }

    fun onInsufficientBudget(fleetId: String) {
        if (queue.peekFirst() != fleetId) {
            return
        }

        val head = queue.pollFirst() ?: return
        queue.addLast(head)
    }

    fun remove(fleetId: String) {
        if (!members.remove(fleetId)) {
            return
        }
        queue.remove(fleetId)
    }
}
