# Igrok

Paper 1.21.4 server-side player agent (Java 21), version 0.4.0.

## Social interaction
- Greets nearby players in normal public chat, with a configurable cooldown per player.
- Looks at nearby players while idle and waves with its main hand when greeting or being interacted with.
- Right-clicking the bot starts a short social interaction.
- It reacts to direct messages and can continue a conversation for a short period when the player remains nearby, even if every line does not repeat the bot name.
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

Address the bot by name or write "Игрок" to start a conversation. While a conversation is active and the player stays nearby, the bot can answer follow-up messages without being named in every line. Ask `Игрок, что мы обсуждали?` or `Игрок, что ты помнишь обо мне?` to query its compact memory.

Chat control requires `igrok.control` (default: op); house building chat requests require `igrok.build` (default: op).

## Performance and storage
- No neural network, model download, database server or GPU required.
- Perception and movement remain bounded. Social scanning runs at a configurable interval.
- Memory is bounded by player and message caps; compressed binary storage is typically much smaller than a YAML transcript. Actual size depends on names and chat content.
- Memory writes are periodic and atomic where supported; the main thread does only bounded in-memory updates during conversation.

## Limitations
This is a deterministic agent, not human consciousness. Its replies are rule-based with context from the compact recent conversation memory, personality and player reputation. It does not retain every message forever; it keeps a short rolling conversation plus summary counters. The chat listener only treats messages as conversation when the bot is addressed or the player has an active nearby conversation with it. It does not answer unrelated server chat globally.

## Source adaptation
The fake-player architecture is adapted from [My Bot Mod](https://github.com/skylhcss/my-bot-mod), distributed under MIT; see [UPSTREAM-LICENSE](UPSTREAM-LICENSE).
