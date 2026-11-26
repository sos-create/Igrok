# Igrok

Paper 1.21.4 server-side player agent (Java 21), version 0.3.1.

## Cognitive systems in 0.3.0
- **Perception:** periodically observes nearby hostile mobs and the local loaded world.
- **Personality:** stable traits (curiosity, bravery, sociability, patience, creativity) derived from the bot profile.
- **Memory:** stores player reputation and mined block counts in `plugins/Igrok/memory.yml`.
- **Goal planner:** prioritizes survival, defense, following a player, gathering and exploration.
- **A* navigation:** bounded search over walkable blocks; it never loads chunks to make a route and backs off briefly after a failed route to avoid repeatedly burning CPU.
- **Combat:** approaches and attacks hostile Minecraft monsters. Player-versus-player attacks are not implemented.
- **Mining:** can cut tree logs or mine stone/ore with a visible staged break action. Every actual block break uses Paper's player block-breaking API so protection plugins can cancel it.
- **Dialogue:** opinions are selected from the bot's personality traits and remembered reputation, rather than only a fixed greeting list.
- **House building:** the gradual cabin builder remains available; its material palette is influenced by the bot's creativity trait.

## Commands
- `/igrok status` — current agent activity.
- `/igrok brain` — personality and remembered mined-block count.
- `/igrok follow` — follow the command sender.
- `/igrok mine wood` or `/igrok mine stone` — gather a small batch.
- `/igrok attack` — attack the nearest hostile monster.
- `/igrok explore` — explore nearby loaded terrain.
- `/igrok stop` — stop the current manual task.
- `/igrok build` / `/igrok cancelbuild` — start or cancel the cabin builder.
- `/igrok spawn` / `/igrok remove`.

In chat, address the bot by name or say "Игрок" and ask it to follow you, cut wood, mine stone, attack a hostile mob, explore, build a house, or share its opinion.

Chat control requires `igrok.control` (default: op); house building chat requests require `igrok.build` (default: op).

## Performance and safety
- No mandatory neural network, model download, external service or GPU use.
- The brain runs on the server thread. Expensive decisions run at a configurable interval; pathfinding is bounded to 900 expanded nodes.
- Perception and route search are restricted to already-loaded chunks.
- The bot attacks hostile monsters only. It never targets real players.
- Mining and placement go through Bukkit events where available; region-protection plugins can deny actions.
- The agent receives wooden tools and a small amount of bread at spawn.

## Important limitations
This is a deterministic agent architecture, not human consciousness. Personality and "opinions" are generated from stable traits, current context and saved player reputation. The present mining routine targets nearby visible tree logs, stone and common ores; it is not yet capable of long underground strip-mines, crafting chains, farming, trading, redstone engineering or complex multi-building projects. Its A* navigator is deliberately bounded and should be tested on your exact world and plugin stack. The existing house builder still performs gradual server-side block placement; it does not yet animate a fully faithful hand-held block placement workflow.

## Source adaptation
The fake-player architecture is adapted from [My Bot Mod](https://github.com/skylhcss/my-bot-mod), distributed under MIT; see [UPSTREAM-LICENSE](UPSTREAM-LICENSE). The original behavior editor/runtime is a useful reference for a future programmable behavior system, but this Paper port uses its own Bukkit-compatible decision and action modules.
