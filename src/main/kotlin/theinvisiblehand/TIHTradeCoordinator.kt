package theinvisiblehand

import com.fs.starfarer.api.Global
import java.io.Serializable
import kotlin.math.max
import kotlin.math.min

class TIHTradeCoordinator private constructor() : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        private const val PERSIST_KEY = "tih_trade_coordinator"

        fun get(): TIHTradeCoordinator {
            val sector = Global.getSector()
            val data = sector.persistentData
            val existing = data[PERSIST_KEY] as? TIHTradeCoordinator
            if (existing != null) {
                return existing
            }

            val created = TIHTradeCoordinator()
            data[PERSIST_KEY] = created
            return created
        }
    }

    private enum class ReservationState : Serializable {
        PLANNED_TO_BUY,
        HOLDING_FOR_SELL
    }

    private data class Reservation(
        val fleetId: String,
        var sourceMarketId: String,
        var destMarketId: String,
        var commodityId: String,
        var quantity: Int,
        var reservedBuyCost: Float,
        var state: ReservationState,
        var lastUpdatedTimestamp: Long
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private val reservationsByFleet: MutableMap<String, Reservation> = HashMap()
    private val lockedBuyCostByFleet: MutableMap<String, Float> = HashMap()
    private val lastReplanTimestampByFleet: MutableMap<String, Long> = HashMap()

    private var budgetQueue = PlanningBudgetQueue()
    private var nextPlanningPermitTimestamp: Long = -1L

    internal fun resetPlanningRuntimeState() {
        budgetQueue = PlanningBudgetQueue()
        nextPlanningPermitTimestamp = -1L
    }

    private fun nowTimestamp(): Long = Global.getSector().clock.timestamp

    private fun pruneExpiredInternal() {
        if (!TIHConfig.multiFleetCoordinationEnabled) {
            return
        }

        val expiryDays = TIHConfig.multiFleetReservationExpiryDays
        if (expiryDays <= 0f) {
            return
        }

        val clock = Global.getSector().clock
        val iter = reservationsByFleet.values.iterator()
        while (iter.hasNext()) {
            val res = iter.next()
            if (clock.getElapsedDaysSince(res.lastUpdatedTimestamp) > expiryDays) {
                iter.remove()
            }
        }
    }

    internal fun seedPlanningSimState(simState: TradePlanSimState, excludeFleetId: String? = null) {
        if (!TIHConfig.multiFleetCoordinationEnabled) {
            return
        }
        pruneExpiredInternal()

        for ((fleetId, res) in reservationsByFleet) {
            if (excludeFleetId != null && fleetId == excludeFleetId) {
                continue
            }
            simState.addExcessUsed(res.sourceMarketId, res.commodityId, res.quantity)
            simState.addDeficitUsed(res.destMarketId, res.commodityId, res.quantity)
        }
    }

    fun getAvailableBuyBudgetCredits(excludeFleetIdReservation: String? = null): Float {
        val credits = Global.getSector().playerFleet.cargo.credits.get()

        val p = (TIHConfig.maxCreditsUsagePercent / 100f).coerceIn(0f, 1f)
        if (p <= 0f) {
            return 0f
        }

        if (!TIHConfig.multiFleetCoordinationEnabled) {
            return credits * p
        }

        pruneExpiredInternal()

        var lockedTotal = 0f
        for (cost in lockedBuyCostByFleet.values) {
            lockedTotal += cost
        }

        var reservedTotal = 0f
        for ((fleetId, res) in reservationsByFleet) {
            if (excludeFleetIdReservation != null && fleetId == excludeFleetIdReservation) {
                continue
            }
            if (res.state == ReservationState.PLANNED_TO_BUY) {
                reservedTotal += res.reservedBuyCost
            }
        }

        val wealth = credits + lockedTotal
        val allowedLocked = wealth * p
        val usedLockedCapacity = lockedTotal + reservedTotal
        val available = allowedLocked - usedLockedCapacity
        return min(max(available, 0f), credits)
    }

    fun tryReservePlannedLeg(fleetId: String, route: TradeRoute): Boolean {
        if (!TIHConfig.multiFleetCoordinationEnabled) {
            return true
        }

        pruneExpiredInternal()
        reservationsByFleet.remove(fleetId)

        if (route.quantity <= 0) {
            return false
        }
        val expectedCost = route.expectedBuyCost
        if (expectedCost <= 0f) {
            return false
        }

        if (!budgetQueue.requestTurn(fleetId)) {
            return false
        }

        val availableBudget = getAvailableBuyBudgetCredits(excludeFleetIdReservation = null)
        if (expectedCost > availableBudget) {
            // Yield turn if this fleet's chosen trade doesn't fit current budget.
            budgetQueue.onInsufficientBudget(fleetId)
            return false
        }

        val now = nowTimestamp()
        reservationsByFleet[fleetId] = Reservation(
            fleetId = fleetId,
            sourceMarketId = route.source.id,
            destMarketId = route.dest.id,
            commodityId = route.commodityId,
            quantity = route.quantity,
            reservedBuyCost = expectedCost,
            state = ReservationState.PLANNED_TO_BUY,
            lastUpdatedTimestamp = now
        )

        // A fleet that already secured a reservation should leave the planning queue.
        budgetQueue.onReservationSucceeded(fleetId)
        return true
    }

    fun touchReservation(fleetId: String) {
        val res = reservationsByFleet[fleetId] ?: return
        res.lastUpdatedTimestamp = nowTimestamp()
    }

    fun restoreFleetStateFromMemory(fleetId: String, route: TradeRoute?, buyCost: Float) {
        // Active fleets should never stay in the planning queue after load.
        budgetQueue.remove(fleetId)

        if (route == null) {
            lockedBuyCostByFleet.remove(fleetId)
            reservationsByFleet.remove(fleetId)
            return
        }

        val now = nowTimestamp()
        if (buyCost > 0f) {
            lockedBuyCostByFleet[fleetId] = buyCost
            reservationsByFleet[fleetId] = Reservation(
                fleetId = fleetId,
                sourceMarketId = route.source.id,
                destMarketId = route.dest.id,
                commodityId = route.commodityId,
                quantity = route.quantity,
                reservedBuyCost = 0f,
                state = ReservationState.HOLDING_FOR_SELL,
                lastUpdatedTimestamp = now
            )
            return
        }

        val reserved = max(route.expectedBuyCost, 0f)
        reservationsByFleet[fleetId] = Reservation(
            fleetId = fleetId,
            sourceMarketId = route.source.id,
            destMarketId = route.dest.id,
            commodityId = route.commodityId,
            quantity = route.quantity,
            reservedBuyCost = reserved,
            state = ReservationState.PLANNED_TO_BUY,
            lastUpdatedTimestamp = now
        )
    }

    fun updateReservationDest(fleetId: String, newDestMarketId: String) {
        val res = reservationsByFleet[fleetId] ?: return
        res.destMarketId = newDestMarketId
        res.lastUpdatedTimestamp = nowTimestamp()
    }

    fun onBuyExecuted(fleetId: String, actualBuyCost: Float, route: TradeRoute): Boolean {
        if (actualBuyCost <= 0f) {
            return false
        }

        if (!TIHConfig.multiFleetCoordinationEnabled) {
            lockedBuyCostByFleet[fleetId] = actualBuyCost
            return true
        }

        pruneExpiredInternal()
        val res = reservationsByFleet[fleetId]

        // Temporarily exclude this fleet's planned reservation from capacity used
        val plannedReserved = if (res != null && res.state == ReservationState.PLANNED_TO_BUY) res.reservedBuyCost else 0f

        // Compute max allowed buy after removing our planned reservation
        val credits = Global.getSector().playerFleet.cargo.credits.get()
        val p = (TIHConfig.maxCreditsUsagePercent / 100f).coerceIn(0f, 1f)
        if (p <= 0f) {
            return false
        }

        var lockedTotal = 0f
        for (cost in lockedBuyCostByFleet.values) {
            lockedTotal += cost
        }

        var reservedTotalExclThis = 0f
        for ((id, r) in reservationsByFleet) {
            if (id == fleetId) continue
            if (r.state == ReservationState.PLANNED_TO_BUY) {
                reservedTotalExclThis += r.reservedBuyCost
            }
        }

        val wealth = credits + lockedTotal
        val allowedLocked = wealth * p
        val maxBuyAllowed = allowedLocked - (lockedTotal + reservedTotalExclThis)

        if (actualBuyCost > maxBuyAllowed) {
            // Restore reservation timestamp so it doesn't expire due to failure
            if (res != null) {
                res.lastUpdatedTimestamp = nowTimestamp()
                res.reservedBuyCost = plannedReserved
            }
            return false
        }

        lockedBuyCostByFleet[fleetId] = actualBuyCost

        val now = nowTimestamp()
        val newRes = if (res != null) {
            res
        } else {
            Reservation(
                fleetId = fleetId,
                sourceMarketId = route.source.id,
                destMarketId = route.dest.id,
                commodityId = route.commodityId,
                quantity = route.quantity,
                reservedBuyCost = 0f,
                state = ReservationState.HOLDING_FOR_SELL,
                lastUpdatedTimestamp = now
            )
        }

        newRes.sourceMarketId = route.source.id
        newRes.destMarketId = route.dest.id
        newRes.commodityId = route.commodityId
        newRes.quantity = route.quantity
        newRes.reservedBuyCost = 0f
        newRes.state = ReservationState.HOLDING_FOR_SELL
        newRes.lastUpdatedTimestamp = now
        reservationsByFleet[fleetId] = newRes

        return true
    }

    fun onSellOrOffloadComplete(fleetId: String) {
        lockedBuyCostByFleet.remove(fleetId)
        reservationsByFleet.remove(fleetId)
        budgetQueue.remove(fleetId)
    }

    fun clearFleetState(fleetId: String) {
        lockedBuyCostByFleet.remove(fleetId)
        reservationsByFleet.remove(fleetId)
        lastReplanTimestampByFleet.remove(fleetId)
        budgetQueue.remove(fleetId)
    }

    fun shouldReplanImmediately(fleetId: String): Boolean {
        if (!TIHConfig.multiFleetCoordinationEnabled) {
            return true
        }
        val clock = Global.getSector().clock
        val now = clock.timestamp

        val cooldownDays = TIHConfig.multiFleetReplanCooldownDays
        if (cooldownDays > 0f) {
            val last = lastReplanTimestampByFleet[fleetId]
            if (last != null) {
                val elapsed = clock.getElapsedDaysSince(last)
                if (elapsed < cooldownDays) {
                    return false
                }
            }
        }

        val spacingDays = TIHConfig.multiFleetPlanningSlotSpacingDays
        if (spacingDays > 0f) {
            if (nextPlanningPermitTimestamp < 0L) {
                nextPlanningPermitTimestamp = now
            }
            if (now < nextPlanningPermitTimestamp) {
                return false
            }
            nextPlanningPermitTimestamp = now + clock.convertToSeconds(spacingDays).toLong()
        }

        lastReplanTimestampByFleet[fleetId] = now
        return true
    }
}
