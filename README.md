# Igrok 0.8.0

Paper 1.21.4 server-side player agent (Java 21).

## Cognitive architecture
- Language layer: Russian/English normalization, lightweight stemming, synonym groups, question and command detection, negation and sentiment signals.
- Associative layer: dozens of semantic concept nodes activate related ideas through weighted links.
- Intent layer: combines several signals to distinguish greetings, questions, memories, opinions, emotions, requests, agreement and disagreement.
- Dialogue layer: response openings, topic-specific reasoning, content fragments and follow-up questions combine into a large variety of replies.
- Adaptive links: a Hebbian-style learning rule strengthens or weakens associations when a player reacts to a previous answer. Co-occurring concepts also form stronger links.
- Player behavior model: per-player counters and action transitions remember whether a player tends to build, mine, fight, explore or interact socially, including common sequences.

This is an algorithmic simulation, not a human brain or a pretrained language model. It does not understand every possible sentence like a large language model, but it uses multiple connected stages rather than a one-keyword-to-one-answer lookup.

## Persistent learning
- plugins/Igrok/memory.bin keeps compact per-player records, dialogue snippets, reputation, preferred topics, action counters and action-transition counts.
- plugins/Igrok/cognition.yml stores learned synaptic weights.
- Old memory.bin versions 1 and 2 are read and upgraded to version 3 on the next save.
- Learning and language analysis run only when a relevant event occurs. The network is not ticked continuously.
- Memory and network weights are saved periodically and on normal shutdown.

## Chat and actions
- Igrok responds when addressed by its configured name or by “Игрок”; it can also continue a recent direct conversation.
- Unrelated player-to-player chat is ignored.
- It can answer in public chat, express opinions, remember earlier messages and politely refuse requests.
- Chat requests are suggestions, not guaranteed commands. igrok.control and igrok.build permissions are required to request actions.
- TNT placement, ignition and detonation remain permanently forbidden.

## Performance
- No neural framework, GPU, model files, database, external network request or extra runtime dependency.
- Inference is event-driven and bounded: a few dozen semantic nodes with two propagation passes, and small bounded memory records.
- The neural-inspired network is calculated on addressed chat only, not on every game tick.
- Existing movement, pathfinding, mining and building remain governed by their current tick limits.

## Commands
- /igrok status — current activity.
- /igrok brain — personality, memory statistics and state.
- /igrok follow, /igrok mine wood, /igrok mine stone, /igrok attack, /igrok explore, /igrok stop.
- /igrok build, /igrok cancelbuild, /igrok spawn, /igrok remove.

## Existing behavior retained
Igrok automatically appears while the Paper server runs, disconnects when it shuts down, remembers its location and unfinished goals across restarts, and keeps the existing safety restrictions. The fake-player architecture is adapted from https://github.com/skylhcss/my-bot-mod, distributed under MIT; see UPSTREAM-LICENSE.
