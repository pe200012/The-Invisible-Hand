package theinvisiblehand

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.util.Misc
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min

object TradeRouteCalculator {

    private var cachedMarketPrices: MutableMap<String, MarketPriceData>? = null
    private var cacheTimestamp: Long = -1L

    private data class MarketPriceData(
        val market: MarketAPI,
        val supplyPrices: Map<String, Float>,
        val demandPrices: Map<String, Float>,
        val deficits: Map<String, Int>,
        val excesses: Map<String, Int>,
        val hasTradeDisruption: Boolean
    )

    private fun calculateNexStyleQuantityCap(
        sourceData: MarketPriceData,
        destData: MarketPriceData,
        commodityId: String,
        quantityCapByCargoCreditsAndSize: Int,
        simState: TradePlanSimState?
    ): Int {
        val sourceExcessBase = (sourceData.excesses[commodityId] ?: 0).coerceAtLeast(0)
        val destDeficitBase = (destData.deficits[commodityId] ?: 0).coerceAtLeast(0)
        val sourceExcess = (sourceExcessBase - (simState?.getExcessUsed(sourceData.market.id, commodityId) ?: 0))
            .coerceAtLeast(0)
        val destDeficit = (destDeficitBase - (simState?.getDeficitUsed(destData.market.id, commodityId) ?: 0))
            .coerceAtLeast(0)
        val marketFlowCap = min(sourceExcess, destDeficit)
        return min(quantityCapByCargoCreditsAndSize, marketFlowCap)
    }

    private fun computeRiskMultiplier(totalDays: Float, hasTradeDisruption: Boolean): Float {
        val penaltyPerDay = TIHConfig.multiHopUncertaintyPenaltyPerDay
        if (penaltyPerDay <= 0f) {
            return 1f
        }
        val disruptionMultiplier = if (hasTradeDisruption) 1.5f else 1f
        return 1f / (1f + (totalDays * penaltyPerDay * disruptionMultiplier))
    }

    fun findBestRoute(fleet: CampaignFleetAPI): TradeRoute? {
        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()
        val usableCredits = playerCredits * (TIHConfig.maxCreditsUsagePercent / 100f)

        // Get blacklists from fleet memory
        val commodityBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        // Filter to accessible markets
        val markets = allMarkets.filter { market ->
            !market.isHidden
                    && !fleetFaction.isHostileTo(market.faction)
                    && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                    && market.id !in marketBlacklist
        }

        if (markets.size < 2) {
            Global.getLogger(this::class.java).info("findBestRoute: Only ${markets.size} accessible markets found")
            return null
        }

        val commodityIds = economy.allCommodityIds.filter { id ->
            val spec = economy.getCommoditySpec(id)
            !spec.isPersonnel && !spec.isMeta && !spec.isNonEcon
                    && !spec.hasTag("nontradeable")
                    && id !in commodityBlacklist
        }

        val cargoSpace = fleet.cargo.spaceLeft

        // Compute travel cost rates
        val supplyCostPerDay = fleet.logistics.totalSuppliesPerDay
        val supplyBasePrice = economy.getCommoditySpec(Commodities.SUPPLIES)?.basePrice ?: 30f
        val fuelCostPerLY = fleet.logistics.fuelCostPerLightYear
        val fuelBasePrice = economy.getCommoditySpec(Commodities.FUEL)?.basePrice ?: 25f

        // Refresh cache if stale
        val clock = Global.getSector().clock
        val currentTime = clock.timestamp
        if (cachedMarketPrices == null || cacheTimestamp < 0L || clock.getElapsedDaysSince(cacheTimestamp) > TIHConfig.cacheRefreshDays) {
            refreshPriceCache(markets, commodityIds)
            cacheTimestamp = currentTime
        }

        val cache = cachedMarketPrices ?: return null

        // Get min profit threshold from fleet memory (or use default)
        val minProfitPerDay = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_MIN_PROFIT_PER_DAY)
            .takeIf { it > 0f } ?: TradeFleetScript.DEFAULT_MIN_PROFIT_PER_DAY

        var bestRoute: TradeRoute? = null
        var bestProfitPerDay = minProfitPerDay

        var totalChecked = 0
        var failReasons = mutableMapOf<String, Int>()

