package theinvisiblehand

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.intel.specialforces.SpecialForcesIntel

class TradeFleetScript(private val fleet: CampaignFleetAPI) : EveryFrameScript {

    companion object {
        const val MEM_KEY_TRADING = "\$tih_trading"
        const val MEM_KEY_STATE = "\$tih_trade_state"
        const val MEM_KEY_COMMODITY = "\$tih_trade_commodity"
        const val MEM_KEY_QUANTITY = "\$tih_trade_quantity"
        const val MEM_KEY_SOURCE_MARKET = "\$tih_trade_source"
        const val MEM_KEY_DEST_MARKET = "\$tih_trade_dest"
        const val MEM_KEY_TOTAL_PROFIT = "\$tih_trade_total_profit"
        const val MEM_KEY_BUY_COST = "\$tih_trade_buy_cost"

        private const val TRADE_MOD_ID = "tih_trade"
        private const val TRADE_MOD_DAYS = 30f
        private const val ARRIVAL_DISTANCE = 300f
    }

    enum class TradeState {
        EVALUATING,
        TRAVELING_TO_BUY,
        BUYING,
        TRAVELING_TO_SELL,
        SELLING
    }

    private val interval = IntervalUtil(0.5f, 1.0f)
    private var state = TradeState.EVALUATING
    private var currentRoute: TradeRoute? = null

    init {
        // Restore state from fleet memory if resuming from save
        val savedState = fleet.memoryWithoutUpdate.getString(MEM_KEY_STATE)
        if (savedState != null) {
            state = try {
                TradeState.valueOf(savedState)
            } catch (_: IllegalArgumentException) {
                TradeState.EVALUATING
            }
        }

        // Restore route data if mid-trade
        if (state != TradeState.EVALUATING) {
            currentRoute = restoreRouteFromMemory()
            if (currentRoute == null) {
                state = TradeState.EVALUATING
            }
        }

        // Mark fleet as busy to prevent military diversion
        fleet.memoryWithoutUpdate.set(MemFlags.FLEET_BUSY, true)
    }

    override fun isDone(): Boolean {
        return !fleet.memoryWithoutUpdate.getBoolean(MEM_KEY_TRADING)
    }

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        if (isDone) return

        val days = Misc.getDays(amount)
        interval.advance(days)
        if (!interval.intervalElapsed()) return

        saveStateToMemory()

