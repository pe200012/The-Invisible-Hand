package theinvisiblehand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class TIHTradeCoordinatorHotfixTest {
    @Test
    fun `resetPlanningRuntimeState replaces planning queue and clears permit timestamp`() {
        val ctor = TIHTradeCoordinator::class.java.getDeclaredConstructor()
        ctor.isAccessible = true
        val coordinator = ctor.newInstance()

        val queueField = TIHTradeCoordinator::class.java.getDeclaredField("budgetQueue")
        queueField.isAccessible = true
        val originalQueue = queueField.get(coordinator)

        val permitField = TIHTradeCoordinator::class.java.getDeclaredField("nextPlanningPermitTimestamp")
        permitField.isAccessible = true
        permitField.setLong(coordinator, 12345L)

        coordinator.resetPlanningRuntimeState()

        val resetQueue = queueField.get(coordinator)
        assertNotSame(originalQueue, resetQueue)
        assertEquals(-1L, permitField.getLong(coordinator))
    }
}
