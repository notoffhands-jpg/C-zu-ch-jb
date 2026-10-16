# ArmyFPS

Client mod (Fabric 1.21.11, works with Sodium) for more FPS with big armies of players/bots on screen.
Up close nothing changes; only detail that is invisible at the size of a far player is skipped.

## What it does (2.0)
- **Multi-core army rendering**: every frame, players/bots and their armour and trims are posed and turned into
  finished vertices on several CPU cores at once, each core with its own copy of the models. The render thread then
  only copies the finished blocks into the vertex buffer, in exactly the same order and with exactly the same bytes
  Minecraft would have written. Other models, glowing outlines, shaders' vertex formats and anything that fails are
  drawn by Minecraft as usual.
- **Armour glint** is not drawn beyond 32 blocks, **armour trims** not beyond 24.
- **Held/dropped item glint** is not drawn beyond 32 blocks. GUI, inventory and first person are never touched.
- **Translucent sort**: Minecraft's sort of see-through geometry, replaced by a radix sort with the identical result, several times faster.
- **F8** switches the whole mod (multi-core included) on/off in-game for an instant FPS comparison (message above the hotbar).

Settings: `config/armyfps.properties` (`parallel`, `parallelThreads` (default: half your CPU threads, max 12),
`parallelMinCommands`, distances, `toggleKey`) in the game folder (created on first start; 0 = never skip; restart after editing).

## Build and install (on the PC with the game)
```bash
cd ~/Desktop/ArmyFPS && git pull && bash armyfps/build.sh install
```
Builds with plain javac against the Minecraft 1.21.11 jar from the Loom cache and the Prism instance's libraries,
moves any older ArmyFPS jar from the mods folder to `armyfps/old/` and installs the new one.

The old BotSystem plugin code was removed from this repository. It is still in the git history
(commit 8da82ec) and untouched on the server.
