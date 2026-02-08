package theinvisiblehand

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.SectorMapAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.util.Misc

class AutoTradeIntel(private val fleet: CampaignFleetAPI) : BaseIntelPlugin() {

    companion object {
        private const val MEM_KEY_INTEL = "\$tih_intel"
        private const val MEM_KEY_TRADES_COMPLETED = "\$tih_trades_completed"
        private const val MEM_KEY_BEST_ROUTE_PROFIT = "\$tih_best_route_profit"
        private const val MEM_KEY_BEST_ROUTE_DESC = "\$tih_best_route_desc"
        const val MAX_HISTORY_SIZE = 50
        const val DISPLAY_HISTORY_COUNT = 10

        fun getOrCreate(fleet: CampaignFleetAPI): AutoTradeIntel {
            val existing = fleet.memoryWithoutUpdate.get(MEM_KEY_INTEL)
            if (existing is AutoTradeIntel) return existing

            val intel = AutoTradeIntel(fleet)
            fleet.memoryWithoutUpdate.set(MEM_KEY_INTEL, intel)
            return intel
        }

        fun remove(fleet: CampaignFleetAPI) {
            val intel = fleet.memoryWithoutUpdate.get(MEM_KEY_INTEL) as? AutoTradeIntel
            intel?.endAfterDelay()
            fleet.memoryWithoutUpdate.unset(MEM_KEY_INTEL)
        }
    }

    // Nullable for XStream backward compat (bypasses constructor)
    private var tradeHistory: MutableList<TradeRecord>? = null

    private fun getHistory(): MutableList<TradeRecord> {
        if (tradeHistory == null) tradeHistory = mutableListOf()
        return tradeHistory!!
    }

    init {
        isImportant = false
    }

    override fun getName(): String {
        return "${fleet.name}: Auto-Trading"
    }

    override fun createSmallDescription(info: TooltipMakerAPI, width: Float, height: Float) {
        val h = Misc.getHighlightColor()
        val tc = Misc.getTextColor()
        val pad = 10f

        // Fleet info
        info.addPara("Fleet: ${fleet.name}", pad)
        info.addSectionHeading("Statistics", fleet.faction.baseUIColor, fleet.faction.darkUIColor, Alignment.MID, pad)

        // Total profit
        val totalProfit = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_TOTAL_PROFIT)
        info.addPara("Total profit: %s", pad, h, Misc.getDGSCredits(totalProfit))

        // Trades completed
        val tradesCompleted = fleet.memoryWithoutUpdate.getInt(MEM_KEY_TRADES_COMPLETED)
        info.addPara("Trades completed: %s", 3f, h, tradesCompleted.toString())

