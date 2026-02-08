================================================================================
  THE INVISIBLE HAND - CONFIGURATION GUIDE
================================================================================

This file explains all configuration options in tih_config.json.
Edit that file to customize the mod's behavior to your preferences.

================================================================================
TRADE ROUTING
================================================================================

minProfitPerDay (default: 100)
  - Minimum profit per day for a route to be considered
  - Higher = more selective, only takes very profitable routes
  - Lower = less selective, will take marginal routes
  - Recommended range: 50-500

cacheRefreshDays (default: 1)
  - How often (in game days) to refresh market price data
  - Lower = more CPU usage but more accurate prices
  - Higher = less CPU usage but may miss price changes
  - Recommended range: 0.5-3

================================================================================
QUANTITY SCALING
================================================================================

Formula: (marketSize^2 * quadratic) + (marketSize * linear)

quadratic (default: 10)
  - Quadratic component of trade quantity scaling
  - Higher = larger markets can handle much bigger trades
  - Lower = more uniform trade sizes across market sizes

linear (default: 50)
  - Linear component of trade quantity scaling
  - Higher = all markets can handle bigger trades
  - Lower = smaller base trade sizes

Examples with defaults (quadratic=10, linear=50):
  - Size 3 markets: 200 units max
  - Size 5 markets: 400 units max
  - Size 7 markets: 700 units max
  - Size 10 markets: 1300 units max

================================================================================
ECONOMY SCORING BONUSES
================================================================================

These are decimal multipliers (0.05 = 5% bonus).

excessBonusMax (default: 0.05)
  - Max bonus for buying from markets with excess supply
  - Encourages fleets to help clear supply gluts
  - Scales with excess quantity (10+ excess = full bonus)

deficitBonusMax (default: 0.075)
  - Max bonus for selling to markets with deficit demand
  - Encourages fleets to help satisfy shortages
  - Scales with deficit quantity (10+ deficit = full bonus)

disruptionBonus (default: 0.2)
  - Flat bonus for selling to disrupted/blockaded markets
  - Helps struggling markets recover faster
  - Applied in addition to deficit bonuses

Total possible bonus: ~32.5% if all conditions are met

================================================================================
MARKET IMPACT PENALTY
================================================================================

Discourages over-trading the same routes.

maxPenalty (default: 0.5)
  - Maximum penalty applied to over-traded routes
  - 0.5 = up to 50% reduction in route score
  - Prevents route score from going negative

thresholdMultiplier (default: 2.0)
  - Controls how quickly penalty scales
  - Penalty = (total_impact) / (quantity_cap * this_value)
  - 2.0 means penalty hits max at 2x the quantity cap worth of impact
  - Lower = penalty applies sooner (more route diversity)
  - Higher = penalty applies later (less route diversity)

Example: With 2.0 multiplier, a route with quantity cap of 400:
  - 400 total impact = 25% penalty
  - 800 total impact = 50% penalty (max)

================================================================================
TRADE DELAYS
================================================================================

All values in game days. Total delay per trade cycle = sum of all four.

beforeBuy (default: 1)
  - Delay when arriving at buy market
  - Represents: docking, customs, finding suppliers, negotiating

afterBuy (default: 2)
  - Delay after purchase before departing
  - Represents: loading cargo, inspections, paperwork

beforeSell (default: 1)
  - Delay when arriving at sell market
  - Represents: docking, customs, finding buyers, negotiating

afterSell (default: 3)
  - Delay after sale before next action
  - Represents: unloading cargo, payment processing, crew rest

Total default: 7 days per round trip

Balance recommendations:
  - Too fast: Set all to 0-0.5 days (very profitable, possibly unbalanced)
  - Default: Current values (balanced for vanilla economy)
  - Slower: Increase afterBuy and afterSell to 3-5 days each
  - Very slow: Set all to 3-5 days (most realistic, lowest profit/day)

================================================================================
AUTO-RESUPPLY
================================================================================

Trade fleets automatically refill supplies/fuel at buy markets.

minProfitBuffer (default: 5000)
  - Only resupply if total profit exceeds this amount
  - Prevents spending money before turning a profit
  - Set to 0 to always resupply (may go into debt early)

suppliesThreshold (default: 0.5)
  - Resupply when supplies drop below this fraction of capacity
  - 0.5 = resupply at 50% or lower

suppliesTarget (default: 1.2)
  - Refill to this multiple of threshold
  - 1.2 with threshold 0.5 = refill to 60% of max

fuelThreshold (default: 0.5)
  - Resupply when fuel drops below this fraction of capacity

fuelTarget (default: 0.75)
  - Refill to this fraction of max fuel capacity

Note: Supplies also have a hard cap of 200 units minimum threshold.

================================================================================
TRADE HISTORY
================================================================================

maxSize (default: 50)
  - Maximum trades to keep in history
  - Older trades are deleted when limit is reached
  - Affects save file size and analytics accuracy

displayCount (default: 10)
  - Number of recent trades to show in intel panel
  - Only affects UI, doesn't impact data collection

================================================================================
FLEET BEHAVIOR (ADVANCED)
================================================================================

tradeModDuration (default: 30)
  - Days that market trade impact persists
  - Affects how long penalty lingers after trading at a market
  - Vanilla game uses 30 days for most economy mods

arrivalDistance (default: 300)
  - Distance in units to consider "arrived" at market
  - Lower = must get closer before executing trades
  - Higher = executes trades from further away

taskTimeLimit (default: 999999)
  - Prevents Nexerelin from expiring the auto-trade task
  - Don't change unless you know what you're doing
  - Too low and fleet will despawn/get reassigned

================================================================================
TIPS AND RECOMMENDATIONS
================================================================================

For MORE profit:
  - Lower minProfitPerDay to 50-75
  - Increase quantity scaling (quadratic to 15-20)
  - Reduce all delays to 0.5-1 day
  - Increase economy bonuses (0.1, 0.15, 0.3)

For LESS profit (more balanced):
  - Raise minProfitPerDay to 200-300
  - Keep quantity scaling default or lower (quadratic to 5-8)
  - Increase all delays to 2-4 days each
  - Keep economy bonuses default or lower

For route DIVERSITY:
  - Lower impactPenalty thresholdMultiplier to 1.0-1.5
  - Raise maxPenalty to 0.6-0.7
  - Fleet will switch routes more frequently

For route STABILITY:
  - Raise impactPenalty thresholdMultiplier to 3.0-4.0
  - Lower maxPenalty to 0.3-0.4
  - Fleet will stick to proven routes longer

================================================================================

After editing tih_config.json, restart Starsector for changes to take effect.
The config is loaded when the game starts, not when you load a save.

For questions or issues, report at:
https://github.com/anthropics/claude-code/issues (or your mod forum)

================================================================================
