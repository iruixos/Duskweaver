![Duskweaver Banner](assets/header.png)

# Duskweaver

**Weave time to your will.** Independent Minecraft day/night duration and sleep-based night skipping for Paper 26.3.

## Features

- 15-minute day / 5-minute night by default at 20 TPS.
- Configurable world list, lengths, and sleep skipping.
- One-player sleep, strict majority with a two-player exception, or the world’s original vanilla percentage.
- 10-second cancellable countdown (configurable).
- Active player filtering: spectators excluded; players inactive for 5 minutes excluded unless sleeping.
- Each world counts its own players.
- Restores original daylight and sleeping percentage gamerules on clean shutdown and reload.

## Requirements

- **Server:** Paper 26.3 (the project targets Paper API `26.3.build.244-stable`)
- **Java:** Java 25 or newer for building and running the target server
- **Build:** Maven
- **Folia:** Not supported (`folia-supported: false`)

## Build

Use **JDK 25** and Maven:

```bash
mvn clean package
```

Output: `target/Duskweaver-VERSION.jar`. Install in the server `plugins/` directory and restart.

## Configuration

See `src/main/resources/config.yml` for all documented options. Copy generated `plugins/Duskweaver/config.yml` to change values; run `/duskweaver reload` (alias `/dw reload`) to apply. Permission: `duskweaver.reload` (OP).

### Sleep modes

- `sleep.enabled: false` disables night skipping in managed worlds.
- `one-player-sleep: true` and `majority-vote: false`: one sleeper is sufficient.
- `one-player-sleep: false` and `majority-vote: true`: 1/2, 2/3, 3/4, 3/5, 4/6 etc.
- Both false: the original `playersSleepingPercentage` gamerule determines quorum; Duskweaver still enforces its countdown.
- Both true while enabled: invalid configuration (reload is rejected).

Sleeping players always count as active. Spectators are excluded. Activity is updated by movement (including camera movement) or interaction. This is an approximate inactivity detector, not a third-party AFK plugin integration. World time is advanced with `setFullTime` so the day count progresses.

## Notes

- Plugin controls the Minecraft clock, not server TPS. Lag changes real-world durations.
- Vanilla sleep skipping is suppressed with `playersSleepingPercentage=101` in managed worlds while enabled. The previous value is restored on clean shutdown. A crash cannot restore it automatically; if necessary, set the gamerule manually before restarting.
- Duskweaver only manages worlds listed in `worlds` and already loaded when enabled/reloaded.
- Weather is not cleared when skipping; only time is advanced.
- Not intended for Folia or other plugins simultaneously controlling time/sleep gamerules.
- Compilation and in-game testing are not verified in this environment.

## Project information

- **Plugin:** Duskweaver
- **Current documented version:** 1.2.2
- **Author:** iruixos
- **Platform:** Paper
- **Package:** `com.iruixos.duskweaver`
- **Notes:** This project contains AI-generated code, but was tested by a human in a real PaperMC server :)

## License

Reapwright is licensed under the MIT License.
See the [LICENSE](LICENSE) file for details.