        // Best route
        val bestRouteProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_BEST_ROUTE_PROFIT)
        val bestRouteDesc = fleet.memoryWithoutUpdate.getString(MEM_KEY_BEST_ROUTE_DESC)
        if (bestRouteProfit > 0f && bestRouteDesc != null) {
            info.addPara("Best route: %s (%s profit)", 3f, h, bestRouteDesc, Misc.getDGSCredits(bestRouteProfit))
        }

        // Current status
        info.addSectionHeading("Current Status", fleet.faction.baseUIColor, fleet.faction.darkUIColor, Alignment.MID, pad)
        val state = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_STATE)
        if (state != null) {
            val stateText = when (state) {
                "OFFLOADING" -> "Offloading non-trade cargo"
                "EVALUATING" -> "Evaluating trade routes"
                "TRAVELING_TO_BUY" -> {
                    val sourceMarketId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_SOURCE_MARKET)
                    val commodityId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY)
                    val commodityName = Global.getSector().economy.getCommoditySpec(commodityId)?.name ?: commodityId
                    val sourceName = Global.getSector().economy.getMarket(sourceMarketId)?.name ?: "Unknown"
                    "Traveling to buy $commodityName at $sourceName"
                }
                "BUYING" -> "Executing purchase"
                "DELAYING" -> {
                    val actionText = fleet.memoryWithoutUpdate.getString("\$tih_delay_callback") ?: "Processing"
                    actionText
                }
                "TRAVELING_TO_SELL" -> {
                    val destMarketId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_DEST_MARKET)
                    val commodityId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY)
                    val commodityName = Global.getSector().economy.getCommoditySpec(commodityId)?.name ?: commodityId
                    val destName = Global.getSector().economy.getMarket(destMarketId)?.name ?: "Unknown"
                    "Traveling to sell $commodityName at $destName"
                }
                "SELLING" -> "Executing sale"
                else -> state
            }
            info.addPara(stateText, tc, 3f)
        }

        // Configuration
        info.addSectionHeading("Configuration", fleet.faction.baseUIColor, fleet.faction.darkUIColor, Alignment.MID, pad)
        val minProfit = fleet.memoryWithoutUpdate.getFloat(TradeFleetScript.MEM_KEY_MIN_PROFIT_PER_DAY)
            .takeIf { it > 0f } ?: TradeFleetScript.DEFAULT_MIN_PROFIT_PER_DAY
        info.addPara("Min profit/day: %s", 3f, h, Misc.getDGSCredits(minProfit))

        val commodityBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_COMMODITY_BLACKLIST)
        if (!commodityBlacklist.isNullOrBlank()) {
            info.addPara("Blacklisted commodities: %s", 3f, h, commodityBlacklist)
        }

        val marketBlacklist = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_MARKET_BLACKLIST)
        if (!marketBlacklist.isNullOrBlank()) {
            info.addPara("Blacklisted markets: %s", 3f, h, marketBlacklist)
        }

        // Trade History section
        val history = getHistory()
        if (history.isNotEmpty()) {
            info.addSectionHeading("Trade History", fleet.faction.baseUIColor, fleet.faction.darkUIColor, Alignment.MID, pad)

            // 30-day profit/day average
            val clock = Global.getSector().clock
            val recentTrades = history.filter { clock.getElapsedDaysSince(it.timestamp) <= 30f }
            if (recentTrades.isNotEmpty()) {
                val recentProfit = recentTrades.sumOf { it.profit.toDouble() }.toFloat()
                val daySpan = clock.getElapsedDaysSince(recentTrades.first().timestamp).coerceAtLeast(1f)
                info.addPara("Avg profit/day (30d): %s", 3f, h, Misc.getDGSCredits(recentProfit / daySpan))
            }

            // Most traded commodity
            val mostTraded = history.groupBy { it.commodityId }.maxByOrNull { it.value.size }
            if (mostTraded != null) {
                val comName = Global.getSector().economy.getCommoditySpec(mostTraded.key)?.name ?: mostTraded.key
                info.addPara("Most traded: %s (%s trades)", 3f, h, comName, mostTraded.value.size.toString())
            }

            // Recent trades table (last 10, newest first)
            info.addSectionHeading("Recent Trades", fleet.faction.baseUIColor, fleet.faction.darkUIColor, Alignment.MID, pad)
            val tableWidth = width - 20f
            info.beginTable(fleet.faction, 20f,
                "Commodity", tableWidth * 0.25f,
                "Route", tableWidth * 0.40f,
                "Qty", tableWidth * 0.10f,
                "Profit", tableWidth * 0.25f
            )
            for (record in history.takeLast(DISPLAY_HISTORY_COUNT).reversed()) {
                val comName = Global.getSector().economy.getCommoditySpec(record.commodityId)?.name ?: record.commodityId
                val routeText = "${record.sourceMarketName} -> ${record.destMarketName}"
                val profitColor = if (record.profit >= 0f) Misc.getPositiveHighlightColor() else Misc.getNegativeHighlightColor()
                info.addRow(tc, comName, tc, routeText, h, record.quantity.toString(), profitColor, Misc.getDGSCredits(record.profit))
            }
            info.addTable("", 0, pad)
        }
    }

    override fun getIcon(): String {
        return InvisibleHandModPlugin.ICON_PATH
    }

    override fun getCommMessageSound(): String? {
        return null // No sound for this intel
    }

    override fun shouldRemoveIntel(): Boolean {
        // Remove if fleet no longer trading
        return !fleet.memoryWithoutUpdate.getBoolean(TradeFleetScript.MEM_KEY_TRADING) || !fleet.isAlive
    }

    override fun getArrowData(map: SectorMapAPI?): MutableList<IntelInfoPlugin.ArrowData>? {
        val arrows = mutableListOf<IntelInfoPlugin.ArrowData>()

        // Show current route as arrows
        val sourceMarketId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_SOURCE_MARKET)
        val destMarketId = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_DEST_MARKET)
        val state = fleet.memoryWithoutUpdate.getString(TradeFleetScript.MEM_KEY_STATE)

        if (sourceMarketId != null && destMarketId != null && state != null && state != "EVALUATING") {
            val source = Global.getSector().economy.getMarket(sourceMarketId)?.primaryEntity
            val dest = Global.getSector().economy.getMarket(destMarketId)?.primaryEntity

            if (source != null && dest != null) {
                val color = fleet.faction.baseUIColor
                arrows.add(IntelInfoPlugin.ArrowData(10f, source, dest, color))
            }
        }

        return if (arrows.isEmpty()) null else arrows
    }

    fun recordTrade(profit: Float, route: TradeRoute) {
        // Increment trade count
        val count = fleet.memoryWithoutUpdate.getInt(MEM_KEY_TRADES_COMPLETED)
        fleet.memoryWithoutUpdate.set(MEM_KEY_TRADES_COMPLETED, count + 1)

        // Update best route if this one is better
        val bestProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_BEST_ROUTE_PROFIT)
        if (profit > bestProfit) {
            fleet.memoryWithoutUpdate.set(MEM_KEY_BEST_ROUTE_PROFIT, profit)
            val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId
            val desc = "$commodityName: ${route.source.name} → ${route.dest.name}"
            fleet.memoryWithoutUpdate.set(MEM_KEY_BEST_ROUTE_DESC, desc)
        }

        // Append trade record to history
        val record = TradeRecord(
            timestamp = Global.getSector().clock.timestamp,
            commodityId = route.commodityId,
            sourceMarketName = route.source.name,
            destMarketName = route.dest.name,
            quantity = route.quantity,
            profit = profit
        )
        getHistory().add(record)
        while (getHistory().size > MAX_HISTORY_SIZE) getHistory().removeAt(0)
    }
}
