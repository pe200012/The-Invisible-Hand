package theinvisiblehand

import com.fs.starfarer.api.Global
import org.json.JSONObject
import java.io.IOException

object TIHConfig {
    // Trade routing
    var minProfitPerDay = 100f
        internal set
    var cacheRefreshDays = 1f
        internal set
    var maxCreditsUsagePercent = 100f
        internal set

    // Trade quantity scaling
    var quantityScalingQuadratic = 10
        internal set
    var quantityScalingLinear = 50
        internal set

    // Economy scoring bonuses (percentage)
    var excessBonusMax = 0.05f  // 5% max bonus for source excess
        internal set
    var deficitBonusMax = 0.075f  // 7.5% max bonus for dest deficit
        internal set
    var disruptionBonus = 0.2f  // 20% bonus for disrupted markets
        internal set

    // Market impact penalty
    var impactPenaltyMax = 0.5f  // 50% max penalty
        internal set
    var impactThresholdMultiplier = 2f  // 2x quantity cap
        internal set

    // Auto-resupply
    var resupplyMinProfitBuffer = 5000f
        internal set
    var resupplySuppliesThreshold = 0.5f  // 50% of max or 200, whichever is lower
        internal set
    var resupplySuppliesTarget = 1.2f  // Refill to 120% of threshold
        internal set
    var resupplyFuelThreshold = 0.5f  // 50% of max
        internal set
    var resupplyFuelTarget = 0.75f  // Refill to 75% of max
        internal set

    // Trade history
    var maxHistorySize = 50
        internal set
    var displayHistoryCount = 10
        internal set

    // Fleet behavior
    var tradeModDuration = 30f  // Days that trade impact lasts
        internal set
    var arrivalDistance = 300f
        internal set
    var taskTimeLimit = 999999f  // Prevents Nexerelin from expiring the route
        internal set

    fun validateAndNormalize() {
        cacheRefreshDays = cacheRefreshDays.coerceAtLeast(0.05f)
        maxCreditsUsagePercent = maxCreditsUsagePercent.coerceIn(0f, 100f)

        quantityScalingQuadratic = quantityScalingQuadratic.coerceAtLeast(0)
        quantityScalingLinear = quantityScalingLinear.coerceAtLeast(0)

        excessBonusMax = excessBonusMax.coerceAtLeast(0f)
        deficitBonusMax = deficitBonusMax.coerceAtLeast(0f)
        disruptionBonus = disruptionBonus.coerceAtLeast(0f)

        impactPenaltyMax = impactPenaltyMax.coerceAtLeast(0f)
        impactThresholdMultiplier = impactThresholdMultiplier.coerceAtLeast(0.1f)

        resupplyMinProfitBuffer = resupplyMinProfitBuffer.coerceAtLeast(0f)
        resupplySuppliesThreshold = resupplySuppliesThreshold.coerceAtLeast(0f)
        resupplySuppliesTarget = resupplySuppliesTarget.coerceAtLeast(resupplySuppliesThreshold)
        resupplyFuelThreshold = resupplyFuelThreshold.coerceAtLeast(0f)
        resupplyFuelTarget = resupplyFuelTarget.coerceAtLeast(resupplyFuelThreshold)

        maxHistorySize = maxHistorySize.coerceAtLeast(1)
        displayHistoryCount = displayHistoryCount.coerceIn(1, maxHistorySize)

        tradeModDuration = tradeModDuration.coerceAtLeast(0f)
        arrivalDistance = arrivalDistance.coerceAtLeast(0f)
        taskTimeLimit = taskTimeLimit.coerceAtLeast(1f)
    }

    fun loadConfig() {
        try {
            val configPath = "data/config/tih_config.json"
            val fallbackPath = "tih_config.json"
            val configJson = try {
                Global.getSettings().loadJSON(configPath)
            } catch (primary: RuntimeException) {
                // Backward compatibility for legacy package layouts.
                try {
                    Global.getSettings().loadJSON(fallbackPath)
                } catch (_: RuntimeException) {
                    Global.getLogger(TIHConfig::class.java).warn(
                        "Config not found at $configPath or $fallbackPath, using defaults"
                    )
                    return
                }
            }

            // Trade routing
            minProfitPerDay = configJson.optDouble("minProfitPerDay", 100.0).toFloat()
            cacheRefreshDays = configJson.optDouble("cacheRefreshDays", 1.0).toFloat()
            maxCreditsUsagePercent = configJson.optDouble("maxCreditsUsagePercent", 100.0).toFloat()

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

            validateAndNormalize()
            Global.getLogger(TIHConfig::class.java).info("The Invisible Hand config loaded successfully")
        } catch (e: IOException) {
            Global.getLogger(TIHConfig::class.java).warn("Failed to load data/config/tih_config.json, using defaults: ${e.message}")
        } catch (e: Exception) {
            Global.getLogger(TIHConfig::class.java).error("Error parsing data/config/tih_config.json: ${e.message}", e)
        }
    }
}
