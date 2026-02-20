package theinvisiblehand

import com.fs.starfarer.api.Global
import lunalib.lunaSettings.LunaSettings
import lunalib.lunaSettings.LunaSettingsListener

object TIHLunaConfig {
    private const val MOD_ID = "the_invisible_hand"
    private val logger = Global.getLogger(TIHLunaConfig::class.java)

    private const val TAB_ROUTING = "Trade Routing"
    private const val TAB_SCORING = "Economy Scoring"
    private const val TAB_RESUPPLY = "Auto-Resupply"
    private const val TAB_HISTORY = "History & Behavior"

    private const val KEY_MIN_PROFIT_PER_DAY = "tih_minProfitPerDay"
    private const val KEY_CACHE_REFRESH_DAYS = "tih_cacheRefreshDays"
    private const val KEY_MAX_CREDITS_USAGE_PERCENT = "tih_maxCreditsUsagePercent"

    private const val KEY_QUANTITY_SCALING_QUADRATIC = "tih_quantityScalingQuadratic"
    private const val KEY_QUANTITY_SCALING_LINEAR = "tih_quantityScalingLinear"

    private const val KEY_EXCESS_BONUS_MAX = "tih_excessBonusMax"
    private const val KEY_DEFICIT_BONUS_MAX = "tih_deficitBonusMax"
    private const val KEY_DISRUPTION_BONUS = "tih_disruptionBonus"

    private const val KEY_RESUPPLY_MIN_PROFIT_BUFFER = "tih_resupplyMinProfitBuffer"
    private const val KEY_RESUPPLY_SUPPLIES_THRESHOLD = "tih_resupplySuppliesThreshold"
    private const val KEY_RESUPPLY_SUPPLIES_TARGET = "tih_resupplySuppliesTarget"
    private const val KEY_RESUPPLY_FUEL_THRESHOLD = "tih_resupplyFuelThreshold"
    private const val KEY_RESUPPLY_FUEL_TARGET = "tih_resupplyFuelTarget"

    private const val KEY_MAX_HISTORY_SIZE = "tih_maxHistorySize"
    private const val KEY_DISPLAY_HISTORY_COUNT = "tih_displayHistoryCount"
    private const val KEY_TRADE_MOD_DURATION = "tih_tradeModDuration"
    private const val KEY_ARRIVAL_DISTANCE = "tih_arrivalDistance"
    private const val KEY_TASK_TIME_LIMIT = "tih_taskTimeLimit"

    private val listener = TIHLunaSettingsListener()

    fun initializeAndLoad() {
        try {
            registerSettings()
            applyLunaValues()
            ensureListenerRegistered()
            logger.info("LunaLib configuration initialized")
        } catch (ex: Exception) {
            logger.error("Failed to initialize LunaLib configuration: ${ex.message}", ex)
        }
    }

    private fun registerSettings() {
        LunaSettings.SettingsCreator.addHeader(MOD_ID, "tih_header_routing", "Trade Routing", TAB_ROUTING)
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_MIN_PROFIT_PER_DAY,
            "Minimum Profit Per Day",
            "Minimum route profitability required before a trade run is accepted.",
            TIHConfig.minProfitPerDay.toDouble(),
            0.0,
            10000.0,
            TAB_ROUTING
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_CACHE_REFRESH_DAYS,
            "Cache Refresh Days",
            "How often route market data cache is refreshed.",
            TIHConfig.cacheRefreshDays.toDouble(),
            0.05,
            30.0,
            TAB_ROUTING
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_MAX_CREDITS_USAGE_PERCENT,
            "Max Credits Usage Percent",
            "Maximum percent of player credits that auto-trading can allocate.",
            TIHConfig.maxCreditsUsagePercent.toDouble(),
            0.0,
            100.0,
            TAB_ROUTING
        )
        LunaSettings.SettingsCreator.addInt(
            MOD_ID,
            KEY_QUANTITY_SCALING_QUADRATIC,
            "Quantity Scaling Quadratic",
            "Quadratic component of market size to quantity scaling.",
            TIHConfig.quantityScalingQuadratic,
            0,
            500,
            TAB_ROUTING
        )
        LunaSettings.SettingsCreator.addInt(
            MOD_ID,
            KEY_QUANTITY_SCALING_LINEAR,
            "Quantity Scaling Linear",
            "Linear component of market size to quantity scaling.",
            TIHConfig.quantityScalingLinear,
            0,
            2000,
            TAB_ROUTING
        )

