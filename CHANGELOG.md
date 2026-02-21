# Changelog

## v1.0.0
### Added
- Optional LunaLib integration: in-game configuration UI with live updates
- Version Checker metadata (`.version` + `version_files.csv`) for update notifications
- Credit usage cap: limit the percent of player credits/exposure used for auto-trading
- Multi-hop trade planning (beam search lookahead) with uncertainty discount for long travel legs
- Automatic adaptation: mid-travel revalidation, replanning, and salvage reroute when a sale would be a large loss
- Multi-fleet coordination:
  - Market-flow reservations (excess/deficit) to reduce route collisions between trade fleets
  - Global trading budget/exposure cap across fleets
  - Fair round-robin budget queue to prevent starvation
  - Global planning throttle to avoid CPU spikes when many fleets replan
- Plan persistence: saves `currentPlan` + `planIndex` to fleet memory and restores after save/load

### Changed
- Route selection now primarily follows real market flow (excess at source, deficit at destination) and planning-time reservations
- Route search performance tuned: bounded top-K sources/destinations per commodity + cached travel-time/distance lookups
- Trade notifications formatting improved for readability

### Fixed
- Trade impact application aligned with vanilla/Nex-style semantics (correct sign + caps by excess/deficit)
- Consistent quantity cap scaling across route search paths (config-driven)

### Deprecated
- Route impact-penalty settings are no longer used for route selection (kept only for backward config compatibility)

---

## v0.1.0
### Added
- Initial release: auto-trading for Nexerelin Special Task Groups / detachments
- Single-leg route evaluation with greedy chaining after each sale
- JSON configuration (`data/config/tih_config.json`) for tuning route scoring and behavior
- Auto-resupply and cargo offload handling
- Trade intel/history logging and player notifications

### Notes
- Requires Nexerelin + LazyLib (and Kotlin runtime as shipped/required by the mod setup)
