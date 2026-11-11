![Duskweaver Banner](assets/header2.png)

# Duskweaver

**Weave time to your will.** A configurable day/night, sleeping, and optional Nightmare encounter plugin for Minecraft Paper.

Duskweaver provides independent day and night durations, multiplayer sleep management, weather clearing after successful sleep skips, and a configurable three-stage encounter inspired by Minecraft's early unsafe-bed ambushes.

## Features

### Day, night, and sleeping

- Set independent real-world day and night durations (default: **15-minute day / 5-minute night**, assuming 20 TPS).
- Restrict time management to selected worlds.
- Choose one-player sleep, majority voting, or the original vanilla sleep threshold.
- Configure a cancellable sleep countdown (default: **10 seconds**).
- Exclude spectators and optionally inactive players from sleep quorum calculations.
- Optionally clear rain, snow, or thunderstorms after a successful sleep skip and enforce a minimum clear-weather interval.
- Allow daytime thunderstorm sleeping to clear weather without advancing the clock to morning.
- Restore original daylight-cycle and sleeping-percentage gamerules on a clean shutdown.

### Nightmares (optional)

Sleeping in an unsafe bed can trigger a Nightmare. The plugin checks for a suitable hostile spawn location and then rolls the configured encounter chance (**5% by default**). Safe beds do not intentionally trigger encounters.

- Fight **three consecutive enemy tiers** in one encounter.
- Configure the hostile mob pool, health, and equipment for each tier.
- Tier 1 and Tier 2 default to the name **Nightmare Shadow**; Tier 3 combines configurable names and titles (10 of each by default, for 100 possible combinations).
- Configure the introductory delay, announcements, cosmetic spawn explosions, and beacon-like visual beam.
- Display a boss bar for the final tier.
- Earn weighted rewards after defeating each tier: **one distinct reward in Tier 1, two in Tier 2, and three in Tier 3** by default.
- Deliver rewards **directly to the player's inventory**; only items that do not fit are dropped nearby.
- Apply configurable reward-quantity multipliers for Normal and Hard difficulty. Nightmares are disabled on Easy and Peaceful.
- Prevent dawn while an encounter is pending or active; the affected world's clock resumes afterward.
- Clean up interrupted encounters and tagged orphan entities following restarts or crashes. Interrupted encounters are not treated as player defeats.
- Store Nightmare cooldown/progress information in `plugins/Duskweaver/nightmare-progress.yml`.

Nightmares have a configurable timeout and per-player cooldown. The supplied default configuration uses a **300-second encounter timeout** and **240-minute cooldown**.

## Requirements

- **Server:** Paper 26.3 (target API: `26.3.build.244-stable`)
- **Java:** 25
- **Build system:** Maven
- **Folia:** Not supported

## Installation

1. Build the project or obtain the compiled plugin JAR.
2. Place **one** Duskweaver JAR in the server's `plugins/` directory, removing older versions of the same plugin.
3. Start the server to generate `plugins/Duskweaver/config.yml`.
4. Edit the configuration and run `/duskweaver reload` (or `/dw reload`) to apply supported changes.

### Build from source

```bash
mvn clean package
```

The resulting JAR is written to the Maven `target/` directory.

## Configuration

All settings and comments are in [`src/main/resources/config.yml`](src/main/resources/config.yml). The repository's default configuration includes the project's customized Nightmare monster pools, rewards, and names. Existing installations keep their own generated configuration; new defaults are **not automatically merged** into an existing `plugins/Duskweaver/config.yml`.

Common settings:

```yaml
day-length-minutes: 15.0
night-length-minutes: 5.0

worlds:
  - world

sleep:
  enabled: true
  one-player-sleep: true
  majority-vote: false
  countdown-seconds: 10
  clear-weather-on-skip: true
  clear-weather-duration-minutes: 10

nightmares:
  enabled: true
  spawn-chance-percent: 5.0
  cooldown-minutes: 240
  encounter-timeout-seconds: 300
```

The example above is abbreviated; consult the full configuration for monster pools, equipment, naming, loot tables, visual effects, and broadcast controls.

### Tier 3 names

Under `nightmares.tiers.3`, customize the `names` and `titles` lists. One entry from each list is selected to create the final monster's display name. Tier 1 and Tier 2 use their own `display-name` settings. The full defaults are documented in `config.yml`.

### Sleep modes

- `one-player-sleep: true`, `majority-vote: false`: one player can initiate a skip.
- `one-player-sleep: false`, `majority-vote: true`: a strict majority of eligible players is required, with the two-player exception.
- Both false: use the world's original vanilla sleeping threshold, while retaining Duskweaver's countdown.
- Both true: invalid configuration.

Sleeping players always count as active; spectators are excluded. Inactivity detection is approximate and does not integrate with third-party AFK plugins.

## Commands and permissions

| Command | Description | Permission |
| --- | --- | --- |
| `/duskweaver reload` | Reload plugin configuration | `duskweaver.reload` |
| `/dw reload` | Short alias for reload | `duskweaver.reload` |

The reload permission defaults to server operators.

## Operational notes

- Time durations depend on server tick rate; sustained lag affects real-world timing.
- Duskweaver is not intended to run alongside another plugin that independently controls the same world's time or sleeping gamerules.
- Nightmares use an approximation of unsafe-bed accessibility, not Minecraft's full mob pathfinding.
- Encounter recovery **cleans up** interrupted fights; it does not resume them after a restart.
- On an unexpected shutdown, cleanup callbacks cannot run; orphaned tagged entities are removed when their chunks load after restart.
- Rewards follow the administrator's configured quantities and weights. Large values are intentionally not capped by the plugin.
- Back up the configuration and Nightmare progress file before major upgrades.

## Project information

- **Author:** iruixos
- **Platform:** Paper
- **Java package:** `com.iruixos.duskweaver`
- **Development:** Includes AI-assisted code, tested by a human on a Paper server. :)

## License

Duskweaver is licensed under the MIT License. See [LICENSE](LICENSE) for details.
