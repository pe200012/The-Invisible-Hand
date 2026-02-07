package theinvisiblehand

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator
import com.fs.starfarer.api.impl.campaign.ids.Commodities
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.util.Misc
import kotlin.math.max
import kotlin.math.min

object TradeRouteCalculator {

    private const val MIN_PROFIT_PER_DAY = 500f
    private const val CACHE_DURATION_DAYS = 1f

    private var cachedMarketPrices: MutableMap<String, MarketPriceData>? = null
    private var cacheTimestamp: Long = -1L

    private data class MarketPriceData(
        val market: MarketAPI,
        val supplyPrices: Map<String, Float>,
        val demandPrices: Map<String, Float>
    )

    fun findBestRoute(fleet: CampaignFleetAPI): TradeRoute? {
        val economy = Global.getSector().economy
        val allMarkets = economy.marketsCopy
        val fleetFaction = fleet.faction
        val playerCredits = Global.getSector().playerFleet.cargo.credits.get()

        // Filter to accessible markets
        val markets = allMarkets.filter { market ->
            !market.isHidden
                    && !fleetFaction.isHostileTo(market.faction)
                    && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
        }

        if (markets.size < 2) return null

        val commodityIds = economy.allCommodityIds.filter { id ->
            val spec = economy.getCommoditySpec(id)
            !spec.isPersonnel && !spec.isMeta && !spec.isNonEcon
                    && !spec.hasTag("nontradeable")
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
        if (cachedMarketPrices == null || cacheTimestamp < 0L || clock.getElapsedDaysSince(cacheTimestamp) > CACHE_DURATION_DAYS) {
            refreshPriceCache(markets, commodityIds)
            cacheTimestamp = currentTime
        }

        val cache = cachedMarketPrices ?: return null
        val fleetToken = fleet.containingLocation.createToken(fleet.location)

        var bestRoute: TradeRoute? = null
        var bestProfitPerDay = MIN_PROFIT_PER_DAY

        for (commodityId in commodityIds) {
            val spec = economy.getCommoditySpec(commodityId)
            val unitCargo = spec.cargoSpace
            if (unitCargo <= 0f) continue

            for (source in markets) {
                val sourceData = cache[source.id] ?: continue
                val buyPricePerUnit = sourceData.supplyPrices[commodityId] ?: continue

                if (buyPricePerUnit <= 0f) continue

                for (dest in markets) {
                    if (source.id == dest.id) continue
                    val destData = cache[dest.id] ?: continue
                    val sellPricePerUnit = destData.demandPrices[commodityId] ?: continue

                    val marginPerUnit = sellPricePerUnit - buyPricePerUnit
                    if (marginPerUnit <= 0f) continue

                    // Calculate trade quantity
                    val maxByCargo = (cargoSpace / unitCargo).toInt()
                    val maxByCredits = if (buyPricePerUnit > 0f) (playerCredits / buyPricePerUnit).toInt() else 0
                    val maxReasonable = 200 // Cap to avoid excessive market impact
                    val quantity = min(min(maxByCargo, maxByCredits), maxReasonable)
                    if (quantity <= 0) continue

                    // Use actual price functions with player tariffs for accurate pricing
                    val buyTotal = source.getSupplyPrice(commodityId, quantity.toDouble(), true)
                    val sellTotal = dest.getDemandPrice(commodityId, quantity.toDouble(), true)
                    val tradeMargin = sellTotal - buyTotal

                    if (tradeMargin <= 0f) continue
                    if (buyTotal > playerCredits) continue

                    // Estimate travel costs
                    val sourceEntity = source.primaryEntity ?: continue
                    val destEntity = dest.primaryEntity ?: continue
                    val daysToSource = RouteLocationCalculator.getTravelDays(fleetToken, sourceEntity)
                    val daysSourceToDest = RouteLocationCalculator.getTravelDays(sourceEntity, destEntity)
                    val totalDays = max(daysToSource + daysSourceToDest, 1f)

                    // Supply consumption cost over the trip
                    val supplyCost = supplyCostPerDay * totalDays * supplyBasePrice

                    // Fuel consumption cost over the trip
                    val distLYToSource = Misc.getDistanceLY(fleetToken, sourceEntity)
                    val distLYSourceToDest = Misc.getDistanceLY(sourceEntity, destEntity)
                    val totalDistLY = distLYToSource + distLYSourceToDest
                    val fuelCost = fuelCostPerLY * totalDistLY * fuelBasePrice

                    // Net profit after travel expenses
                    val netProfit = tradeMargin - supplyCost - fuelCost

                    if (netProfit <= 0f) continue

                    val profitPerDay = netProfit / totalDays

                    if (profitPerDay > bestProfitPerDay) {
                        bestProfitPerDay = profitPerDay
                        bestRoute = TradeRoute(
                            source = source,
                            dest = dest,
                            commodityId = commodityId,
                            quantity = quantity,
                            expectedProfit = netProfit,
                            estimatedDays = totalDays
                        )
                    }
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
            for (id in commodityIds) {
                // Price for 1 unit as a baseline for comparison (with player tariffs)
                supply[id] = market.getSupplyPrice(id, 1.0, true)
                demand[id] = market.getDemandPrice(id, 1.0, true)
            }
            cache[market.id] = MarketPriceData(market, supply, demand)
        }
        cachedMarketPrices = cache
    }

    fun invalidateCache() {
        cachedMarketPrices = null
    }
}