        fun logFail(reason: String) {
            failReasons[reason] = (failReasons[reason] ?: 0) + 1
        }

        for (commodityId in commodityIds) {
            val spec = economy.getCommoditySpec(commodityId)
            val unitCargo = spec.cargoSpace
            if (unitCargo <= 0f) continue

            for (source in markets) {
                val sourceData = cache[source.id] ?: continue
                val buyPricePerUnit = sourceData.supplyPrices[commodityId] ?: continue

                if (buyPricePerUnit <= 0f) continue

                for (dest in markets) {
                    totalChecked++
                    if (source.id == dest.id) {
                        logFail("same_market")
                        continue
                    }
                    val destData = cache[dest.id] ?: continue
                    val sellPricePerUnit = destData.demandPrices[commodityId] ?: continue

                    val marginPerUnit = sellPricePerUnit - buyPricePerUnit
                    if (marginPerUnit <= 0f) {
                        logFail("no_margin")
                        continue
                    }

                    // Calculate trade quantity
                    val maxByCargo = (cargoSpace / unitCargo).toInt()
                    val maxByCredits = if (buyPricePerUnit > 0f) (usableCredits / buyPricePerUnit).toInt() else 0
                    // Progressive cap based on config: quadratic + linear scaling
                    val marketSize = min(source.size, dest.size)
                    val maxReasonable = (marketSize * marketSize * TIHConfig.quantityScalingQuadratic) + (marketSize * TIHConfig.quantityScalingLinear)
                    val initialCap = min(min(maxByCargo, maxByCredits), maxReasonable)
                    val quantity = calculateNexStyleQuantityCap(sourceData, destData, commodityId, initialCap, simState = null)
                    if (quantity <= 0) {
                        if (maxByCargo == 0) logFail("no_quantity_cargo_full")
                        else if (maxByCredits == 0) logFail("no_quantity_no_credits")
                        else {
                            val sourceExcess = (sourceData.excesses[commodityId] ?: 0).coerceAtLeast(0)
                            val destDeficit = (destData.deficits[commodityId] ?: 0).coerceAtLeast(0)
                            if (sourceExcess <= 0 || destDeficit <= 0) logFail("no_quantity_no_market_flow")
                            else logFail("no_quantity_other")
                        }
                        continue
                    }

                    // Use actual price functions with player tariffs for accurate pricing
                    val buyTotal = source.getSupplyPrice(commodityId, quantity.toDouble(), true)
                    val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                    val tradeMargin = sellTotal - buyTotal

                    if (tradeMargin <= 0f) {
                        logFail("no_trade_margin")
                        continue
                    }
                    if (buyTotal > usableCredits) {
                        logFail("insufficient_credits")
                        continue
                    }

                    // Estimate travel costs - use fleet directly for cross-system calculations
                    val sourceEntity = source.primaryEntity ?: continue
                    val destEntity = dest.primaryEntity ?: continue
                    val daysToSource = RouteLocationCalculator.getTravelDays(fleet, sourceEntity)
                    val daysSourceToDest = RouteLocationCalculator.getTravelDays(sourceEntity, destEntity)
                    val totalDays = max(daysToSource + daysSourceToDest, 1f)

                    // Supply consumption cost over the trip
                    val supplyCost = supplyCostPerDay * totalDays * supplyBasePrice

                    // Fuel consumption cost over the trip
                    val distLYToSource = Misc.getDistanceLY(fleet, sourceEntity)
                    val distLYSourceToDest = Misc.getDistanceLY(sourceEntity, destEntity)
                    val totalDistLY = distLYToSource + distLYSourceToDest
                    val fuelCost = fuelCostPerLY * totalDistLY * fuelBasePrice

                    // Net profit after travel expenses
                    val netProfit = tradeMargin - supplyCost - fuelCost

                    if (netProfit <= 0f) {
                        logFail("negative_net_profit")
                        continue
                    }

                    val profitPerDay = netProfit / totalDays

                    // Economy bonus: prefer routes exploiting shortage/surplus
                    var economyMultiplier = 1.0f
                    val sourceExcess = (sourceData.excesses[commodityId] ?: 0).toFloat()
                    if (sourceExcess > 0f) economyMultiplier += TIHConfig.excessBonusMax * min(sourceExcess / 10f, 1.0f)
                    val destDeficit = (destData.deficits[commodityId] ?: 0).toFloat()
                    if (destDeficit > 0f) economyMultiplier += TIHConfig.deficitBonusMax * min(destDeficit / 10f, 1.0f)
                    if (destData.hasTradeDisruption) economyMultiplier += TIHConfig.disruptionBonus

                    val adjustedProfitPerDay = profitPerDay * economyMultiplier

                    if (adjustedProfitPerDay > bestProfitPerDay) {
                        bestProfitPerDay = adjustedProfitPerDay
                        bestRoute = TradeRoute(
                            source = source,
                            dest = dest,
                            commodityId = commodityId,
                            quantity = quantity,
                            expectedProfit = netProfit,
                            estimatedDays = totalDays,
                            expectedBuyCost = buyTotal,
                            expectedSellRevenue = sellTotal
                        )
                    } else {
                        logFail("below_threshold")
                    }
                }
            }
        }

