# MaceLimiter

Paper 26.2 Mace limiter with automatic server discovery, an authenticated web console, live console output, and mace-location tracking.

Web console: https://j3id3n.github.io/macelimiter-console/

Servers announce themselves automatically. There is no room code. On first startup the plugin generates a persistent console.access-token in plugins/MaceLimiter/config.yml. Enter that token after selecting the server.

The console reports maces held by players, in ender chests, inside loaded storage blocks and nested storage items, plus dropped maces. Storage entries include the world and exact block coordinates.

Only authenticated sessions can run Minecraft console commands; no operating-system shell is exposed.
