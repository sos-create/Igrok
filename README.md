# Igrok

Server-side fake-player plugin for **Paper 1.21.4**.

## Current implementation
- Creates a server-side `ServerPlayer` and registers it through the vanilla player list.
- Uses a fake packet listener that discards outbound packets (no fake Netty queue growth).
- Automatically spawns at the configured world's normal spawn point.
- Attaches no custom skin textures, so the client uses the vanilla default skin fallback.
- Responds to mentions in normal public chat with simple Russian scripted dialogue.
- Optional respawn after death.
- No continuously running neural network or background polling.

## Install
1. Use Paper 1.21.4 and Java 21.
2. Download the `Igrok-*.jar` from the successful GitHub Actions build artifact.
3. Put it in `plugins/` and start the server.
4. The bot appears at the first world's spawn by default.

## Commands
- `/igrok status`
- `/igrok spawn`
- `/igrok remove`

Permission: `igrok.admin` (default: op).

## Configuration
See `plugins/Igrok/config.yml`. Set `spawn-world` to a world name or leave it empty to use the first loaded world.

## Source adaptation and attribution
The fake-player architecture is adapted from **My Bot Mod** by Skyline_hcss:
https://github.com/skylhcss/my-bot-mod

The upstream project is distributed under the MIT License. Its copyright and permission notice are retained in [UPSTREAM-LICENSE](UPSTREAM-LICENSE). This is a Paper adaptation, not a wholesale Fabric mod conversion. Client-only editor/UI code is not included.

## Current scope
This initial stage provides the fake player, spawn lifecycle and scripted chat dialogue. The original mod's complete behavior runtime, pathfinding and action controller are not yet ported; they require adaptation and separate testing against Paper's tick model.
