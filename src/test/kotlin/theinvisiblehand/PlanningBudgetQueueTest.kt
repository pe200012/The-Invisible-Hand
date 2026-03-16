package theinvisiblehand

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlanningBudgetQueueTest {
    @Test
    fun `successful reservation does not keep a lone fleet at the head of the queue`() {
        val queue = PlanningBudgetQueue()

        assertTrue(queue.requestTurn("fleet-A"))
        queue.onReservationSucceeded("fleet-A")

        assertTrue(queue.requestTurn("fleet-B"))
    }

    @Test
    fun `waiting fleet becomes head immediately after current head succeeds`() {
        val queue = PlanningBudgetQueue()

        assertTrue(queue.requestTurn("fleet-A"))
        assertFalse(queue.requestTurn("fleet-B"))

        queue.onReservationSucceeded("fleet-A")

        assertTrue(queue.requestTurn("fleet-B"))
    }

    @Test
    fun `insufficient budget rotates the head to the next waiting fleet`() {
        val queue = PlanningBudgetQueue()

        assertTrue(queue.requestTurn("fleet-A"))
        assertFalse(queue.requestTurn("fleet-B"))

        queue.onInsufficientBudget("fleet-A")

        assertTrue(queue.requestTurn("fleet-B"))
    }

    @Test
    fun `removing the current head immediately frees the next waiting fleet`() {
        val queue = PlanningBudgetQueue()

        assertTrue(queue.requestTurn("fleet-A"))
        assertFalse(queue.requestTurn("fleet-B"))

        queue.remove("fleet-A")

        assertTrue(queue.requestTurn("fleet-B"))
    }
}
