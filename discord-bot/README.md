# MaceLimiter Discord Bot

Controls the active MaceLimiter target through the relay bridge.

## Setup

1. Install Python 3.11+.
2. Run `pip install -r requirements.txt`.
3. Create a Discord application/bot and put its token in `DISCORD_TOKEN`.
4. Optionally set `MACE_ALLOWED_ROLE_IDS` to allowed Discord role IDs. Server administrators are always allowed.
5. Run `python bot.py`.

Commands:
- `/maces` — current target mace count.
- `/mc <command>` — execute a Minecraft console command.
- `/mcstatus` — show the active target.

The bot automatically discovers the active MaceLimiter server; no room code is required.
