# Igrok 0.9.53

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

## Branching development and self-model

- Development is split into independent branches: survival, exploration, craft, construction, combat, social play and fishing. Every branch is available from the start; none requires completing the others.
- Experience is earned through successful in-world actions such as mining, building, moving through new areas, farming, chatting, combat and cooking. Extra branches grow only when the bot actually performs the corresponding activity.
- The preferred branch emerges from the bot's accumulated experience. It influences autonomous priorities, so different histories can produce different choices.
- At higher development stages the planner gradually shifts from resource grinding toward self-chosen exploration, optional projects and ordinary downtime. Critical survival and immediate danger still take priority.
- "Self-awareness" is an algorithmic self-model: a compact summary of strengths, preferences and lessons from previous actions. It is not sentience or human-level consciousness.

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
- An unfinished construction is restored after restart or death. Its compact footprint and placement radius are reloaded asynchronously, with bounded retries; it never synchronously forces a large area of terrain to load.
- Scans for furnaces, chests, crops, beds and crafting are throttled; pathfinding is bounded and only reads already-loaded chunks.

## Player appearance and safe movement

- The fake player receives a two-chunk client view distance in the ServerPlayer constructor, before PlayerList registers it and creates chunk tickets.
- Navigation supplies a movement intent while the fake player applies acceleration and braking once per server tick; vanilla knockback is preserved and head/body turning remains independently smoothed.
- The same restricted ClientInformation is used by the fake packet listener and the PlayerList login cookie. Post-registration Bukkit limits are a second guard, not the primary restriction.
- An optional signed Mojang skin can be selected with skin-owner in config.yml. Lookup is asynchronous and has a five-second startup fallback; a slow skin service never blocks server startup.
- Pathfinding only examines already-loaded chunks, revalidates waypoints before movement, rejects common damaging blocks and checks combat sidesteps against safe ground.
- The initial spawn and exploration destinations reject common damaging surfaces.

## Chat and interaction

- Igrok replies when addressed by its configured name or Игрок.
- Ordinary chat can start naturally when only one non-spectator player is within the listening radius; the same player can continue the conversation for the configured session window without repeating the bot name.
- When several players are nearby, Igrok does not intrude on their conversation unless directly addressed or already in a conversation with that player.
- Player requests are expressed in ordinary chat; the bot may accept or refuse them.
- Igrok does not greet players first, request items, invite anyone to explore or suggest joint activities. He keeps pursuing his own survival, crafting, resource and exploration goals.
- Conversation begins when a player addresses Igrok or starts ordinary chat with him. Gift pickup can trigger a brief thank-you because the player initiated that interaction.
- Player gifts are recognized when Igrok actually picks up an item thrown by that player. Gift events, conversations and negative actions change the persistent per-player relationship record.
- There is no ambient unsolicited speech or invitation scheduler. Social memory and trust still change through player conversations and gifts.
- No slash-command interface is required.
- TNT and ignition remain forbidden.

## Notes

The plugin is compiled against Paper 1.21.4. Version 0.9.51 preserves an active construction across death and respawn, saves the last bot location before replacement, and resumes near a safe saved position with a world-spawn fallback. Version 0.9.50 also strengthened independent goal continuity, removed chat-driven interruption of autonomous work, reset temporary orders on restart, and hardened sleep/build recovery. A successful CI build verifies source compilation and artifact packaging, but actual movement, crop interaction, furnace behavior and protection-plugin compatibility must still be tested on the target live server.

Unexpected runtime failures in chat, the brain and construction are isolated: a failed construction step cancels that build, a failed brain tick pauses and retries instead of cancelling the scheduler, and duplicate stack traces are rate-limited. Chat input is length-limited before parsing. These safeguards prevent cascading errors; they do not repair invalid chunk data already stored in a world region file.

Igrok is explicitly placed in Survival mode and is never an operator. It starts with an empty inventory, 20 health, 20 food and normal saturation; saved inventory and vitals are restored across restarts. Vanilla item entities handle pickup, so the bot no longer vacuums items from four blocks away. Mining, farming, container access and building are limited to ordinary player reach and line of sight; building walks to a legal standing position and respects BlockPlaceEvent cancellation. Death uses vanilla inventory and experience loss before automatic respawn.

Chat messages are coalesced and duplicate text is ignored for one minute, except valid answers to a recent bot question. Unrelated chat cannot replace a pending message addressed to Igrok. Responses are intentionally delayed by a configurable 20–30 seconds after the last queued message. The autonomous planner runs at 10 Hz while the Minecraft player entity continues to receive normal server physics ticks. Crafting, farming, furnace, storage and bed scans run less frequently; A* has a smaller node budget and mining search radius.

