package theinvisiblehand

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.util.Misc
import kotlin.math.max
import kotlin.math.min

object TradePlanCalculator {
    private val logger = Global.getLogger(TradePlanCalculator::class.java)

    private data class PartialPlan(
        val legs: List<TradeRouteCandidate>,
        val sim: TradePlanSimState,
        val totalDays: Float,
        val totalDiscountedRiskNet: Float,
        val nextDiscount: Float
    ) {
        val scorePerDay: Float
            get() = if (totalDays > 0f) totalDiscountedRiskNet / totalDays else 0f
    }

    internal fun planTradePlan(fleet: CampaignFleetAPI): TradePlan? {
        val depth = TIHConfig.multiHopLookaheadDepth.coerceIn(1, 3)
        val beamWidth = TIHConfig.multiHopBeamWidth.coerceIn(1, 20)
        val candidatesPerHop = TIHConfig.multiHopCandidatesPerHop.coerceIn(1, 20)
        val futureDiscount = TIHConfig.multiHopFutureDiscountPerHop.coerceIn(0f, 1f)

        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()
        val startState = TradePlanSimState(playerCredits)

        val coordinator = TIHTradeCoordinator.get()
        val coordinationEnabled = TIHConfig.multiFleetCoordinationEnabled
        if (coordinationEnabled) {
            coordinator.seedPlanningSimState(startState, excludeFleetId = fleet.id)
        }
        val firstHopBuyBudget = if (coordinationEnabled) {
            coordinator.getAvailableBuyBudgetCredits(excludeFleetIdReservation = fleet.id)
        } else {
            null
        }

        var best: PartialPlan? = null
        var beam: List<PartialPlan> = listOf(
            PartialPlan(
                legs = emptyList(),
                sim = startState,
                totalDays = 0f,
                totalDiscountedRiskNet = 0f,
                nextDiscount = 1f
            )
        )

        for (step in 0 until depth) {
            val nextBeam = mutableListOf<PartialPlan>()

            for (partial in beam) {
                val candidates = if (partial.legs.isEmpty()) {
                    // First hop: allow traveling empty to the best source.
                    TradeRouteCalculator.findTopRouteCandidates(
                        fleet = fleet,
                        limit = max(beamWidth * candidatesPerHop, candidatesPerHop),
                        availableCredits = partial.sim.credits,
                        simState = partial.sim,
                        maxBuyBudget = firstHopBuyBudget
                    )
                } else {
                    // Next hops: buy at the current market (previous destination).
                    val fromMarket = partial.legs.last().route.dest
                    TradeRouteCalculator.findTopRouteCandidatesFrom(
                        fleet = fleet,
                        fromMarket = fromMarket,
                        limit = candidatesPerHop,
                        availableCredits = partial.sim.credits,
                        simState = partial.sim
                    )
                }

                val limitedCandidates = if (partial.legs.isEmpty()) {
                    candidates.take(candidatesPerHop)
                } else {
                    candidates
                }

                for (cand in limitedCandidates) {
                    if (partial.legs.isNotEmpty()) {
                        val prev = partial.legs.last().route
                        // Avoid immediate backtracking loops.
                        if (cand.route.dest.id == prev.source.id) {
                            continue
                        }
                    }

                    val sim = partial.sim.copyDeep()
                    sim.credits += cand.tradeMargin
                    sim.addExcessUsed(cand.route.source.id, cand.route.commodityId, cand.route.quantity)
                    sim.addDeficitUsed(cand.route.dest.id, cand.route.commodityId, cand.route.quantity)

                    val legDays = max(cand.route.estimatedDays, 1f)
                    val newTotalDays = partial.totalDays + legDays
                    val legRiskNet = cand.riskAdjustedScorePerDay * legDays
                    val newTotalDiscountedRiskNet = partial.totalDiscountedRiskNet + (legRiskNet * partial.nextDiscount)
                    val newNextDiscount = partial.nextDiscount * futureDiscount

                    val newPartial = PartialPlan(
                        legs = partial.legs + cand,
                        sim = sim,
                        totalDays = newTotalDays,
                        totalDiscountedRiskNet = newTotalDiscountedRiskNet,
                        nextDiscount = newNextDiscount
                    )
                    nextBeam.add(newPartial)

                    if (best == null || newPartial.scorePerDay > best!!.scorePerDay) {
                        best = newPartial
                    }
                }
            }

            if (nextBeam.isEmpty()) {
                break
            }
            beam = nextBeam
                .sortedByDescending { it.scorePerDay }
                .take(beamWidth)
        }

        val bestPlan = best ?: return null
        if (bestPlan.legs.isEmpty()) {
            return null
        }

        val legs = bestPlan.legs.map { it.route }
        val debug = buildString {
            append("depth=")
            append(min(depth, legs.size))
            append(", score=")
            append(Misc.getRoundedValueMaxOneAfterDecimal(bestPlan.scorePerDay))
            append(", legs=")
            append(legs.size)
        }
        logger.info("Planned trade route for ${fleet.name}: $debug")
        for ((index, route) in legs.withIndex()) {
            logger.info(
                "  #${index + 1}: ${route.source.name} -> ${route.dest.name}, ${route.commodityId} x${route.quantity}, " +
                    "profit/day~${Misc.getRoundedValueMaxOneAfterDecimal(bestPlan.legs[index].adjustedProfitPerDay)}"
            )
        }

        return TradePlan(
            legs = legs,
            scorePerDay = bestPlan.scorePerDay,
            debug = debug
        )
    }
}