        if (bestRoute == null) {
            val logger = Global.getLogger(this::class.java)
            logger.info("findBestRoute: No route found. Checked $totalChecked combinations.")
            logger.info("  Markets: ${markets.size}, Commodities: ${commodityIds.size}, Credits: ${Misc.getDGSCredits(playerCredits)}, Usable credits: ${Misc.getDGSCredits(usableCredits)}")
            logger.info("  Cargo space: $cargoSpace, Min profit/day: ${Misc.getDGSCredits(minProfitPerDay)}")
            logger.info("  Fail reasons: $failReasons")
        }

        return bestRoute
    }

    internal fun findTopRouteCandidates(
        fleet: CampaignFleetAPI,
        limit: Int,
        availableCredits: Float? = null,
        simState: TradePlanSimState? = null,
        logFailure: Boolean = false
    ): List<TradeRouteCandidate> {
        val normalizedLimit = limit.coerceAtLeast(1)

        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = availableCredits ?: Global.getSector().playerFleet.cargo.credits.get()
        val usableCredits = playerCredits * (TIHConfig.maxCreditsUsagePercent / 100f)

        val commodityBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        val markets = allMarkets.filter { market ->
            !market.isHidden
                && !fleetFaction.isHostileTo(market.faction)
                && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                && market.id !in marketBlacklist
        }

        if (markets.size < 2) {
            return emptyList()
        }

        val commodityIds = economy.allCommodityIds.filter { id ->
            val spec = economy.getCommoditySpec(id)
            !spec.isPersonnel && !spec.isMeta && !spec.isNonEcon
                && !spec.hasTag("nontradeable")
                && id !in commodityBlacklist
        }
        if (commodityIds.isEmpty()) {
            return emptyList()
        }

        val cargoSpace = fleet.cargo.spaceLeft
        val supplyCostPerDay = fleet.logistics.totalSuppliesPerDay
        val supplyBasePrice = economy.getCommoditySpec(Commodities.SUPPLIES)?.basePrice ?: 30f
        val fuelCostPerLY = fleet.logistics.fuelCostPerLightYear
        val fuelBasePrice = economy.getCommoditySpec(Commodities.FUEL)?.basePrice ?: 25f

        val clock = Global.getSector().clock
        val currentTime = clock.timestamp
        if (cachedMarketPrices == null || cacheTimestamp < 0L || clock.getElapsedDaysSince(cacheTimestamp) > TIHConfig.cacheRefreshDays) {
            refreshPriceCache(markets, commodityIds)
            cacheTimestamp = currentTime
        }
        val cache = cachedMarketPrices ?: return emptyList()

        val minProfitPerDay = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_MIN_PROFIT_PER_DAY)
            .takeIf { it > 0f } ?: TradeFleetScript.DEFAULT_MIN_PROFIT_PER_DAY

        val pq = PriorityQueue<TradeRouteCandidate>(compareBy { it.riskAdjustedScorePerDay })

        var totalChecked = 0
        val failReasons = if (logFailure) mutableMapOf<String, Int>() else null
        fun logFail(reason: String) {
            if (failReasons == null) return
            failReasons[reason] = (failReasons[reason] ?: 0) + 1
        }

        for (commodityId in commodityIds) {
            val spec = economy.getCommoditySpec(commodityId)
            val unitCargo = spec.cargoSpace
            if (unitCargo <= 0f) continue

            for (source in markets) {
                val sourceData = cache[source.id] ?: continue
                val buyPricePerUnit = sourceData.supplyPrices[commodityId] ?: continue
                if (buyPricePerUnit <= 0f) continue

                for (dest in markets) {
                    totalChecked++
                    if (source.id == dest.id) {
                        logFail("same_market")
                        continue
                    }
                    val destData = cache[dest.id] ?: continue
                    val sellPricePerUnit = destData.demandPrices[commodityId] ?: continue
                    val marginPerUnit = sellPricePerUnit - buyPricePerUnit
                    if (marginPerUnit <= 0f) {
                        logFail("no_margin")
                        continue
                    }

                    val maxByCargo = (cargoSpace / unitCargo).toInt()
                    val maxByCredits = if (buyPricePerUnit > 0f) (usableCredits / buyPricePerUnit).toInt() else 0
                    val marketSize = min(source.size, dest.size)
                    val maxReasonable = (marketSize * marketSize * TIHConfig.quantityScalingQuadratic) + (marketSize * TIHConfig.quantityScalingLinear)
                    val initialCap = min(min(maxByCargo, maxByCredits), maxReasonable)
                    val quantity = calculateNexStyleQuantityCap(sourceData, destData, commodityId, initialCap, simState)
                    if (quantity <= 0) {
                        logFail("no_quantity")
                        continue
                    }

                    val buyTotal = source.getSupplyPrice(commodityId, quantity.toDouble(), true)
                    val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                    val tradeMargin = sellTotal - buyTotal
                    if (tradeMargin <= 0f) {
                        logFail("no_trade_margin")
                        continue
                    }
                    if (buyTotal > usableCredits) {
                        logFail("insufficient_credits")
                        continue
                    }

                    val sourceEntity = source.primaryEntity ?: continue
                    val destEntity = dest.primaryEntity ?: continue
                    val daysToSource = RouteLocationCalculator.getTravelDays(fleet, sourceEntity)
                    val daysSourceToDest = RouteLocationCalculator.getTravelDays(sourceEntity, destEntity)
                    val totalDays = max(daysToSource + daysSourceToDest, 1f)

                    val supplyCost = supplyCostPerDay * totalDays * supplyBasePrice
                    val distLYToSource = Misc.getDistanceLY(fleet, sourceEntity)
                    val distLYSourceToDest = Misc.getDistanceLY(sourceEntity, destEntity)
                    val fuelCost = fuelCostPerLY * (distLYToSource + distLYSourceToDest) * fuelBasePrice

                    val netProfit = tradeMargin - supplyCost - fuelCost
                    if (netProfit <= 0f) {
                        logFail("negative_net_profit")
                        continue
                    }

                    val profitPerDay = netProfit / totalDays
                    var economyMultiplier = 1.0f
                    val sourceExcessAdj = (
                        (sourceData.excesses[commodityId] ?: 0).coerceAtLeast(0)
                            - (simState?.getExcessUsed(source.id, commodityId) ?: 0)
                        ).toFloat()
                    if (sourceExcessAdj > 0f) economyMultiplier += TIHConfig.excessBonusMax * min(sourceExcessAdj / 10f, 1.0f)
                    val destDeficitAdj = (
                        (destData.deficits[commodityId] ?: 0).coerceAtLeast(0)
                            - (simState?.getDeficitUsed(dest.id, commodityId) ?: 0)
                        ).toFloat()
                    if (destDeficitAdj > 0f) economyMultiplier += TIHConfig.deficitBonusMax * min(destDeficitAdj / 10f, 1.0f)
                    if (destData.hasTradeDisruption) economyMultiplier += TIHConfig.disruptionBonus

                    val adjustedProfitPerDay = profitPerDay * economyMultiplier
                    if (adjustedProfitPerDay <= minProfitPerDay) {
                        logFail("below_threshold")
                        continue
                    }

                    val riskMultiplier = computeRiskMultiplier(totalDays, sourceData.hasTradeDisruption || destData.hasTradeDisruption)
                    val riskAdjustedScorePerDay = adjustedProfitPerDay * riskMultiplier

                    val route = TradeRoute(
                        source = source,
                        dest = dest,
                        commodityId = commodityId,
                        quantity = quantity,
                        expectedProfit = netProfit,
                        estimatedDays = totalDays,
                        expectedBuyCost = buyTotal,
                        expectedSellRevenue = sellTotal
                    )
                    val cand = TradeRouteCandidate(route, adjustedProfitPerDay, riskAdjustedScorePerDay)
                    pq.add(cand)
                    if (pq.size > normalizedLimit) {
                        pq.poll()
                    }
                }
            }
        }

        if (pq.isEmpty()) {
            if (logFailure) {
                val logger = Global.getLogger(this::class.java)
                logger.info("findTopRouteCandidates: No route found. Checked $totalChecked combinations.")
                logger.info("  Markets: ${markets.size}, Commodities: ${commodityIds.size}, Credits: ${Misc.getDGSCredits(playerCredits)}, Usable credits: ${Misc.getDGSCredits(usableCredits)}")
                logger.info("  Fail reasons: $failReasons")
            }
            return emptyList()
        }

        return pq.toList().sortedByDescending { it.riskAdjustedScorePerDay }
    }

    internal fun findTopRouteCandidatesFrom(
        fleet: CampaignFleetAPI,
        fromMarket: MarketAPI,
        limit: Int,
        availableCredits: Float? = null,
        simState: TradePlanSimState? = null
    ): List<TradeRouteCandidate> {
        val normalizedLimit = limit.coerceAtLeast(1)

        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = availableCredits ?: Global.getSector().playerFleet.cargo.credits.get()
        val usableCredits = playerCredits * (TIHConfig.maxCreditsUsagePercent / 100f)

        val commodityBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        val markets = allMarkets.filter { market ->
            !market.isHidden
                && !fleetFaction.isHostileTo(market.faction)
                && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                && market.id !in marketBlacklist
        }
        if (markets.size < 2) {
            return emptyList()
        }

        val commodityIds = economy.allCommodityIds.filter { id ->
            val spec = economy.getCommoditySpec(id)
            !spec.isPersonnel && !spec.isMeta && !spec.isNonEcon
                && !spec.hasTag("nontradeable")
                && id !in commodityBlacklist
        }
        if (commodityIds.isEmpty()) {
            return emptyList()
        }

        val cargoSpace = fleet.cargo.spaceLeft
        val supplyCostPerDay = fleet.logistics.totalSuppliesPerDay
        val supplyBasePrice = economy.getCommoditySpec(Commodities.SUPPLIES)?.basePrice ?: 30f
        val fuelCostPerLY = fleet.logistics.fuelCostPerLightYear
        val fuelBasePrice = economy.getCommoditySpec(Commodities.FUEL)?.basePrice ?: 25f

        val clock = Global.getSector().clock
        val currentTime = clock.timestamp
        if (cachedMarketPrices == null || cacheTimestamp < 0L || clock.getElapsedDaysSince(cacheTimestamp) > TIHConfig.cacheRefreshDays) {
            refreshPriceCache(markets, commodityIds)
            cacheTimestamp = currentTime
        }
        val cache = cachedMarketPrices ?: return emptyList()
        val sourceData = cache[fromMarket.id] ?: return emptyList()
        val sourceEntity = fromMarket.primaryEntity ?: return emptyList()

        val minProfitPerDay = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_MIN_PROFIT_PER_DAY)
            .takeIf { it > 0f } ?: TradeFleetScript.DEFAULT_MIN_PROFIT_PER_DAY

        val pq = PriorityQueue<TradeRouteCandidate>(compareBy { it.riskAdjustedScorePerDay })

        for (commodityId in commodityIds) {
            val spec = economy.getCommoditySpec(commodityId)
            val unitCargo = spec.cargoSpace
            if (unitCargo <= 0f) continue

            val buyPricePerUnit = sourceData.supplyPrices[commodityId] ?: continue
            if (buyPricePerUnit <= 0f) continue

            for (dest in markets) {
                if (fromMarket.id == dest.id) continue
                val destData = cache[dest.id] ?: continue
                val sellPricePerUnit = destData.demandPrices[commodityId] ?: continue
                val marginPerUnit = sellPricePerUnit - buyPricePerUnit
                if (marginPerUnit <= 0f) continue

                val maxByCargo = (cargoSpace / unitCargo).toInt()
                val maxByCredits = if (buyPricePerUnit > 0f) (usableCredits / buyPricePerUnit).toInt() else 0
                val marketSize = min(fromMarket.size, dest.size)
                val maxReasonable = (marketSize * marketSize * TIHConfig.quantityScalingQuadratic) + (marketSize * TIHConfig.quantityScalingLinear)
                val initialCap = min(min(maxByCargo, maxByCredits), maxReasonable)
                val quantity = calculateNexStyleQuantityCap(sourceData, destData, commodityId, initialCap, simState)
                if (quantity <= 0) continue

                val buyTotal = fromMarket.getSupplyPrice(commodityId, quantity.toDouble(), true)
                val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                val tradeMargin = sellTotal - buyTotal
                if (tradeMargin <= 0f) continue
                if (buyTotal > usableCredits) continue

                val destEntity = dest.primaryEntity ?: continue
                val daysSourceToDest = RouteLocationCalculator.getTravelDays(sourceEntity, destEntity)
                val totalDays = max(daysSourceToDest, 1f)

                val supplyCost = supplyCostPerDay * totalDays * supplyBasePrice
                val distLYSourceToDest = Misc.getDistanceLY(sourceEntity, destEntity)
                val fuelCost = fuelCostPerLY * distLYSourceToDest * fuelBasePrice

                val netProfit = tradeMargin - supplyCost - fuelCost
                if (netProfit <= 0f) continue

                val profitPerDay = netProfit / totalDays
                var economyMultiplier = 1.0f
                val sourceExcessAdj = (
                    (sourceData.excesses[commodityId] ?: 0).coerceAtLeast(0)
                        - (simState?.getExcessUsed(fromMarket.id, commodityId) ?: 0)
                    ).toFloat()
                if (sourceExcessAdj > 0f) economyMultiplier += TIHConfig.excessBonusMax * min(sourceExcessAdj / 10f, 1.0f)
                val destDeficitAdj = (
                    (destData.deficits[commodityId] ?: 0).coerceAtLeast(0)
                        - (simState?.getDeficitUsed(dest.id, commodityId) ?: 0)
                    ).toFloat()
                if (destDeficitAdj > 0f) economyMultiplier += TIHConfig.deficitBonusMax * min(destDeficitAdj / 10f, 1.0f)
                if (destData.hasTradeDisruption) economyMultiplier += TIHConfig.disruptionBonus

                val adjustedProfitPerDay = profitPerDay * economyMultiplier
                if (adjustedProfitPerDay <= minProfitPerDay) continue

                val riskMultiplier = computeRiskMultiplier(totalDays, sourceData.hasTradeDisruption || destData.hasTradeDisruption)
                val riskAdjustedScorePerDay = adjustedProfitPerDay * riskMultiplier

                val route = TradeRoute(
                    source = fromMarket,
                    dest = dest,
                    commodityId = commodityId,
                    quantity = quantity,
                    expectedProfit = netProfit,
                    estimatedDays = totalDays,
                    expectedBuyCost = buyTotal,
                    expectedSellRevenue = sellTotal
                )
                val cand = TradeRouteCandidate(route, adjustedProfitPerDay, riskAdjustedScorePerDay)
                pq.add(cand)
                if (pq.size > normalizedLimit) {
                    pq.poll()
                }
            }
        }

        if (pq.isEmpty()) {
            return emptyList()
        }
        return pq.toList().sortedByDescending { it.riskAdjustedScorePerDay }
    }

    fun findBestRouteFrom(fleet: CampaignFleetAPI, fromMarket: MarketAPI): TradeRoute? {
        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()
        val usableCredits = playerCredits * (TIHConfig.maxCreditsUsagePercent / 100f)

        val commodityBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        val markets = allMarkets.filter { market ->
            !market.isHidden
                    && !fleetFaction.isHostileTo(market.faction)
                    && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                    && market.id !in marketBlacklist
        }

        if (markets.size < 2) return null

        val commodityIds = economy.allCommodityIds.filter { id ->
            val spec = economy.getCommoditySpec(id)
            !spec.isPersonnel && !spec.isMeta && !spec.isNonEcon
                    && !spec.hasTag("nontradeable")
                    && id !in commodityBlacklist
        }

        val cargoSpace = fleet.cargo.spaceLeft

        val supplyCostPerDay = fleet.logistics.totalSuppliesPerDay
        val supplyBasePrice = economy.getCommoditySpec(Commodities.SUPPLIES)?.basePrice ?: 30f
        val fuelCostPerLY = fleet.logistics.fuelCostPerLightYear
        val fuelBasePrice = economy.getCommoditySpec(Commodities.FUEL)?.basePrice ?: 25f

        // Refresh cache if stale
        val clock = Global.getSector().clock
        val currentTime = clock.timestamp
        if (cachedMarketPrices == null || cacheTimestamp < 0L || clock.getElapsedDaysSince(cacheTimestamp) > TIHConfig.cacheRefreshDays) {
            refreshPriceCache(markets, commodityIds)
            cacheTimestamp = currentTime
        }

        val cache = cachedMarketPrices ?: return null
        val sourceData = cache[fromMarket.id] ?: return null

        val minProfitPerDay = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_MIN_PROFIT_PER_DAY)
            .takeIf { it > 0f } ?: TradeFleetScript.DEFAULT_MIN_PROFIT_PER_DAY

        var bestRoute: TradeRoute? = null
        var bestProfitPerDay = minProfitPerDay

        val sourceEntity = fromMarket.primaryEntity ?: return null

        for (commodityId in commodityIds) {
            val spec = economy.getCommoditySpec(commodityId)
            val unitCargo = spec.cargoSpace
            if (unitCargo <= 0f) continue

            val buyPricePerUnit = sourceData.supplyPrices[commodityId] ?: continue
            if (buyPricePerUnit <= 0f) continue

            for (dest in markets) {
                if (fromMarket.id == dest.id) continue
                val destData = cache[dest.id] ?: continue
                val sellPricePerUnit = destData.demandPrices[commodityId] ?: continue

                val marginPerUnit = sellPricePerUnit - buyPricePerUnit
                if (marginPerUnit <= 0f) continue

                val maxByCargo = (cargoSpace / unitCargo).toInt()
                val maxByCredits = if (buyPricePerUnit > 0f) (usableCredits / buyPricePerUnit).toInt() else 0
                // Progressive cap based on config: quadratic + linear scaling
                val marketSize = min(fromMarket.size, dest.size)
                val maxReasonable = (marketSize * marketSize * TIHConfig.quantityScalingQuadratic) + (marketSize * TIHConfig.quantityScalingLinear)
                val initialCap = min(min(maxByCargo, maxByCredits), maxReasonable)
                val quantity = calculateNexStyleQuantityCap(sourceData, destData, commodityId, initialCap, simState = null)
                if (quantity <= 0) continue

                val buyTotal = fromMarket.getSupplyPrice(commodityId, quantity.toDouble(), true)
                val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                val tradeMargin = sellTotal - buyTotal

                if (tradeMargin <= 0f) continue
                if (buyTotal > usableCredits) continue

                val destEntity = dest.primaryEntity ?: continue
                // Fleet is already at source — no travel to source needed
                val daysSourceToDest = RouteLocationCalculator.getTravelDays(sourceEntity, destEntity)
                val totalDays = max(daysSourceToDest, 1f)

                val supplyCost = supplyCostPerDay * totalDays * supplyBasePrice

                val distLYSourceToDest = Misc.getDistanceLY(sourceEntity, destEntity)
                val fuelCost = fuelCostPerLY * distLYSourceToDest * fuelBasePrice

                val netProfit = tradeMargin - supplyCost - fuelCost
                if (netProfit <= 0f) continue

                val profitPerDay = netProfit / totalDays

                // Economy bonus
                var economyMultiplier = 1.0f
                val sourceExcess = (sourceData.excesses[commodityId] ?: 0).toFloat()
                if (sourceExcess > 0f) economyMultiplier += TIHConfig.excessBonusMax * min(sourceExcess / 10f, 1.0f)
                val destDeficit = (destData.deficits[commodityId] ?: 0).toFloat()
                if (destDeficit > 0f) economyMultiplier += TIHConfig.deficitBonusMax * min(destDeficit / 10f, 1.0f)
                if (destData.hasTradeDisruption) economyMultiplier += TIHConfig.disruptionBonus

                val adjustedProfitPerDay = profitPerDay * economyMultiplier

                if (adjustedProfitPerDay > bestProfitPerDay) {
                    bestProfitPerDay = adjustedProfitPerDay
                    bestRoute = TradeRoute(
                        source = fromMarket,
                        dest = dest,
                        commodityId = commodityId,
                        quantity = quantity,
                        expectedProfit = netProfit,
                        estimatedDays = totalDays,
                        expectedBuyCost = buyTotal,
                        expectedSellRevenue = sellTotal
                    )
                }
            }
        }

        return bestRoute
    }

    private fun refreshPriceCache(markets: List<MarketAPI>, commodityIds: List<String>) {
        val cache = mutableMapOf<String, MarketPriceData>()
        for (market in markets) {
            val supply = mutableMapOf<String, Float>()
            val demand = mutableMapOf<String, Float>()
            val deficits = mutableMapOf<String, Int>()
            val excesses = mutableMapOf<String, Int>()
            for (id in commodityIds) {
                // Price for 1 unit as a baseline for comparison (with player tariffs)
                supply[id] = market.getSupplyPrice(id, 1.0, true)
                demand[id] = market.getDemandPrice(id, 1.0, true)
                val comData = market.getCommodityData(id)
                deficits[id] = comData.deficitQuantity
                excesses[id] = comData.excessQuantity
            }
            val hasDisruption = market.hasCondition("event_trade_disruption")
                    || market.hasCondition("blockaded")
            cache[market.id] = MarketPriceData(market, supply, demand, deficits, excesses, hasDisruption)
        }
        cachedMarketPrices = cache
    }

    data class SellRouteCandidate(
        val dest: MarketAPI,
        val revenue: Float,
        val netProfit: Float,
        val profitPerDay: Float,
        val estimatedDays: Float
    )

    fun findBestSellDestinationForCargo(
        fleet: CampaignFleetAPI,
        commodityId: String,
        quantity: Float,
        buyCost: Float,
        excludedMarketIds: Set<String> = emptySet()
    ): SellRouteCandidate? {
        if (quantity <= 0f) {
            return null
        }

        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction

        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
            ?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()

        val markets = allMarkets.filter { market ->
            !market.isHidden
                && !fleetFaction.isHostileTo(market.faction)
                && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                && market.id !in marketBlacklist
                && market.id !in excludedMarketIds
        }
        if (markets.isEmpty()) {
            return null
        }

        val supplyCostPerDay = fleet.logistics.totalSuppliesPerDay
        val supplyBasePrice = economy.getCommoditySpec(Commodities.SUPPLIES)?.basePrice ?: 30f
        val fuelCostPerLY = fleet.logistics.fuelCostPerLightYear
        val fuelBasePrice = economy.getCommoditySpec(Commodities.FUEL)?.basePrice ?: 25f

        var best: SellRouteCandidate? = null
        var bestProfitPerDay = Float.NEGATIVE_INFINITY

        for (dest in markets) {
            val destEntity = dest.primaryEntity ?: continue
            val travelDays = RouteLocationCalculator.getTravelDays(fleet, destEntity)
            val effectiveDays = max(travelDays, 1f)

            val sellRevenue = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
            if (sellRevenue <= 0f) {
                continue
            }

            val supplyCost = supplyCostPerDay * travelDays * supplyBasePrice
            val distLY = Misc.getDistanceLY(fleet, destEntity)
            val fuelCost = fuelCostPerLY * distLY * fuelBasePrice

            val netProfit = (sellRevenue - buyCost) - supplyCost - fuelCost
            val profitPerDay = netProfit / effectiveDays

            if (profitPerDay > bestProfitPerDay) {
                bestProfitPerDay = profitPerDay
                best = SellRouteCandidate(
                    dest = dest,
                    revenue = sellRevenue,
                    netProfit = netProfit,
                    profitPerDay = profitPerDay,
                    estimatedDays = effectiveDays
                )
            }
        }

        return best
    }

    fun invalidateCache() {
        cachedMarketPrices = null
    }
}
