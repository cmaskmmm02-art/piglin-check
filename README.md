# Piglin Check (Fabric, Minecraft 1.21.11)

Press F3+B (hitboxes). You then have 15 seconds to actually see at least one
zombified piglin (on screen and with line of sight, within 64 blocks). Merely
loaded piglins don't count. If you don't see one, you get disconnected.

- Turning hitboxes off cancels the check.
- Once a piglin is seen, the check is satisfied until you toggle hitboxes off and on again.
- An action-bar countdown shows the remaining time.

## Build

Needs JDK 21 and Gradle 9+ (or add the Gradle wrapper from the Fabric example mod).

    gradle build

The jar is in `build/libs/piglin-check-1.0.0.jar`. Put it in your `mods` folder
together with Fabric API 0.141.1+1.21.11.

## Settings

Edit the constants at the top of `PiglinCheckClient.java`:
`TIMEOUT_SECONDS`, `MAX_DISTANCE`, `REQUIRE_LINE_OF_SIGHT`, `REARM_AFTER_SEEN`.
