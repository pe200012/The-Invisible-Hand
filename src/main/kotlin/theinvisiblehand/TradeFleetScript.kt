package theinvisiblehand

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.CampaignFleetAPI
import com.fs.starfarer.api.campaign.FleetAssignment
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.comm.CommMessageAPI
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.impl.campaign.ids.MemFlags
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin
import com.fs.starfarer.api.impl.campaign.intel.MessageIntel
import com.fs.starfarer.api.util.IntervalUtil
import com.fs.starfarer.api.util.Misc
import exerelin.campaign.intel.specialforces.SpecialForcesIntel
import java.awt.Color
import kotlin.math.min

class TradeFleetScript(private val fleet: CampaignFleetAPI) : EveryFrameScript {

    companion object {
        const val MEM_KEY_TRADING = "\$tih_trading"
        const val MEM_KEY_STATE = "\$tih_trade_state"
        const val MEM_KEY_COMMODITY = "\$tih_trade_commodity"
        const val MEM_KEY_QUANTITY = "\$tih_trade_quantity"
        const val MEM_KEY_SOURCE_MARKET = "\$tih_trade_source"
        const val MEM_KEY_DEST_MARKET = "\$tih_trade_dest"
        const val MEM_KEY_EXPECTED_BUY_COST = "\$tih_trade_expected_buy_cost"
        const val MEM_KEY_TOTAL_PROFIT = "\$tih_trade_total_profit"
        const val MEM_KEY_BUY_COST = "\$tih_trade_buy_cost"
        const val MEM_KEY_MIN_PROFIT_PER_DAY = "\$tih_min_profit_per_day"
        const val MEM_KEY_COMMODITY_BLACKLIST = "\$tih_commodity_blacklist"
        const val MEM_KEY_MARKET_BLACKLIST = "\$tih_market_blacklist"
        const val MEM_KEY_OFFLOADED = "\$tih_offloaded"
        private const val SAFETY_FLAG_REASON = "tih_auto_trade_safety"

        const val DEFAULT_MIN_PROFIT_PER_DAY = 100f
        private const val TRADE_MOD_ID = "tih_trade"
    }

