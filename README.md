# MaceLimiter

Paper 26.2 plugin that enforces the six-Mace server-wide limit and runs the Discord control bot inside the same plugin.

## One-plugin architecture

There is no separate Discord bot process and no relay/discovery service. The MaceLimiter JAR connects directly to Discord while the Minecraft server is running.

## Setup

After the first start, edit `plugins/MaceLimiter/config.yml`:

```yaml
discord:
  enabled: true
  token: "YOUR_DISCORD_BOT_TOKEN"
  guild-id: ""
```

Set `guild-id` to your Discord server ID for immediate slash-command registration. Leave it blank for global commands.

Discord commands:
- `/maces` — current global mace count.
- `/mcstatus` — Minecraft server status.
- `/mc <command>` — dispatch a Minecraft console command.

The Discord commands are available to all members of a guild. The bot only dispatches Minecraft/Bukkit commands; it does not execute operating-system shell commands.

The JDA Discord library is packaged inside the MaceLimiter JAR, so there is only one plugin file to install.