        when (state) {
            TradeState.EVALUATING -> evaluate()
            TradeState.TRAVELING_TO_BUY -> checkArrival(TradeState.BUYING)
            TradeState.BUYING -> buy()
            TradeState.TRAVELING_TO_SELL -> checkArrival(TradeState.SELLING)
            TradeState.SELLING -> sell()
        }
    }

    private fun evaluate() {
        val route = TradeRouteCalculator.findBestRoute(fleet)
        if (route == null) {
            // No profitable route found - orbit passively and retry next interval
            fleet.clearAssignments()
            fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, fleet, 2f, "Evaluating trade routes")
            updateTaskText("Auto-trading: evaluating routes")
            return
        }

        currentRoute = route
        saveRouteToMemory(route)

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Travel to source market
        val sourceEntity = route.source.primaryEntity
        fleet.clearAssignments()
        fleet.addAssignment(
            FleetAssignment.GO_TO_LOCATION, sourceEntity, 999f,
            "Traveling to buy $commodityName at ${route.source.name}"
        )
        state = TradeState.TRAVELING_TO_BUY
        saveStateToMemory()
        updateTaskText("Auto-trading: buying $commodityName at ${route.source.name}")
    }

    private fun checkArrival(nextState: TradeState) {
        val route = currentRoute ?: run {
            state = TradeState.EVALUATING
            return
        }

        val target = when (state) {
            TradeState.TRAVELING_TO_BUY -> route.source.primaryEntity
            TradeState.TRAVELING_TO_SELL -> route.dest.primaryEntity
            else -> return
        } ?: run {
            state = TradeState.EVALUATING
            return
        }

        val dist = Misc.getDistance(fleet.location, target.location)
        if (dist < ARRIVAL_DISTANCE || fleet.containingLocation == target.containingLocation && dist < ARRIVAL_DISTANCE * 3) {
            state = nextState
            saveStateToMemory()
            // Process immediately
            when (nextState) {
                TradeState.BUYING -> buy()
                TradeState.SELLING -> sell()
                else -> {}
            }
        }
    }

    private fun buy() {
        val route = currentRoute ?: run {
            state = TradeState.EVALUATING
            return
        }

        val playerCredits = Global.getSector().playerFleet.cargo.credits
        val buyPrice = route.source.getSupplyPrice(route.commodityId, route.quantity.toDouble(), true)

        if (playerCredits.get() < buyPrice) {
            // Can't afford - re-evaluate
            state = TradeState.EVALUATING
            currentRoute = null
            saveStateToMemory()
            return
        }

        // Execute purchase
        playerCredits.subtract(buyPrice)
        fleet.cargo.addCommodity(route.commodityId, route.quantity.toFloat())

        // Store buy cost for profit calculation on sell
        fleet.memoryWithoutUpdate.set(MEM_KEY_BUY_COST, buyPrice)

        // Market economic impact
        val comData = route.source.getCommodityData(route.commodityId)
        comData.addTradeModMinus(TRADE_MOD_ID, route.quantity.toFloat(), TRADE_MOD_DAYS)

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Notify player
        sendTradeNotification(
            "${fleet.name}: bought ${route.quantity} $commodityName",
            "at ${route.source.name} for ${Misc.getDGSCredits(buyPrice)}",
            "ui_cargo_default"
        )

        // Travel to destination
        val destEntity = route.dest.primaryEntity
        fleet.clearAssignments()
        fleet.addAssignment(
            FleetAssignment.GO_TO_LOCATION, destEntity, 999f,
            "Traveling to sell $commodityName at ${route.dest.name}"
        )
        state = TradeState.TRAVELING_TO_SELL
        saveStateToMemory()
        updateTaskText("Auto-trading: selling $commodityName at ${route.dest.name}")
    }

    private fun sell() {
        val route = currentRoute ?: run {
            state = TradeState.EVALUATING
            return
        }

        val actualQty = fleet.cargo.getCommodityQuantity(route.commodityId)
        if (actualQty <= 0f) {
            // Cargo lost somehow - re-evaluate
            state = TradeState.EVALUATING
            currentRoute = null
            saveStateToMemory()
            return
        }

        val sellQty = actualQty.coerceAtMost(route.quantity.toFloat())
        val sellPrice = route.dest.getDemandPrice(route.commodityId, sellQty.toDouble(), true)

        // Execute sale
        fleet.cargo.removeCommodity(route.commodityId, sellQty)
        Global.getSector().playerFleet.cargo.credits.add(sellPrice)

        // Market economic impact
        val comData = route.dest.getCommodityData(route.commodityId)
        comData.addTradeModPlus(TRADE_MOD_ID, sellQty, TRADE_MOD_DAYS)

        // Compute actual profit (sell - buy)
        val buyCost = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_BUY_COST)
        val profit = sellPrice - buyCost
        val totalProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_TOTAL_PROFIT) + profit
        fleet.memoryWithoutUpdate.set(MEM_KEY_TOTAL_PROFIT, totalProfit)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_BUY_COST)

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Notify player
        sendTradeNotification(
            "${fleet.name}: sold ${sellQty.toInt()} $commodityName",
            "at ${route.dest.name} for ${Misc.getDGSCredits(sellPrice)} (profit: ${Misc.getDGSCredits(profit)})",
            "ui_intel_monthly_income_positive"
        )

        // Back to evaluating for next trade
        currentRoute = null
        state = TradeState.EVALUATING
        saveStateToMemory()
        updateTaskText("Auto-trading: evaluating routes")
    }

    private fun saveStateToMemory() {
        fleet.memoryWithoutUpdate.set(MEM_KEY_STATE, state.name)
    }

    private fun saveRouteToMemory(route: TradeRoute) {
        fleet.memoryWithoutUpdate.set(MEM_KEY_COMMODITY, route.commodityId)
        fleet.memoryWithoutUpdate.set(MEM_KEY_QUANTITY, route.quantity)
        fleet.memoryWithoutUpdate.set(MEM_KEY_SOURCE_MARKET, route.source.id)
        fleet.memoryWithoutUpdate.set(MEM_KEY_DEST_MARKET, route.dest.id)
    }

    private fun restoreRouteFromMemory(): TradeRoute? {
        val mem = fleet.memoryWithoutUpdate
        val commodityId = mem.getString(MEM_KEY_COMMODITY) ?: return null
        val quantity = mem.getFloat(MEM_KEY_QUANTITY).toInt()
        val sourceId = mem.getString(MEM_KEY_SOURCE_MARKET) ?: return null
        val destId = mem.getString(MEM_KEY_DEST_MARKET) ?: return null

        val economy = Global.getSector().economy
        val source = economy.getMarket(sourceId) ?: return null
        val dest = economy.getMarket(destId) ?: return null

        return TradeRoute(
            source = source,
            dest = dest,
            commodityId = commodityId,
            quantity = quantity,
            expectedProfit = 0f,
            estimatedDays = 0f
        )
    }

    fun cleanup() {
        fleet.memoryWithoutUpdate.unset(MEM_KEY_STATE)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_COMMODITY)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_QUANTITY)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_SOURCE_MARKET)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_DEST_MARKET)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_BUY_COST)
        fleet.memoryWithoutUpdate.unset(MemFlags.FLEET_BUSY)
    }

    private fun sendTradeNotification(title: String, detail: String, soundId: String) {
        val intel = MessageIntel(title, Misc.getBasePlayerColor())
        intel.addLine(detail, Misc.getTextColor())
        intel.icon = InvisibleHandModPlugin.ICON_PATH
        intel.sound = soundId
        Global.getSector().campaignUI.addMessage(intel)
    }

    private fun updateTaskText(text: String) {
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet) ?: return
        val task = sf.routeAI?.currentTask
        if (task is AutoTradeTask) {
            task.tradeActionText = text
        }
    }
}
