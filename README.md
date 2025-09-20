# ElytraHorsepower (Paper 1.21.1) – v1.4.0

Build: `mvn -q -DskipTests package`

## Features
- Altitude-based thrust efficiency that weakens at high Y-levels
- Boost and eco flight modes triggered by left and right click
- Up to three boost consumables with per-item duration, thrust, fuel, and life multipliers
- Default boosts
  - Redstone ×1 → +25% thrust, +40% fuel usage, +45% life usage for 6 s
  - Glowstone Dust ×4 → +40% thrust, +80% fuel usage, +115% life usage for 5 s
  - Blaze Powder ×8 → +70% thrust, +140% fuel usage, +225% life usage for 7 s
- Zone-specific drag, fuel, and speed cap modifiers
- G-force warnings and configurable damage threshold via `gforce.damage_start_g`
- `/elytrahp info` command showing speed, fuel, life, and mode
- Lapis-block engine repairs with configurable lifespan limits
- Sneak-right-click bulk fuel charging, limited by `fuel.max_sets_per_click`
- Optional neutralization of vanilla air and elytra drag via the `vanilla` config section
- Action-bar HUD showing fuel and remaining life while gliding
