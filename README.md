# Igrok

Server-side fake-player plugin for **Paper 1.21.4** (Java 21).

## Current implementation
- Creates a server-side `ServerPlayer` and registers it through the vanilla player list.
- Uses a fake packet listener that discards outgoing packets.
- Automatically spawns at the configured world's normal spawn point.
- Responds to mentions in normal public chat with simple Russian scripted dialogue.
- Builds a small wooden cabin on request: 7x7 foundation/floor, spruce walls, glass windows, oak door and a 9x9 overhanging roof.
- Searches for a flat, clear site near the player. It does not clear terrain or overwrite existing blocks.
- Places one block every configured number of ticks, on the main server thread.
- Fires a `BlockPlaceEvent` for each block so protection plugins can reject placements.
- Supports stopping a build with `/igrok cancelbuild`; blocks already placed are left in place.
- No neural network or background polling is used.

## Install
1. Use Paper 1.21.4 and Java 21.
2. Download the `Igrok-*.jar` from the successful GitHub Actions build artifact.
3. Put it in `plugins/` and restart the server.

## Commands
- `/igrok status`
- `/igrok spawn`
- `/igrok remove`
- `/igrok build` — build a cabin near the command sender (for console, near Igrok).
- `/igrok cancelbuild` — stop the current build.

Permission: `igrok.admin` (default: op). Chat construction is additionally gated by `igrok.build` (default: op). Say `Игрок, построй дом` to request a cabin in chat.

## Configuration
See `plugins/Igrok/config.yml`:
- `building.enabled`: enable or disable construction.
- `building.block-interval-ticks`: delay between block placements (default 2 ticks).
- `building.search-radius`: maximum site-search radius (default 16 blocks).
- `building.chat-trigger`: enable chat build requests.

## Important behavior
This version uses a server-side construction routine: it changes the world one block at a time and emits block-place events. The fake player does **not yet physically walk to each block, hold items, or consume materials**. It is a low-cost deterministic algorithm, not an AI/neural-network builder. The build can stop partway if a protection plugin cancels a placement; blocks already placed are intentionally not rolled back.

## Source adaptation and attribution
The fake-player architecture is adapted from **My Bot Mod** by Skyline_hcss:
https://github.com/skylhcss/my-bot-mod

The upstream project is distributed under the MIT License. Its copyright and permission notice are retained in [UPSTREAM-LICENSE](UPSTREAM-LICENSE). This is a Paper adaptation, not a wholesale Fabric mod conversion.