    private var impactEventCounter = 0L

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
        applyAutoTradeSafetyFlags()
    }

    override fun isDone(): Boolean {
        return !fleet.memoryWithoutUpdate.getBoolean(MEM_KEY_TRADING)
    }

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        if (isDone) {
            cleanup()
            return
        }

        val days = Misc.getDays(amount)
        interval.advance(days)
        if (!interval.intervalElapsed()) return

        applyAutoTradeSafetyFlags()

        // Check if Nexerelin assigned a different task (raid, patrol, etc.)
        val sf = exerelin.campaign.intel.specialforces.SpecialForcesIntel.getIntelFromMemory(fleet)
        val currentTask = sf?.routeAI?.currentTask
        if (currentTask != null && currentTask !is AutoTradeTask) {
            // Different task assigned - stop auto-trading completely
            Global.getLogger(this::class.java).info("${fleet.name}: Different task assigned, stopping auto-trade")
            fleet.memoryWithoutUpdate.unset(TradeFleetScript.MEM_KEY_TRADING)
            cleanup()
            AutoTradeIntel.remove(fleet)
            return
        }

        // Safety net: ensure AutoTradeTask time is high to prevent route expiration
        ensureAutoTradeTask()

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
        // If cargo is full, force re-offload
        if (fleet.cargo.spaceLeft <= 0f && hasCargoToOffload()) {
            Global.getLogger(this::class.java).info("Cargo full during evaluate, forcing re-offload")
            fleet.memoryWithoutUpdate.unset(MEM_KEY_OFFLOADED)
            state = TradeState.OFFLOADING
            saveStateToMemory()
            return
        }

        val route = TradeRouteCalculator.findBestRoute(fleet)
        if (route == null) {
            // No profitable route found - dock at nearby market and retry next interval
            val idleMarket = findNearestAccessibleMarket()
            fleet.clearAssignments()
            if (idleMarket?.primaryEntity != null) {
                fleet.addAssignment(
                    FleetAssignment.ORBIT_PASSIVE, idleMarket.primaryEntity, 2f,
                    "Docked at ${idleMarket.name}, evaluating trade routes"
                )
                updateTaskText("Auto-trading: idle at ${idleMarket.name}", to = idleMarket.primaryEntity)
            } else {
                fleet.addAssignment(FleetAssignment.ORBIT_PASSIVE, fleet, 2f, "Evaluating trade routes")
                updateTaskText("Auto-trading: evaluating routes")
            }

            // Debug: send one-time notification about why no route found
            if (!fleet.memoryWithoutUpdate.getBoolean("\$tih_no_route_notified")) {
                val minProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_MIN_PROFIT_PER_DAY)
                    .takeIf { it > 0f } ?: DEFAULT_MIN_PROFIT_PER_DAY
                val credits = Global.getSector().playerFleet.cargo.credits.get()
                sendTradeNotification(
                    "${fleet.name}: No profitable routes found",
                    "Min profit threshold: ${Misc.getDGSCredits(minProfit)}/day, Available credits: ${Misc.getDGSCredits(credits)}. Will continue searching...",
                    "ui_intel_log_update",
                    highlights = arrayOf(Misc.getDGSCredits(minProfit), Misc.getDGSCredits(credits))
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
        if (dist < TIHConfig.arrivalDistance || fleet.containingLocation == target.containingLocation && dist < TIHConfig.arrivalDistance * 3) {
            state = nextState
            saveStateToMemory()
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
        val usableCredits = playerCredits.get() * (TIHConfig.maxCreditsUsagePercent / 100f)
        val buyPrice = route.source.getSupplyPrice(route.commodityId, route.quantity.toDouble(), true)

        if (buyPrice > usableCredits) {
            // Can't afford - re-evaluate
            Global.getLogger(this::class.java).warn(
                "${fleet.name}: Trade exceeds configured credit usage cap (${Misc.getDGSCredits(usableCredits)}), re-evaluating"
            )
            state = TradeState.EVALUATING
            currentRoute = null
            saveStateToMemory()
            return
        }

        if (playerCredits.get() < buyPrice) {
            // Can't afford - re-evaluate
            Global.getLogger(this::class.java).warn("${fleet.name}: Can't afford trade, re-evaluating")
            state = TradeState.EVALUATING
            currentRoute = null
            saveStateToMemory()
            return
        }

        // Check if price changed significantly (more than 20% worse vs planned buy cost)
        if (route.expectedBuyCost > 0f) {
            if (buyPrice > route.expectedBuyCost * 1.2f) {
                Global.getLogger(this::class.java).warn(
                    "${fleet.name}: Buy price changed significantly, re-evaluating (was ~${route.expectedBuyCost.toInt()}, now ${buyPrice.toInt()})"
                )
                state = TradeState.EVALUATING
                currentRoute = null
                saveStateToMemory()
                return
            }
        }

        // Execute purchase
        playerCredits.subtract(buyPrice)
        fleet.cargo.addCommodity(route.commodityId, route.quantity.toFloat())

        // Store buy cost for profit calculation on sell
        fleet.memoryWithoutUpdate.set(MEM_KEY_BUY_COST, buyPrice)

        // Market economic impact (Nex-style cap: only consume currently excess stock)
        applyBuyImpact(route.source, route.commodityId, route.quantity.toFloat())

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Auto-resupply: top off supplies and fuel if running low
        autoResupply(route.source)

        // Notify player
        val intel = AutoTradeIntel.getOrCreate(fleet)
        intel.sendTradeUpdate(
            title = "${fleet.name}",
            market = route.source,
            goods = "Bought ${route.quantity} $commodityName",
            amount = Misc.getDGSCredits(buyPrice)
        )

        // Travel to destination immediately after buying
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
        val buyCost = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_BUY_COST)

        // Check if this trade would result in a significant loss (more than 10% of buy cost)
        val profit = sellPrice - buyCost
        if (profit < -buyCost * 0.1f) {
            Global.getLogger(this::class.java).warn("${fleet.name}: Trade would result in significant loss (${profit.toInt()}¢), aborting sale and re-evaluating")
            // Don't execute the trade, just clear cargo and re-evaluate
            // This is a loss but better than selling at a huge loss
            state = TradeState.EVALUATING
            currentRoute = null
            saveStateToMemory()
            return
        }

        // Execute sale
        fleet.cargo.removeCommodity(route.commodityId, sellQty)
        Global.getSector().playerFleet.cargo.credits.add(sellPrice)

        // Market economic impact (Nex-style cap: only satisfy current deficit)
        applySellImpact(route.dest, route.commodityId, sellQty)

        // Log if profit is negative
        if (profit < 0) {
            Global.getLogger(this::class.java).warn("${fleet.name}: Negative profit trade: bought for ${buyCost.toInt()}¢, sold for ${sellPrice.toInt()}¢, loss: ${(-profit).toInt()}¢")
        }
        val totalProfit = fleet.memoryWithoutUpdate.getFloat(MEM_KEY_TOTAL_PROFIT) + profit
        fleet.memoryWithoutUpdate.set(MEM_KEY_TOTAL_PROFIT, totalProfit)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_BUY_COST)

        // Record trade in intel
        AutoTradeIntel.getOrCreate(fleet).recordTrade(profit, route)

        val commodityName = Global.getSector().economy.getCommoditySpec(route.commodityId)?.name ?: route.commodityId

        // Notify player
        val intel = AutoTradeIntel.getOrCreate(fleet)
        intel.sendTradeUpdate(
            title = "${fleet.name}",
            market = route.dest,
            goods = "Sold ${sellQty.toInt()} $commodityName",
            amount = Misc.getDGSCredits(sellPrice),
            netProfit = Misc.getDGSCredits(profit),
            netProfitColor = if (profit >= 0f) Misc.getPositiveHighlightColor() else Misc.getNegativeHighlightColor()
        )

        // Attempt trade chaining immediately — avoid dead leg back to EVALUATING
        val chainRoute = TradeRouteCalculator.findBestRouteFrom(fleet, route.dest)
        if (chainRoute != null) {
            currentRoute = chainRoute
            saveRouteToMemory(chainRoute)
            state = TradeState.BUYING
            saveStateToMemory()
            buy()
            return
        }

        // No chain found — fall through to EVALUATING
        currentRoute = null
        state = TradeState.EVALUATING
        saveStateToMemory()
        updateTaskText("Auto-trading: evaluating routes")
    }

    private fun ensureAutoTradeTask() {
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet) ?: return
        val task = sf.routeAI?.currentTask ?: return

        // If Nexerelin assigned a different task (raid, patrol, etc.), suspend auto-trading
        // and let the fleet complete that task first
        if (task !is AutoTradeTask) {
            // Different task assigned - suspend auto-trading until task completes
            return
        }

        // Ensure task time is always high so route never expires and triggers despawn
        if (task.time < TIHConfig.taskTimeLimit * 0.1f) {
            task.time = TIHConfig.taskTimeLimit
        }
    }

    private fun saveStateToMemory() {
        fleet.memoryWithoutUpdate.set(MEM_KEY_STATE, state.name)
    }

    private fun applyAutoTradeSafetyFlags() {
        val mem = fleet.memoryWithoutUpdate
        Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, SAFETY_FLAG_REASON, true, -1f)
        Misc.setFlagWithReason(mem, MemFlags.FLEET_IGNORES_OTHER_FLEETS, SAFETY_FLAG_REASON, true, -1f)
        Misc.setFlagWithReason(mem, MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, SAFETY_FLAG_REASON, true, -1f)
    }

    private fun clearAutoTradeSafetyFlags() {
        val mem = fleet.memoryWithoutUpdate
        Misc.setFlagWithReason(mem, MemFlags.MEMORY_KEY_MAKE_NON_HOSTILE, SAFETY_FLAG_REASON, false, 0f)
        Misc.setFlagWithReason(mem, MemFlags.FLEET_IGNORES_OTHER_FLEETS, SAFETY_FLAG_REASON, false, 0f)
        Misc.setFlagWithReason(mem, MemFlags.FLEET_IGNORED_BY_OTHER_FLEETS, SAFETY_FLAG_REASON, false, 0f)
    }

    private fun saveRouteToMemory(route: TradeRoute) {
        fleet.memoryWithoutUpdate.set(MEM_KEY_COMMODITY, route.commodityId)
        fleet.memoryWithoutUpdate.set(MEM_KEY_QUANTITY, route.quantity)
        fleet.memoryWithoutUpdate.set(MEM_KEY_SOURCE_MARKET, route.source.id)
        fleet.memoryWithoutUpdate.set(MEM_KEY_DEST_MARKET, route.dest.id)
        fleet.memoryWithoutUpdate.set(MEM_KEY_EXPECTED_BUY_COST, route.expectedBuyCost)
    }

    private fun restoreRouteFromMemory(): TradeRoute? {
        val mem = fleet.memoryWithoutUpdate
        val commodityId = mem.getString(MEM_KEY_COMMODITY) ?: return null
        val quantity = mem.getFloat(MEM_KEY_QUANTITY).toInt()
        val sourceId = mem.getString(MEM_KEY_SOURCE_MARKET) ?: return null
        val destId = mem.getString(MEM_KEY_DEST_MARKET) ?: return null
        val expectedBuyCost = mem.getFloat(MEM_KEY_EXPECTED_BUY_COST)

        val economy = Global.getSector().economy
        val source = economy.getMarket(sourceId) ?: return null
        val dest = economy.getMarket(destId) ?: return null

        return TradeRoute(
            source = source,
            dest = dest,
            commodityId = commodityId,
            quantity = quantity,
            expectedProfit = 0f,
            estimatedDays = 0f,
            expectedBuyCost = expectedBuyCost
        )
    }

    fun cleanup() {
        clearAutoTradeSafetyFlags()
        fleet.memoryWithoutUpdate.unset(MEM_KEY_STATE)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_COMMODITY)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_QUANTITY)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_SOURCE_MARKET)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_DEST_MARKET)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_EXPECTED_BUY_COST)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_BUY_COST)
        fleet.memoryWithoutUpdate.unset(MEM_KEY_OFFLOADED)
        fleet.memoryWithoutUpdate.unset("\$tih_delay_callback")
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

        if (fleet.containingLocation == entity.containingLocation && dist < TIHConfig.arrivalDistance * 3) {
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

            // Market economic impact (Nex-style cap: only satisfy current deficit)
            applySellImpact(market, commodityId, qty)
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
            val intel = AutoTradeIntel.getOrCreate(fleet)
            intel.sendTradeUpdate(
                title = "${fleet.name}",
                market = market,
                goods = "Sold mixed commodities",
                amount = Misc.getDGSCredits(totalSellRevenue)
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
        if (totalProfit < TIHConfig.resupplyMinProfitBuffer) return

        // Resupply supplies if below threshold
        val maxSupplies = cargo.maxCapacity
        val currentSupplies = cargo.supplies
        val threshold = min(200f, maxSupplies * TIHConfig.resupplySuppliesThreshold)
        if (currentSupplies < threshold) {
            val neededSupplies = (threshold * TIHConfig.resupplySuppliesTarget).coerceAtLeast(0f)
            val supplyPrice = market.getSupplyPrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.SUPPLIES, neededSupplies.toDouble(), true)
            if (playerCredits.get() >= supplyPrice && neededSupplies > 0f) {
                playerCredits.subtract(supplyPrice)
                cargo.addSupplies(neededSupplies)
            }
        }

        // Resupply fuel if below threshold
        val maxFuel = cargo.maxFuel
        val currentFuel = cargo.fuel
        if (currentFuel < maxFuel * TIHConfig.resupplyFuelThreshold) {
            val neededFuel = (maxFuel * TIHConfig.resupplyFuelTarget - currentFuel).coerceAtLeast(0f)
            val fuelPrice = market.getSupplyPrice(com.fs.starfarer.api.impl.campaign.ids.Commodities.FUEL, neededFuel.toDouble(), true)
            if (playerCredits.get() >= fuelPrice && neededFuel > 0f) {
                playerCredits.subtract(fuelPrice)
                cargo.addFuel(neededFuel)
            }
        }
    }

    private fun applyBuyImpact(market: MarketAPI, commodityId: String, boughtQty: Float) {
        if (boughtQty <= 0f) return
        val comData = market.getCommodityData(commodityId)
        val excess = comData.excessQuantity.toFloat().coerceAtLeast(0f)
        val impactQty = min(boughtQty, excess)
        if (impactQty <= 0f) return

        // addTradeModMinus only applies negative quantity values.
        comData.addTradeModMinus(nextImpactSourceId("buy", commodityId), -impactQty, TIHConfig.tradeModDuration)
    }

    private fun applySellImpact(market: MarketAPI, commodityId: String, soldQty: Float) {
        if (soldQty <= 0f) return
        val comData = market.getCommodityData(commodityId)
        val deficit = comData.deficitQuantity.toFloat().coerceAtLeast(0f)
        val impactQty = min(soldQty, deficit)
        if (impactQty <= 0f) return

        comData.addTradeModPlus(nextImpactSourceId("sell", commodityId), impactQty, TIHConfig.tradeModDuration)
    }

    private fun nextImpactSourceId(action: String, commodityId: String): String {
        impactEventCounter += 1L
        return "${TRADE_MOD_ID}_${fleet.id}_${action}_${commodityId}_$impactEventCounter"
    }

    private fun sendTradeNotification(
        title: String,
        detail: String,
        soundId: String,
        highlights: Array<String> = emptyArray(),
        highlightColors: Array<Color> = emptyArray()
    ) {
        val intel = MessageIntel(title, Misc.getBasePlayerColor())
        if (highlights.isNotEmpty()) {
            val colors = if (highlightColors.size == highlights.size) {
                highlightColors
            } else {
                Array(highlights.size) { Misc.getHighlightColor() }
            }
            intel.addLine(detail, Misc.getTextColor(), highlights, *colors)
        } else {
            intel.addLine(detail, Misc.getTextColor())
        }
        intel.icon = InvisibleHandModPlugin.ICON_PATH
        intel.sound = soundId
        Global.getSector().campaignUI.addMessage(intel)
    }

    private fun sendTradeListNotification(
        title: String,
        market: MarketAPI,
        goods: String,
        transactionAmount: String,
        soundId: String,
        netProfit: String? = null,
        netProfitColor: Color = Misc.getPositiveHighlightColor()
    ) {
        val intel = MessageIntel(title, Misc.getBasePlayerColor())

        val marketColor = market.textColorForFactionOrPlanet ?: Misc.getHighlightColor()
        intel.addLine(
            BaseIntelPlugin.BULLET + "Market: %s",
            Misc.getTextColor(),
            arrayOf(market.name),
            marketColor
        )
        intel.addLine(
            BaseIntelPlugin.BULLET + "%s",
            Misc.getTextColor(),
            arrayOf(goods),
            Misc.getHighlightColor()
        )

        if (netProfit != null) {
            intel.addLine(
                BaseIntelPlugin.BULLET + "Transaction Amount: %s (profit: %s)",
                Misc.getTextColor(),
                arrayOf(transactionAmount, netProfit),
                Misc.getHighlightColor(),
                netProfitColor
            )
        } else {
            intel.addLine(
                BaseIntelPlugin.BULLET + "Transaction Amount: %s",
                Misc.getTextColor(),
                arrayOf(transactionAmount),
                Misc.getHighlightColor()
            )
        }

        intel.icon = InvisibleHandModPlugin.ICON_PATH
        intel.sound = soundId
        
        // Get or create the intel and add it to manager if not already added
        val autoTradeIntel = AutoTradeIntel.getOrCreate(fleet)
        if (!Global.getSector().intelManager.hasIntel(autoTradeIntel)) {
            Global.getSector().intelManager.addIntel(autoTradeIntel)
        }
        
        // Send clickable notification that opens the fleet's intel tab
        Global.getSector().campaignUI.addMessage(
            intel,
            CommMessageAPI.MessageClickAction.INTEL_TAB,
            autoTradeIntel
        )
    }

    private fun updateTaskText(text: String, from: SectorEntityToken? = null, to: SectorEntityToken? = null) {
        val sf = SpecialForcesIntel.getIntelFromMemory(fleet) ?: return
        val task = sf.routeAI?.currentTask
        if (task is AutoTradeTask) {
            task.tradeActionText = text
        }
        // Keep task entity in sync so Nexerelin's assignment AI always has a non-null entity
        if (task != null && to != null) {
            task.setEntity(to)
        }
        // Update route segment destinations
        val segment = sf.route?.current
        if (segment != null) {
            if (from != null) segment.from = from
            if (to != null) segment.to = to
            // Recalculate segment days if both from and to are set
            if (from != null && to != null) {
                val travelDays = com.fs.starfarer.api.impl.campaign.fleets.RouteLocationCalculator.getTravelDays(from, to)
                if (travelDays > 0) {
                    segment.daysMax = travelDays
                }
            }
        }
    }
}
