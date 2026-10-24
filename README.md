# Quarry

Two dragon-racing game modes for Paper/Spigot servers, set up from an in-game menu:

- **Manhunt**: runners try to kill the Ender Dragon while hunters track them down with compasses.
- **Dragon Rush**: everyone races to the End, and whoever lands the final blow on the dragon wins.

[Download on Modrinth](https://modrinth.com/plugin/quarry-manhunt)

## Manhunt

Runners win by killing the Ender Dragon. Hunters win when every runner is down.

- **Tracking compasses that work everywhere.** Hunters right-click their compass to lock
  onto the nearest runner, in the Overworld, the Nether *and* the End. If no runner is in the hunter's
  dimension, the compass points to where a runner was last seen there (for example, the
  portal they left through).
- **Head start.** Runners get a head start you set with `/quarry manhunt headstart <seconds>`.
  During it, hunters are frozen, blinded and can't break or place blocks.
- **Ready-up.** Players mark themselves ready with `/quarry manhunt iamready`. When everyone
  is ready, an op starts the game, or a runner starts it by punching a hunter.
- **Random teams.** `/quarry manhunt random [hunters]` shuffles everyone into roles.
- **Runner lives.** Give runners more than one life. Out of lives means spectator.
- **Help-hunter mode (optional).** Catch-up buffs for hunters who keep losing fights:
  Strength I after being killed by runners 5 times and Speed I after 10. Hunters also
  keep their inventory while it's on.
- **Rulebook.** `/quarry manhunt rules` hands out a book with the house rules for
  Nether portals, so everyone plays fair.

## Dragon Rush

Everyone is a racer. The first player to land the final blow on the Ender Dragon wins.

- Optional countdown, during which racers are frozen at the start
- Optional PvP, so racers can sabotage each other
- Lives per racer, or unlimited (dying just costs time)
- Optional keep-inventory
- Scatter racers around spawn so nobody shares a starting spot
- Optional time limit, after which the race is a draw

## Shared features

- **Setup GUI.** `/quarry` opens a mode picker. From there you set teams and settings
  with clicks instead of commands.
- **Pause and resume.** Freeze the whole game (everyone stands still and the timer stops),
  then carry on.
- **Survives restarts.** A game in progress is saved, so a server restart or crash doesn't
  end it. Players who rejoin get their role back.
- **Titles and timers.** Big on-screen titles for every phase, and the final time is
  shown when someone wins.

## Commands

| Command | Who | Description |
|---|---|---|
| `/quarry` | op | Open the mode picker |
| `/quarry manhunt` | op | Open the Manhunt setup GUI |
| `/quarry manhunt hunter\|runner <player>` | op | Assign a role |
| `/quarry manhunt random [hunters]` | op | Shuffle everyone into roles |
| `/quarry manhunt headstart <seconds>` | op | Set the head start (required before the first game) |
| `/quarry manhunt helphunter <on\|off>` | op | Toggle hunter catch-up buffs |
| `/quarry manhunt start\|pause\|resume` | op | Control the game |
| `/quarry rush` | op | Open the Dragon Rush setup GUI |
| `/quarry rush racer <player>` | op | Add or remove a racer |
| `/quarry rush random` | op | Make everyone a racer |
| `/quarry rush start\|pause\|resume` | op | Control the race |
| `/quarry stop` | op | End the current game |
| `/quarry reload` | op | Reload the config |
| `/quarry manhunt iamready` / `/quarry rush iamready` | everyone | Mark yourself ready |
| `/quarry status` | everyone | Show roles, timer and settings |
| `/quarry manhunt rules` | everyone | Get the rulebook |
| `/quarry help` | everyone | Show help |

Aliases: `/mh`, `/hunt`.

## Permissions

| Node | Default | Grants |
|---|---|---|
| `quarry.admin` | op | Assign roles, change settings, start/pause/stop games |

## Configuration

```yaml
head-start-seconds: 0          # set in-game with /quarry manhunt headstart
runner-lives: 1                # 1 = out on first death
compass-cooldown-seconds: 0    # delay between compass re-locks
help-hunter: false             # hunter catch-up buffs

rush:
  countdown-seconds: 10        # frozen start (0 = none)
  pvp: false                   # racers can hit each other
  lives: 0                     # 0 = unlimited
  keep-inventory: false
  scatter: true                # spread racers around spawn
  scatter-radius: 200
  time-limit-minutes: 0        # 0 = no limit
```

## Building

Requires JDK 21+. The Gradle wrapper is included, so no local Gradle install is needed:

```
./gradlew build
```

The jar lands in `build/libs/`. Built against the Spigot 26.3 API; runs on 26.2 and 26.3.

## Reporting bugs

Open an issue on this repository using the bug report form.

## License

MIT, see [LICENSE](LICENSE).
