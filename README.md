# Igrok 0.9.0

Paper 1.21.4 server-side player agent (Java 21).

## Compact memory format
All persistent memory is stored in the dedicated folder plugins/Igrok/memory/.

- memory.dat — one compressed GZIP container holding a shared word dictionary, phrase table, player profiles and a sparse 2D phrase-transition matrix.
- cognition.yml — learned cognitive-network weights; written only after the weights change.
- The word dictionary maps each unique token to a numeric ID. Repeated words are not copied into every sentence.
- The phrase table maps each unique sentence to a sequence of word IDs. If a sentence repeats, history stores only its phrase ID.
- The two-dimensional matrix uses X = previous phrase ID and Y = current phrase ID. It counts which sentence sequences commonly follow one another. It is sparse, so unused coordinates consume no storage.
- IDs use variable-length integer encoding; the full payload is GZIP-compressed.
- The player cap is 50. Up to 24 recent phrase references are kept per player. Oldest profiles are removed first when the cap is exceeded.
- Word IDs, phrase IDs and matrix coordinates are bounded to prevent unbounded storage growth.

## Low disk activity and migration
- Files are read once at plugin startup and kept in memory during gameplay.
- The memory pack is written every five minutes only if something changed, and once on normal shutdown.
- Cognitive weights are also saved only when changed, on the same five-minute schedule and during shutdown.
- A write uses a temporary file and atomic replacement where supported; one compressed memory pack avoids many tiny disk writes.
- Existing plugins/Igrok/memory.bin, memory.yml and root cognition.yml are read as legacy sources and copied into the new folder format. Old files are left untouched as backups.
- Existing 0.8.0 memory is converted to shared word and phrase IDs at first startup after the upgrade.

## Cognitive architecture
- Russian/English normalization, common word endings, synonym groups, question/command detection, negation and sentiment.
- Concept neurons, associative links, intent neurons and two shallow propagation passes.
- Hebbian-style link adaptation based on conversation context and player reactions.
- DialogueComposer creates varied replies using topic-specific reasoning, memory and personality.
- Player profiles learn mining, building, combat, exploration and social interaction patterns.

This is an algorithmic cognitive simulation, not a pretrained large language model. It performs bounded calculations only for relevant events and addressed messages, not on every game tick.

## Chat and actions
- Igrok responds when addressed by its configured name or Игрок; recent direct dialogue can continue without repeating the name.
- Unrelated player-to-player chat is ignored.
- Requests require igrok.control or igrok.build; permission does not force the bot to accept.
- TNT and ignition remain forbidden.

## Commands
- /igrok status, /igrok brain, /igrok follow
- /igrok mine wood, /igrok mine stone, /igrok attack, /igrok explore, /igrok stop
- /igrok build, /igrok cancelbuild, /igrok spawn, /igrok remove

The fake-player architecture is adapted from https://github.com/skylhcss/my-bot-mod, distributed under MIT; see UPSTREAM-LICENSE.