        LunaSettings.SettingsCreator.addHeader(MOD_ID, "tih_header_scoring", "Economy Scoring", TAB_SCORING)
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_EXCESS_BONUS_MAX,
            "Excess Bonus Max",
            "Maximum score bonus when buying from excess supply markets.",
            TIHConfig.excessBonusMax.toDouble(),
            0.0,
            2.0,
            TAB_SCORING
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_DEFICIT_BONUS_MAX,
            "Deficit Bonus Max",
            "Maximum score bonus when selling to deficit markets.",
            TIHConfig.deficitBonusMax.toDouble(),
            0.0,
            2.0,
            TAB_SCORING
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_DISRUPTION_BONUS,
            "Disruption Bonus",
            "Additional score bonus for disrupted markets.",
            TIHConfig.disruptionBonus.toDouble(),
            0.0,
            2.0,
            TAB_SCORING
        )

        LunaSettings.SettingsCreator.addHeader(MOD_ID, "tih_header_resupply", "Auto-Resupply", TAB_RESUPPLY)
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_RESUPPLY_MIN_PROFIT_BUFFER,
            "Resupply Min Profit Buffer",
            "Minimum accumulated profit before resupply spending is allowed.",
            TIHConfig.resupplyMinProfitBuffer.toDouble(),
            0.0,
            500000.0,
            TAB_RESUPPLY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_RESUPPLY_SUPPLIES_THRESHOLD,
            "Supplies Threshold",
            "Resupply triggers below this supplies fraction.",
            TIHConfig.resupplySuppliesThreshold.toDouble(),
            0.0,
            3.0,
            TAB_RESUPPLY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_RESUPPLY_SUPPLIES_TARGET,
            "Supplies Target",
            "Refill target as supplies fraction after purchasing.",
            TIHConfig.resupplySuppliesTarget.toDouble(),
            0.0,
            5.0,
            TAB_RESUPPLY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_RESUPPLY_FUEL_THRESHOLD,
            "Fuel Threshold",
            "Resupply triggers below this fuel fraction.",
            TIHConfig.resupplyFuelThreshold.toDouble(),
            0.0,
            3.0,
            TAB_RESUPPLY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_RESUPPLY_FUEL_TARGET,
            "Fuel Target",
            "Refill target as fuel fraction after purchasing.",
            TIHConfig.resupplyFuelTarget.toDouble(),
            0.0,
            5.0,
            TAB_RESUPPLY
        )

        LunaSettings.SettingsCreator.addHeader(MOD_ID, "tih_header_history_behavior", "History & Behavior", TAB_HISTORY)
        LunaSettings.SettingsCreator.addInt(
            MOD_ID,
            KEY_MAX_HISTORY_SIZE,
            "Max History Size",
            "Maximum number of trade records stored.",
            TIHConfig.maxHistorySize,
            1,
            1000,
            TAB_HISTORY
        )
        LunaSettings.SettingsCreator.addInt(
            MOD_ID,
            KEY_DISPLAY_HISTORY_COUNT,
            "Display History Count",
            "Number of records shown in intel view.",
            TIHConfig.displayHistoryCount,
            1,
            1000,
            TAB_HISTORY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_TRADE_MOD_DURATION,
            "Trade Mod Duration",
            "Days that trade impact remains active.",
            TIHConfig.tradeModDuration.toDouble(),
            0.0,
            365.0,
            TAB_HISTORY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_ARRIVAL_DISTANCE,
            "Arrival Distance",
            "Distance threshold for considering fleet arrival at market.",
            TIHConfig.arrivalDistance.toDouble(),
            0.0,
            5000.0,
            TAB_HISTORY
        )
        LunaSettings.SettingsCreator.addDouble(
            MOD_ID,
            KEY_TASK_TIME_LIMIT,
            "Task Time Limit",
            "Upper bound for special task group assignment duration.",
            TIHConfig.taskTimeLimit.toDouble(),
            1.0,
            9999999.0,
            TAB_HISTORY
        )

        LunaSettings.SettingsCreator.refresh(MOD_ID)
    }

    private fun applyLunaValues() {
        TIHConfig.minProfitPerDay = LunaSettings.getDouble(MOD_ID, KEY_MIN_PROFIT_PER_DAY)?.toFloat() ?: TIHConfig.minProfitPerDay
        TIHConfig.cacheRefreshDays = LunaSettings.getDouble(MOD_ID, KEY_CACHE_REFRESH_DAYS)?.toFloat() ?: TIHConfig.cacheRefreshDays
        TIHConfig.maxCreditsUsagePercent = LunaSettings.getDouble(MOD_ID, KEY_MAX_CREDITS_USAGE_PERCENT)?.toFloat() ?: TIHConfig.maxCreditsUsagePercent

        TIHConfig.quantityScalingQuadratic = LunaSettings.getInt(MOD_ID, KEY_QUANTITY_SCALING_QUADRATIC) ?: TIHConfig.quantityScalingQuadratic
        TIHConfig.quantityScalingLinear = LunaSettings.getInt(MOD_ID, KEY_QUANTITY_SCALING_LINEAR) ?: TIHConfig.quantityScalingLinear

        TIHConfig.excessBonusMax = LunaSettings.getDouble(MOD_ID, KEY_EXCESS_BONUS_MAX)?.toFloat() ?: TIHConfig.excessBonusMax
        TIHConfig.deficitBonusMax = LunaSettings.getDouble(MOD_ID, KEY_DEFICIT_BONUS_MAX)?.toFloat() ?: TIHConfig.deficitBonusMax
        TIHConfig.disruptionBonus = LunaSettings.getDouble(MOD_ID, KEY_DISRUPTION_BONUS)?.toFloat() ?: TIHConfig.disruptionBonus

        TIHConfig.resupplyMinProfitBuffer = LunaSettings.getDouble(MOD_ID, KEY_RESUPPLY_MIN_PROFIT_BUFFER)?.toFloat() ?: TIHConfig.resupplyMinProfitBuffer
        TIHConfig.resupplySuppliesThreshold = LunaSettings.getDouble(MOD_ID, KEY_RESUPPLY_SUPPLIES_THRESHOLD)?.toFloat() ?: TIHConfig.resupplySuppliesThreshold
        TIHConfig.resupplySuppliesTarget = LunaSettings.getDouble(MOD_ID, KEY_RESUPPLY_SUPPLIES_TARGET)?.toFloat() ?: TIHConfig.resupplySuppliesTarget
        TIHConfig.resupplyFuelThreshold = LunaSettings.getDouble(MOD_ID, KEY_RESUPPLY_FUEL_THRESHOLD)?.toFloat() ?: TIHConfig.resupplyFuelThreshold
        TIHConfig.resupplyFuelTarget = LunaSettings.getDouble(MOD_ID, KEY_RESUPPLY_FUEL_TARGET)?.toFloat() ?: TIHConfig.resupplyFuelTarget

        TIHConfig.maxHistorySize = LunaSettings.getInt(MOD_ID, KEY_MAX_HISTORY_SIZE) ?: TIHConfig.maxHistorySize
        TIHConfig.displayHistoryCount = LunaSettings.getInt(MOD_ID, KEY_DISPLAY_HISTORY_COUNT) ?: TIHConfig.displayHistoryCount
        TIHConfig.tradeModDuration = LunaSettings.getDouble(MOD_ID, KEY_TRADE_MOD_DURATION)?.toFloat() ?: TIHConfig.tradeModDuration
        TIHConfig.arrivalDistance = LunaSettings.getDouble(MOD_ID, KEY_ARRIVAL_DISTANCE)?.toFloat() ?: TIHConfig.arrivalDistance
        TIHConfig.taskTimeLimit = LunaSettings.getDouble(MOD_ID, KEY_TASK_TIME_LIMIT)?.toFloat() ?: TIHConfig.taskTimeLimit

        TIHConfig.validateAndNormalize()
        logger.info("Applied LunaLib configuration values")
    }

    private fun ensureListenerRegistered() {
        if (!LunaSettings.hasSettingsListenerOfClass(TIHLunaSettingsListener::class.java)) {
            LunaSettings.addSettingsListener(listener)
        }
    }

    private class TIHLunaSettingsListener : LunaSettingsListener {
        override fun settingsChanged(modID: String) {
            if (modID != MOD_ID) {
                return
            }
            applyLunaValues()
        }
    }
}