The fake-player architecture is adapted from https://github.com/skylhcss/my-bot-mod, distributed under MIT; see UPSTREAM-LICENSE.


Dialogue parsing recognizes Russian and English words with Unicode-aware tokenization, uses sentence-level question detection instead of treating every occurrence of "what/how" as a question, and distinguishes imperative forms from ordinary conversation. Repeated identical answers are varied without forcing a question at the end of every reply. Interaction and attack reactions are rate-limited; cancelled server events do not affect memory or trust.

The Russian question detector uses Unicode-safe word boundaries. Standalone negation words are separated from ordinary phrases such as "без проблем", so casual positive wording does not suppress an otherwise valid action request.

Wood and nature recognition also covers common Russian forms such as дерево, деревья, деревянный, древесина and доски.

Repeated identical dialogue replies are replaced with a distinct non-question response, rather than repeating the same question with an added sentence. Chat events cancelled by other plugins are ignored.

## Independent life routines (0.9.46)

- During overworld storms Igrok searches only already-loaded chunks for a safe position under a roof, then temporarily pauses its current voluntary route and resumes it when the storm ends. The scan radius and interval are bounded in config.
- Gifts add experience to the social/cooperation development branch. The existing independent skill branches remain optional and continue to grow from actual in-world actions.


## Social and weather bug fixes (0.9.47)

- Public chat from nearby players is no longer automatically treated as addressed to Igrok. Players can talk to the bot by name; short follow-ups and explicit answers to a pending invitation remain supported.
- Invitation answers are matched against the active invitation even when the player is outside the normal chat range. Negative wording takes precedence over a preceding casual "да".
- A food invitation no longer increases trust or social skill before the food is actually received; gift pickup records the real interaction.
- Gift attribution uses the dropped item's thrower UUID and no longer rejects a genuine gift just because the giver walked away before pickup.
- When rain stops or the rain-shelter routine is disabled, it releases the navigator so the autonomous planner regains full movement control.

## Companion dialogue and ordinary chat (0.9.48)

- The existing parser, associative cognitive network, stable personality, social reputation and compact conversation memory remain the core of the bot's mind; the new dialogue layer is integrated with them rather than replacing them.
- Players can speak naturally in the normal Minecraft chat. No slash commands, dialogue wheel, special prefix or external model service is required.
- The conversation layer understands additional personal topics: home and shelter, rest and sleep, hunger, danger and fear, gifts, trust and friendship, remembered conversations, plans, interests and skill development.
- Replies use the bot's current survival state and nearby world conditions where applicable. For example, answers about hunger use its actual food level and inventory; answers about danger inspect nearby hostile mobs.
- Conversation wording is newly written for Igrok. The social interaction categories are reimplemented independently; Borya's source code, assets and dialogue lines are not copied.
- This remains an adaptive algorithmic dialogue system, not a pretrained language model or human consciousness.


## Independent goals and player requests (0.9.49)

- Igrok no longer initiates greetings, asks for food, invites players to explore or proposes doing things together. It spends its time on its own survival and development goals.
- Players can still start ordinary chat or make a request in chat. Igrok evaluates requests against current danger, construction, its active goal, personality and its relationship with the player; it may accept or refuse.
- Even requests to gather wood or stone are no longer automatically accepted. Immediate danger and an active construction cannot be interrupted by a normal request.
- Social memory and gift recognition remain. A brief thank-you is sent only after Igrok actually receives a player-thrown item.


## Autonomy and stability improvements (0.9.50)

- Addressing Igrok in chat no longer makes him walk away from his work or cancel a construction. He answers through chat and keeps the current goal.
- A restart discards temporary player orders and stale navigation targets. Skills, memory, personality and learned danger lessons remain; the planner selects a fresh goal after startup.
- Communication experience is awarded only after the message passes cooldown and duplicate checks, preventing rapid chat spam from farming social skills.
- The resource planner counts Nether stems, hyphae and bamboo blocks as wood, so agents in those biomes can progress with the materials they actually have.

- Version 0.9.50 also caps communication-skill gains to one award per player per 30 seconds; the planner recognises Overworld logs, Nether stems/hyphae and bamboo blocks as wood.


## Death and construction recovery (0.9.51)

- If Igrok dies while building, the plugin saves the current block cursor and world position before replacing the fake player. The builder task is not cancelled; it waits for the respawned player and rebinds its navigator.
- Automatic respawn first tries the last saved location and searches for safe ground there. If that chunk or position cannot be used, it falls back to the configured world spawn.
- Builder recovery uses a 60-second wall-clock timeout so low build speeds, chunk loading and server lag do not consume the retry window too quickly.
