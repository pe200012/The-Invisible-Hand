package theinvisiblehand

import com.fs.starfarer.api.campaign.econ.MarketAPI

data class TradeRoute(
    val source: MarketAPI,
    val dest: MarketAPI,
    val commodityId: String,
    val quantity: Int,
    val expectedProfit: Float,
    val estimatedDays: Float,
    val expectedBuyCost: Float = 0f
)
