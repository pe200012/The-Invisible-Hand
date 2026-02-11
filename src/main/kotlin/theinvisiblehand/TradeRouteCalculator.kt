package theinvisiblehand

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.util.Misc
import kotlin.math.abs
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
        val tradeModQuantities: Map<String, Float>,
        val hasTradeDisruption: Boolean
    )

    fun findBestRoute(fleet: CampaignFleetAPI): TradeRoute? {
        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()

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
                    val maxByCredits = if (buyPricePerUnit > 0f) (playerCredits / buyPricePerUnit).toInt() else 0
                    // Progressive cap based on config: quadratic + linear scaling
                    val marketSize = min(source.size, dest.size)
                    val maxReasonable = (marketSize * marketSize * TIHConfig.quantityScalingQuadratic) + (marketSize * TIHConfig.quantityScalingLinear)
                    val quantity = min(min(maxByCargo, maxByCredits), maxReasonable)
                    if (quantity <= 0) {
                        if (maxByCargo == 0) logFail("no_quantity_cargo_full")
                        else if (maxByCredits == 0) logFail("no_quantity_no_credits")
                        else logFail("no_quantity_other")
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
                    if (buyTotal > playerCredits) {
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

                    // Market impact penalty: avoid over-traded routes
                    val sourceImpact = abs(sourceData.tradeModQuantities[commodityId] ?: 0f)
                    val destImpact = abs(destData.tradeModQuantities[commodityId] ?: 0f)
                    val impactThreshold = TIHConfig.impactThresholdMultiplier * ((marketSize * marketSize * TIHConfig.quantityScalingQuadratic.toFloat()) + (marketSize * TIHConfig.quantityScalingLinear.toFloat()))
                    val impactPenalty = 1.0f - min((sourceImpact + destImpact) / impactThreshold, TIHConfig.impactPenaltyMax)

                    val adjustedProfitPerDay = profitPerDay * economyMultiplier * impactPenalty

                    if (adjustedProfitPerDay > bestProfitPerDay) {
                        bestProfitPerDay = adjustedProfitPerDay
                        bestRoute = TradeRoute(
                            source = source,
                            dest = dest,
                            commodityId = commodityId,
                            quantity = quantity,
                            expectedProfit = netProfit,
                            estimatedDays = totalDays,
                            expectedBuyCost = buyTotal
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
            logger.info("  Markets: ${markets.size}, Commodities: ${commodityIds.size}, Credits: ${Misc.getDGSCredits(playerCredits)}")
            logger.info("  Cargo space: $cargoSpace, Min profit/day: ${Misc.getDGSCredits(minProfitPerDay)}")
            logger.info("  Fail reasons: $failReasons")
        }

        return bestRoute
    }

    fun findBestRouteFrom(fleet: CampaignFleetAPI, fromMarket: MarketAPI): TradeRoute? {
        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()

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
                val maxByCredits = if (buyPricePerUnit > 0f) (playerCredits / buyPricePerUnit).toInt() else 0
                // Progressive cap: size 3 = 200, size 5 = 400, size 7 = 700, size 10 = 1300
                val marketSize = min(fromMarket.size, dest.size)
                val maxReasonable = (marketSize * marketSize * 10) + (marketSize * 50)
                val quantity = min(min(maxByCargo, maxByCredits), maxReasonable)
                if (quantity <= 0) continue

                val buyTotal = fromMarket.getSupplyPrice(commodityId, quantity.toDouble(), true)
                val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                val tradeMargin = sellTotal - buyTotal

                if (tradeMargin <= 0f) continue
                if (buyTotal > playerCredits) continue

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

                // Market impact penalty
                val sourceImpact = abs(sourceData.tradeModQuantities[commodityId] ?: 0f)
                val destImpact = abs(destData.tradeModQuantities[commodityId] ?: 0f)
                val impactThreshold = TIHConfig.impactThresholdMultiplier * ((marketSize * marketSize * TIHConfig.quantityScalingQuadratic.toFloat()) + (marketSize * TIHConfig.quantityScalingLinear.toFloat()))
                val impactPenalty = 1.0f - min((sourceImpact + destImpact) / impactThreshold, TIHConfig.impactPenaltyMax)

                val adjustedProfitPerDay = profitPerDay * economyMultiplier * impactPenalty

                if (adjustedProfitPerDay > bestProfitPerDay) {
                    bestProfitPerDay = adjustedProfitPerDay
                    bestRoute = TradeRoute(
                        source = fromMarket,
                        dest = dest,
                        commodityId = commodityId,
                        quantity = quantity,
                        expectedProfit = netProfit,
                        estimatedDays = totalDays,
                        expectedBuyCost = buyTotal
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
            val tradeMods = mutableMapOf<String, Float>()
            for (id in commodityIds) {
                // Price for 1 unit as a baseline for comparison (with player tariffs)
                supply[id] = market.getSupplyPrice(id, 1.0, true)
                demand[id] = market.getDemandPrice(id, 1.0, true)
                val comData = market.getCommodityData(id)
                deficits[id] = comData.deficitQuantity
                excesses[id] = comData.excessQuantity
                tradeMods[id] = comData.combinedTradeModQuantity
            }
            val hasDisruption = market.hasCondition("event_trade_disruption")
                    || market.hasCondition("blockaded")
            cache[market.id] = MarketPriceData(market, supply, demand, deficits, excesses, tradeMods, hasDisruption)
        }
        cachedMarketPrices = cache
    }

    fun invalidateCache() {
        cachedMarketPrices = null
    }
}
