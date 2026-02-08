package theinvisiblehand

import com.fs.starfarer.api.Global
import org.json.JSONObject
import java.io.IOException

object TIHConfig {
    // Trade routing
    var minProfitPerDay = 100f
        private set
    var cacheRefreshDays = 1f
        private set

    // Trade quantity scaling
    var quantityScalingQuadratic = 10
        private set
    var quantityScalingLinear = 50
        private set

    // Economy scoring bonuses (percentage)
    var excessBonusMax = 0.05f  // 5% max bonus for source excess
        private set
    var deficitBonusMax = 0.075f  // 7.5% max bonus for dest deficit
        private set
    var disruptionBonus = 0.2f  // 20% bonus for disrupted markets
        private set

    // Market impact penalty
    var impactPenaltyMax = 0.5f  // 50% max penalty
        private set
    var impactThresholdMultiplier = 2f  // 2x quantity cap
        private set

    // Trade delays (days)
    var delayBeforeBuy = 1f
        private set
    var delayAfterBuy = 2f
        private set
    var delayBeforeSell = 1f
        private set
    var delayAfterSell = 3f
        private set

    // Auto-resupply
    var resupplyMinProfitBuffer = 5000f
        private set
    var resupplySuppliesThreshold = 0.5f  // 50% of max or 200, whichever is lower
        private set
    var resupplySuppliesTarget = 1.2f  // Refill to 120% of threshold
        private set
    var resupplyFuelThreshold = 0.5f  // 50% of max
        private set
    var resupplyFuelTarget = 0.75f  // Refill to 75% of max
        private set

    // Trade history
    var maxHistorySize = 50
        private set
    var displayHistoryCount = 10
        private set

    // Fleet behavior
    var tradeModDuration = 30f  // Days that trade impact lasts
        private set
    var arrivalDistance = 300f
        private set
    var taskTimeLimit = 999999f  // Prevents Nexerelin from expiring the route
        private set

    fun loadConfig() {
        try {
            val configJson = Global.getSettings().loadJSON("tih_config.json")

            // Trade routing
            minProfitPerDay = configJson.optDouble("minProfitPerDay", 100.0).toFloat()
            cacheRefreshDays = configJson.optDouble("cacheRefreshDays", 1.0).toFloat()

            // Trade quantity scaling
            val quantityScaling = configJson.optJSONObject("quantityScaling")
            if (quantityScaling != null) {
                quantityScalingQuadratic = quantityScaling.optInt("quadratic", 10)
                quantityScalingLinear = quantityScaling.optInt("linear", 50)
            }

            // Economy scoring
            val economyScoring = configJson.optJSONObject("economyScoring")
            if (economyScoring != null) {
                excessBonusMax = economyScoring.optDouble("excessBonusMax", 0.05).toFloat()
                deficitBonusMax = economyScoring.optDouble("deficitBonusMax", 0.075).toFloat()
                disruptionBonus = economyScoring.optDouble("disruptionBonus", 0.2).toFloat()
            }

            // Market impact penalty
            val impactPenalty = configJson.optJSONObject("impactPenalty")
            if (impactPenalty != null) {
                impactPenaltyMax = impactPenalty.optDouble("maxPenalty", 0.5).toFloat()
                impactThresholdMultiplier = impactPenalty.optDouble("thresholdMultiplier", 2.0).toFloat()
            }

            // Trade delays
            val delays = configJson.optJSONObject("delays")
            if (delays != null) {
                delayBeforeBuy = delays.optDouble("beforeBuy", 1.0).toFloat()
                delayAfterBuy = delays.optDouble("afterBuy", 2.0).toFloat()
                delayBeforeSell = delays.optDouble("beforeSell", 1.0).toFloat()
                delayAfterSell = delays.optDouble("afterSell", 3.0).toFloat()
            }

            // Auto-resupply
            val resupply = configJson.optJSONObject("resupply")
            if (resupply != null) {
                resupplyMinProfitBuffer = resupply.optDouble("minProfitBuffer", 5000.0).toFloat()
                resupplySuppliesThreshold = resupply.optDouble("suppliesThreshold", 0.5).toFloat()
                resupplySuppliesTarget = resupply.optDouble("suppliesTarget", 1.2).toFloat()
                resupplyFuelThreshold = resupply.optDouble("fuelThreshold", 0.5).toFloat()
                resupplyFuelTarget = resupply.optDouble("fuelTarget", 0.75).toFloat()
            }

            // Trade history
            val history = configJson.optJSONObject("history")
            if (history != null) {
                maxHistorySize = history.optInt("maxSize", 50)
                displayHistoryCount = history.optInt("displayCount", 10)
            }

            // Fleet behavior
            val fleetBehavior = configJson.optJSONObject("fleetBehavior")
            if (fleetBehavior != null) {
                tradeModDuration = fleetBehavior.optDouble("tradeModDuration", 30.0).toFloat()
                arrivalDistance = fleetBehavior.optDouble("arrivalDistance", 300.0).toFloat()
                taskTimeLimit = fleetBehavior.optDouble("taskTimeLimit", 999999.0).toFloat()
            }

            Global.getLogger(TIHConfig::class.java).info("The Invisible Hand config loaded successfully")
        } catch (e: IOException) {
            Global.getLogger(TIHConfig::class.java).warn("Failed to load tih_config.json, using defaults: ${e.message}")
        } catch (e: Exception) {
            Global.getLogger(TIHConfig::class.java).error("Error parsing tih_config.json: ${e.message}", e)
        }
    }
}
