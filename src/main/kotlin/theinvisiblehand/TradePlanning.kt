package theinvisiblehand

internal data class TradePlan(
    val legs: List<TradeRoute>,
    val scorePerDay: Float,
    val debug: String
)

internal data class TradeRouteCandidate(
    val route: TradeRoute,
    val adjustedProfitPerDay: Float,
    val riskAdjustedScorePerDay: Float
) {
    val tradeMargin: Float
        get() = route.expectedSellRevenue - route.expectedBuyCost

    val riskAdjustedNet: Float
        get() = riskAdjustedScorePerDay * route.estimatedDays
}

internal class TradePlanSimState(
    var credits: Float
) {
    private val excessUsed: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()
    private val deficitUsed: MutableMap<String, MutableMap<String, Int>> = mutableMapOf()

    fun getExcessUsed(marketId: String, commodityId: String): Int {
        return excessUsed[marketId]?.get(commodityId) ?: 0
    }

    fun getDeficitUsed(marketId: String, commodityId: String): Int {
        return deficitUsed[marketId]?.get(commodityId) ?: 0
    }

    fun addExcessUsed(marketId: String, commodityId: String, amount: Int) {
        if (amount <= 0) return
        val byCommodity = excessUsed.getOrPut(marketId) { mutableMapOf() }
        byCommodity[commodityId] = (byCommodity[commodityId] ?: 0) + amount
    }

    fun addDeficitUsed(marketId: String, commodityId: String, amount: Int) {
        if (amount <= 0) return
        val byCommodity = deficitUsed.getOrPut(marketId) { mutableMapOf() }
        byCommodity[commodityId] = (byCommodity[commodityId] ?: 0) + amount
    }

    fun copyDeep(): TradePlanSimState {
        val copy = TradePlanSimState(credits)

        for ((marketId, map) in excessUsed) {
            for ((commodityId, amount) in map) {
                copy.addExcessUsed(marketId, commodityId, amount)
            }
        }
        for ((marketId, map) in deficitUsed) {
            for ((commodityId, amount) in map) {
                copy.addDeficitUsed(marketId, commodityId, amount)
            }
        }

        return copy
    }
}
