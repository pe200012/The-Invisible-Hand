package theinvisiblehand

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.intel.specialforces.SpecialForcesIntel
import kotlin.math.min

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
        const val MEM_KEY_MIN_PROFIT_PER_DAY = "\$tih_min_profit_per_day"
        const val MEM_KEY_COMMODITY_BLACKLIST = "\$tih_commodity_blacklist"
        const val MEM_KEY_MARKET_BLACKLIST = "\$tih_market_blacklist"
        const val MEM_KEY_OFFLOADED = "\$tih_offloaded"

        const val DEFAULT_MIN_PROFIT_PER_DAY = 100f

        private const val TRADE_MOD_ID = "tih_trade"
        private const val TRADE_MOD_DAYS = 30f
        private const val ARRIVAL_DISTANCE = 300f
    }

    enum class TradeState {
        OFFLOADING,
        EVALUATING,
        TRAVELING_TO_BUY,
        BUYING,
        TRAVELING_TO_SELL,
        SELLING
    }

    private val interval = IntervalUtil(0.5f, 1.0f)
    private var state = TradeState.OFFLOADING
    private var currentRoute: TradeRoute? = null

    init {
        // Restore state from fleet memory if resuming from save
        val savedState = fleet.memoryWithoutUpdate.getString(MEM_KEY_STATE)
        if (savedState != null) {
            state = try {
                TradeState.valueOf(savedState)
            } catch (_: IllegalArgumentException) {
                if (fleet.memoryWithoutUpdate.getBoolean(MEM_KEY_OFFLOADED))
                    TradeState.EVALUATING
                else
                    TradeState.OFFLOADING
            }
        } else if (fleet.memoryWithoutUpdate.getBoolean(MEM_KEY_OFFLOADED)) {
            // Fresh script but already offloaded (shouldn't normally happen, but be safe)
            state = TradeState.EVALUATING
        }

        // Restore route data if mid-trade
        if (state != TradeState.EVALUATING && state != TradeState.OFFLOADING) {
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
            TradeState.OFFLOADING -> offload()
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

            // Debug: send one-time notification about why no route found
            if (!fleet.memoryWithoutUpdate.getBoolean("\$tih_no_route_notified")) {
                val minProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_MIN_PROFIT_PER_DAY)
                    .takeIf { it > 0f } ?: DEFAULT_MIN_PROFIT_PER_DAY
                val credits = Global.getSector().playerFleet.cargo.credits.get()
                sendTradeNotification(
                    "${fleet.name}: No profitable routes found",
                    "Min profit threshold: ${Misc.getDGSCredits(minProfit)}/day, Available credits: ${Misc.getDGSCredits(credits)}. Will continue searching...",
                    "ui_intel_log_update"
                )
                fleet.memoryWithoutUpdate.set("\$tih_no_route_notified", true, 5f) // Reset after 5 days
            }
            return
        }

        // Clear the notification flag when route is found
        fleet.memoryWithoutUpdate.unset("\$tih_no_route_notified")

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
        updateTaskText(
            "Auto-trading: buying $commodityName at ${route.source.name}",
            from = fleet,
            to = sourceEntity
        )
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

        // Auto-resupply: top off supplies and fuel if running low
        autoResupply(route.source)

        // Notify player
        sendTradeNotification(
            "${fleet.name}: bought ${route.quantity} $commodityName",
            "at ${route.source.name} for ${Misc.getDGSCredits(buyPrice)}",
            "ui_intel_log_update"
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
        updateTaskText(
            "Auto-trading: selling $commodityName at ${route.dest.name}",
            from = route.source.primaryEntity,
            to = destEntity
        )
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

        // Record trade in intel
        val intel = AutoTradeIntel.getOrCreate(fleet)
        intel.recordTrade(profit, route)

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Notify player
        sendTradeNotification(
            "${fleet.name}: sold ${sellQty.toInt()} $commodityName",
            "at ${route.dest.name} for ${Misc.getDGSCredits(sellPrice)} (profit: ${Misc.getDGSCredits(profit)})",
            "ui_intel_log_update"
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
        fleet.memoryWithoutUpdate.unset(MEM_KEY_OFFLOADED)
        fleet.memoryWithoutUpdate.unset(MemFlags.FLEET_BUSY)
    }

    private fun offload() {
        // If already offloaded (from save/load), skip
        if (fleet.memoryWithoutUpdate.getBoolean(MEM_KEY_OFFLOADED)) {
            state = TradeState.EVALUATING
            saveStateToMemory()
            return
        }

        // Check if fleet has anything to offload
        if (!hasCargoToOffload()) {
            fleet.memoryWithoutUpdate.set(MEM_KEY_OFFLOADED, true)
            state = TradeState.EVALUATING
            saveStateToMemory()
            return
        }

        // Find nearest accessible market
        val market = findNearestAccessibleMarket()
        if (market == null) {
            // No market found - skip offloading, go to EVALUATING
            fleet.memoryWithoutUpdate.set(MEM_KEY_OFFLOADED, true)
            state = TradeState.EVALUATING
            saveStateToMemory()
            return
        }

        val entity = market.primaryEntity
        val dist = Misc.getDistance(fleet.location, entity.location)

        if (fleet.containingLocation == entity.containingLocation && dist < ARRIVAL_DISTANCE * 3) {
            // Already at market — execute offload
            executeOffload(market)
            fleet.memoryWithoutUpdate.set(MEM_KEY_OFFLOADED, true)
            state = TradeState.EVALUATING
            saveStateToMemory()
            updateTaskText("Auto-trading: evaluating routes")
        } else {
            // Travel to market
            fleet.clearAssignments()
            fleet.addAssignment(
                FleetAssignment.GO_TO_LOCATION, entity, 999f,
                "Traveling to offload cargo at ${market.name}"
            )
            updateTaskText("Auto-trading: offloading cargo at ${market.name}", to = entity)
        }
    }

    private fun hasCargoToOffload(): Boolean {
        val cargo = fleet.cargo
        for (stack in cargo.stacksCopy) {
            if (stack.isSupplyStack || stack.isFuelStack) continue
            if (stack.size > 0) return true
        }
        return false
    }

    private fun findNearestAccessibleMarket(): MarketAPI? {
        val allMarkets = Global.getSector().economy.marketsCopy
        val fleetFaction = fleet.faction
        return allMarkets
            .filter { market ->
                !market.isHidden
                        && !fleetFaction.isHostileTo(market.faction)
                        && market.hasSubmarket(Submarkets.SUBMARKET_OPEN)
                        && market.primaryEntity != null
            }
            .minByOrNull { Misc.getDistanceLY(fleet, it.primaryEntity) }
    }

    private fun executeOffload(market: MarketAPI) {
        val cargo = fleet.cargo
        var totalSellRevenue = 0f

        // 1. Sell all tradeable commodities (not supplies, not fuel)
        for (stack in cargo.stacksCopy) {
            if (!stack.isCommodityStack) continue
            if (stack.isSupplyStack || stack.isFuelStack) continue

            val commodityId = stack.commodityId ?: continue
            val qty = stack.size
            if (qty <= 0f) continue

            val sellPrice = market.getDemandPrice(commodityId, qty.toDouble(), true)
            cargo.removeCommodity(commodityId, qty)
            Global.getSector().playerFleet.cargo.credits.add(sellPrice)
            totalSellRevenue += sellPrice

            // Market economic impact
            market.getCommodityData(commodityId)
                .addTradeModPlus(TRADE_MOD_ID, qty, TRADE_MOD_DAYS)
        }

        // 2. Transfer valuable items (weapons, fighters, hullmods, specials) to storage
        val storageCargo = findStorageCargo(market)
        if (storageCargo != null) {
            transferValuableItems(cargo, storageCargo)
        } else {
            // Fallback: transfer to player fleet cargo
            val playerCargo = Global.getSector().playerFleet.cargo
            transferValuableItems(cargo, playerCargo)
        }

        cargo.removeEmptyStacks()

        // Notify player
        if (totalSellRevenue > 0f) {
            sendTradeNotification(
                "${fleet.name}: offloaded cargo",
                "Sold commodities for ${Misc.getDGSCredits(totalSellRevenue)} at ${market.name}",
                "ui_intel_log_update"
            )
        }
    }

    private fun transferValuableItems(from: CargoAPI, to: CargoAPI) {
        for (stack in from.stacksCopy) {
            if (stack.isWeaponStack) {
                val weaponId = stack.data as? String ?: continue
                val count = stack.size.toInt()
                to.addWeapons(weaponId, count)
                from.removeWeapons(weaponId, count)
            } else if (stack.isFighterWingStack) {
                val wingId = stack.data as? String ?: continue
                val count = stack.size.toInt()
                to.addFighters(wingId, count)
                from.removeFighters(wingId, count)
            } else if (stack.isSpecialStack) {
                val specialData = stack.specialDataIfSpecial ?: continue
                val qty = stack.size
                to.addSpecial(specialData, qty)
                from.removeItems(CargoAPI.CargoItemType.SPECIAL, specialData, qty)
            }
        }
    }

    private fun findStorageCargo(market: MarketAPI): CargoAPI? {
        // Check if this market has storage
        if (market.hasSubmarket(Submarkets.SUBMARKET_STORAGE)) {
            return market.getSubmarket(Submarkets.SUBMARKET_STORAGE).cargo
        }
        // Search for nearest market with storage
        val allMarkets = Global.getSector().economy.marketsCopy
        val nearestStorage = allMarkets
            .filter { it.hasSubmarket(Submarkets.SUBMARKET_STORAGE) }
            .filter { it.primaryEntity != null }
            .minByOrNull { Misc.getDistanceLY(fleet, it.primaryEntity) }
        if (nearestStorage != null) {
            return nearestStorage.getSubmarket(Submarkets.SUBMARKET_STORAGE).cargo
        }
        return null
    }

    private fun autoResupply(market: com.fs.starfarer.api.campaign.econ.MarketAPI) {
        val cargo = fleet.cargo
        val playerCredits = Global.getSector().playerFleet.cargo.credits

        // Only resupply if we have a profit buffer
        val totalProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_TOTAL_PROFIT)
        if (totalProfit < 5000f) return

        // Resupply supplies if below 50%
        val maxSupplies = cargo.maxCapacity
        val currentSupplies = cargo.supplies
        val threshold = min(200f, maxSupplies * 0.5f)
        if (currentSupplies < threshold) {
            val neededSupplies = (threshold * 1.2f).coerceAtLeast(0f)
            val supplyPrice = market.getSupplyPrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, neededSupplies.toDouble(), true)
            if (playerCredits.get() >= supplyPrice && neededSupplies > 0f) {
                playerCredits.subtract(supplyPrice)
                cargo.addSupplies(neededSupplies)
            }
        }

        // Resupply fuel if below 50%
        val maxFuel = cargo.maxFuel
        val currentFuel = cargo.fuel
        if (currentFuel < maxFuel * 0.5f) {
            val neededFuel = (maxFuel * 0.75f - currentFuel).coerceAtLeast(0f)
            val fuelPrice = market.getSupplyPrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, neededFuel.toDouble(), true)
            if (playerCredits.get() >= fuelPrice && neededFuel > 0f) {
                playerCredits.subtract(fuelPrice)
                cargo.addFuel(neededFuel)
            }
        }
    }

    private fun sendTradeNotification(title: String, detail: String, soundId: String) {
        val intel = MessageIntel(title, Misc.getBasePlayerColor())
        intel.addLine(detail, Misc.getTextColor())
        intel.icon = InvisibleHandModPlugin.ICON_PATH
        intel.sound = soundId
        Global.getSector().campaignUI.addMessage(intel)
    }

    private fun updateTaskText(text: String, from: SectorEntityToken? = null, to: SectorEntityToken? = null) {
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet) ?: return
        val task = sf.routeAI?.currentTask
        if (task is AutoTradeTask) {
            task.tradeActionText = text
        }
        // Update route segment so the intel panel shows correct from/to
        val segment = sf.route?.current
        if (segment != null) {
            if (from != null) segment.from = from
            if (to != null) segment.to = to
        }
    }
}
