# Igrok 0.9.19

Paper 1.21.4 server-side player agent (Java 21). Igrok is a lightweight autonomous survival agent built around a fake server-side Player, bounded pathfinding, utility-based goals, associative cognition and compact persistent memory.

## Autonomous survival loop

- Chooses between collecting resources, exploring, hunting for food, sleeping, fighting nearby hostiles, following a player or resting.
- Uses its own inventory: picks up nearby item drops and experience, eats suitable food, selects tools from the full inventory and equips better armor when available.
- Crafts useful items using recipes registered by the running server: planks, sticks, crafting tables, tools, swords, shields, doors, bread, torches, furnaces, chests and beds when ingredients and a suitable work area are available.
- Places and uses a furnace to smelt raw meat, fish, potatoes and supported ores; collects output and supplies fuel from coal, charcoal or surplus planks.
- Finds nearby chests, creates one when possible, and stores surplus wood, stone, ores and selected farming supplies while retaining working materials in its personal inventory.
- Harvests mature nearby wheat, carrots, potatoes and beetroot, then replants only when it already has the appropriate seed or crop item.
- Looks for a bed at night and sleeps when one is available. If it has enough materials, it plans and builds one compact shelter from its own inventory. The builder checks the full material bill first and consumes one item for every block placed.
- Avoids searching or building through unloaded chunks. Creepers within a larger danger radius trigger retreat rather than closing into melee range.

## Decision and cognition

- The goal planner compares risk, food and material needs, curiosity, personality and active tasks.
- Critical survival decisions can interrupt voluntary activities. Player orders are proposals, not commands that override the bot's own survival.
- The associative cognitive network links language concepts and intentions and learns weighted associations from observed interactions.
- Dialogue memory uses shared word IDs, phrase IDs and a sparse transition matrix.

This is an algorithmic simulation. It does not provide human-level intelligence, general world knowledge or a pretrained LLM.

## Persistence and performance

- Player profiles are capped at 50; up to 24 recent phrase references are kept per profile.
- Compact memory and cognitive weights load once and save every five minutes only when changed.
- Inventory, equipment, food, health, XP and player position/task state are persisted separately. Inventory is saved every five minutes and on normal shutdown.
- Scans for furnaces, chests, crops, beds and crafting are throttled; pathfinding is bounded and only reads already-loaded chunks.

## Player appearance and safe movement

- The fake player receives a two-chunk client view distance in the ServerPlayer constructor, before PlayerList registers it and creates chunk tickets.
- The same restricted ClientInformation is used by the fake packet listener and the PlayerList login cookie. Post-registration Bukkit limits are a second guard, not the primary restriction.
- An optional signed Mojang skin can be selected with skin-owner in config.yml. Lookup is asynchronous and has a five-second startup fallback; a slow skin service never blocks server startup.
- Pathfinding only examines already-loaded chunks, revalidates waypoints before movement, rejects common damaging blocks and checks combat sidesteps against safe ground.
- The initial spawn and exploration destinations reject common damaging surfaces.

## Chat and interaction

- Igrok replies when addressed by its configured name or Игрок; recent direct conversation can continue without repeating the name.
- Unrelated player-to-player chat is ignored.
- Player requests are expressed in ordinary chat; the bot may accept or refuse them.
- No slash-command interface is required.
- TNT and ignition remain forbidden.

## Notes

The plugin is compiled against Paper 1.21.4. Version 0.9.12 includes the safe stuck-recovery check and world-border guard; version 0.9.11 corrected the ServerPlayer constructor settings; the 0.9.10 post-login-only distance restriction was insufficient by itself. A successful CI build verifies source compilation and artifact packaging, but actual movement, crop interaction, furnace behavior and protection-plugin compatibility must still be tested on the target live server.

Unexpected runtime failures in chat, the brain and construction are isolated: a failed construction step cancels that build, a failed brain tick pauses and retries instead of cancelling the scheduler, and duplicate stack traces are rate-limited. Chat input is length-limited before parsing. These safeguards prevent cascading errors; they do not repair invalid chunk data already stored in a world region file.\n\nIgrok is explicitly placed in Survival mode and is never an operator. It starts with an empty inventory, 20 health, 20 food and normal saturation; saved inventory and vitals are restored across restarts. Vanilla item entities handle pickup, so the bot no longer vacuums items from four blocks away. Mining, farming, container access and building are limited to ordinary player reach and line of sight; building walks to a legal standing position and respects BlockPlaceEvent cancellation. Death uses vanilla inventory and experience loss before automatic respawn.

Chat messages are coalesced and duplicate text is ignored for one minute, except valid answers to a recent bot question. Unrelated chat cannot replace a pending message addressed to Igrok. Responses are intentionally delayed by a configurable 20–30 seconds after the last queued message. The autonomous planner runs at 10 Hz while the Minecraft player entity continues to receive normal server physics ticks. Crafting, farming, furnace, storage and bed scans run less frequently; A* has a smaller node budget and mining search radius.

The fake-player architecture is adapted from https://github.com/skylhcss/my-bot-mod, distributed under MIT; see UPSTREAM-LICENSE.


Dialogue parsing recognizes Russian and English words with Unicode-aware tokenization, uses sentence-level question detection instead of treating every occurrence of "what/how" as a question, and distinguishes imperative forms from ordinary conversation. Repeated identical answers are varied without forcing a question at the end of every reply. Interaction and attack reactions are rate-limited; cancelled server events do not affect memory or trust.

The Russian question detector uses Unicode-safe word boundaries. Standalone negation words are separated from ordinary phrases such as "без проблем", so casual positive wording does not suppress an otherwise valid action request.

Wood and nature recognition also covers common Russian forms such as дерево, деревья, деревянный, древесина and доски.

Repeated identical dialogue replies are replaced with a distinct non-question response, rather than repeating the same question with an added sentence. Chat events cancelled by other plugins are ignored.