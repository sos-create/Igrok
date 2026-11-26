# Igrok

Paper 1.21.4 server-side player agent (Java 21), version 0.5.0.

## Independent decisions and safety
- Player chat is processed only when the message directly addresses Igrok by name or says "Игрок"; unrelated player chat is ignored.
- A request is a suggestion, never an order. Even players with the required permission can be refused.
- The bot weighs its personality, trust/reputation and current activity before accepting requests. It can say no and continue its own plans.
- TNT is permanently forbidden: Igrok will not place, ignite or trigger TNT. Ignition blocks/items are also rejected by the cabin builder. This rule is hard-coded and cannot be overridden by permissions or configuration.
- Chat control still requires `igrok.control` (default: op); house-building requests require `igrok.build` (default: op). Permission only allows making a request; it does not guarantee acceptance.

## Social interaction
- Greets nearby players in normal public chat, with a configurable cooldown per player.
- Looks at nearby players while idle and waves with its main hand when greeting or being interacted with.
- Right-clicking the bot starts a short social interaction.
- It reacts to being hit by a player and remembers the event; it does not retaliate against real players.
- Replies can refer to recent conversation lines, player name, remembered interests and approximate trust/reputation.

## Compact memory
- Stored in `plugins/Igrok/memory.bin`: versioned binary stream compressed with GZIP.
- Keeps up to 128 player records and 10 short conversation entries per player.
- Remembers player UUID/name, reputation, interaction count, greeting cooldown, topic counters, recent dialogue and mined-material totals.
- Text entries are trimmed to 180 characters; no unbounded chat transcript is kept.
- Writes are atomic where supported and saved every 60 seconds plus on shutdown.
- Existing `memory.yml` is imported automatically on first startup and remains untouched as a backup.
- No additional library, database, network call or AI model is required.

## Commands
- `/igrok status` — current agent activity.
- `/igrok brain` — personality, memory size and remembered conversation count.
- `/igrok follow` — follow the command sender.
- `/igrok mine wood` or `/igrok mine stone` — gather a small batch.
- `/igrok attack` — attack the nearest hostile monster.
- `/igrok explore` — explore nearby loaded terrain.
- `/igrok stop` — stop the current manual task.
- `/igrok build` / `/igrok cancelbuild` — start or cancel the cabin builder.
- `/igrok spawn` / `/igrok remove`.

Address the bot by name or write "Игрок" to talk to it. It will not treat unrelated public chat as instructions.

## Performance and storage
- No neural network, model download, database server or GPU required.
- Perception and movement remain bounded. Social scanning runs at a configurable interval.
- Memory is bounded by player and message caps; compressed binary storage is typically much smaller than a YAML transcript. Actual size depends on names and chat content.
- Memory writes are periodic and atomic where supported.

## Limitations
This is a deterministic agent, not human consciousness. Its replies and decisions are algorithmic, based on personality, trust, activity and compact recent conversation memory. It can refuse requests, but its reasoning is a rule-based simulation rather than human-level understanding.

## Source adaptation
The fake-player architecture is adapted from [My Bot Mod](https://github.com/skylhcss/my-bot-mod), distributed under MIT; see [UPSTREAM-LICENSE](UPSTREAM-LICENSE).
