import asyncio
import base64
import json
import os
import time
import uuid
from urllib.parse import quote
import aiohttp
import discord
from discord import app_commands

DISCOVERY_TOPIC = "macelimiter-discovery-v5"
RELAYS = [x.rstrip("/") for x in os.getenv("MACE_RELAYS", "https://ntfy.jae.fi,https://ntfy.sh").split(",") if x.strip()]
DISCORD_TOKEN = os.environ["DISCORD_TOKEN"]
ALLOWED_ROLE_IDS = {int(x) for x in os.getenv("MACE_ALLOWED_ROLE_IDS", "").split(",") if x.strip().isdigit()}

def decode(value):
    return base64.urlsafe_b64decode(value + "=" * ((4 - len(value) % 4) % 4)).decode("utf-8")

class MaceBridge:
    def __init__(self):
        self.target = None
        self.lock = asyncio.Lock()

    async def discover(self, http):
        candidates = []
        for relay in RELAYS:
            try:
                url = f"{relay}/{DISCOVERY_TOPIC}/json?poll=1&since=all"
                async with http.get(url, timeout=5) as response:
                    if response.status != 200:
                        continue
                    for line in (await response.text()).splitlines():
                        try:
                            event = json.loads(line)
                            parts = event.get("message", "").split("|", 6)
                            if len(parts) == 7 and parts[0] == "DISCOVER" and parts[5] == "ONLINE":
                                candidates.append({"name": decode(parts[2]), "version": parts[3], "node": decode(parts[4]), "token": decode(parts[6])})
                        except (ValueError, UnicodeError, json.JSONDecodeError):
                            pass
            except (aiohttp.ClientError, asyncio.TimeoutError):
                pass
        self.target = candidates[-1] if candidates else None
        return self.target

    async def publish(self, http, target, message):
        async with http.post(target["node"], data=message.encode(), headers={"Content-Type": "text/plain; charset=utf-8"}, timeout=5) as response:
            if response.status // 100 != 2:
                raise RuntimeError(f"bridge publish failed: HTTP {response.status}")

    async def poll(self, http, target, predicate, timeout=5):
        since = "all"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                url = f'{target["node"]}/json?poll=1&since={quote(since)}'
                async with http.get(url, timeout=5) as response:
                    if response.status != 200:
                        await asyncio.sleep(.25)
                        continue
                    for line in (await response.text()).splitlines():
                        try:
                            event = json.loads(line)
                            if event.get("id"):
                                since = event["id"]
                            message = event.get("message", "")
                            if predicate(message):
                                return message
                        except (ValueError, json.JSONDecodeError):
                            pass
            except (aiohttp.ClientError, asyncio.TimeoutError):
                pass
            await asyncio.sleep(.25)
        return None

    async def authenticate(self, http, target):
        session_id = uuid.uuid4().hex
        await self.publish(http, target, f'AUTH|{session_id}|{target["token"]}')
        if not await self.poll(http, target, lambda m: m == f"AUTHOK|{session_id}", 4):
            raise RuntimeError("target did not authenticate the Discord bot")
        return session_id

    async def count(self, http, target):
        async with self.lock:
            sid = await self.authenticate(http, target)
            await self.publish(http, target, f"GETSTATE|{sid}")
            response = await self.poll(http, target, lambda m: m.startswith(f"COUNT|{sid}|"), 4)
            if not response:
                raise RuntimeError("target did not return a mace count")
            return int(response.split("|", 2)[2])

    async def command(self, http, target, command):
        async with self.lock:
            sid = await self.authenticate(http, target)
            await self.publish(http, target, f'CMD|{sid}|{command.lstrip("/")}')
            return sid

class MaceBot(discord.Client):
    def __init__(self):
        super().__init__(intents=discord.Intents.none())
        self.tree = app_commands.CommandTree(self)
        self.http = None
        self.bridge = MaceBridge()

    async def setup_hook(self):
        await self.tree.sync()
        self.http = aiohttp.ClientSession()

    async def close(self):
        if self.http:
            await self.http.close()
        await super().close()

bot = MaceBot()

def authorized(interaction):
    if interaction.guild is None:
        return False
    if interaction.user.guild_permissions.administrator:
        return True
    return any(role.id in ALLOWED_ROLE_IDS for role in interaction.user.roles)

async def target():
    result = await bot.bridge.discover(bot.http)
    if result is None:
        raise RuntimeError("no MaceLimiter target server is online")
    return result

@bot.tree.command(name="maces", description="Show the current global mace count.")
async def maces(interaction):
    if not authorized(interaction):
        return await interaction.response.send_message("You are not authorized.", ephemeral=True)
    await interaction.response.defer(ephemeral=True)
    try:
        t = await target()
        count = await bot.bridge.count(bot.http, t)
        await interaction.followup.send(f'🟢 **{t["name"]}** — Maces: **{count}/6**', ephemeral=True)
    except Exception as e:
        await interaction.followup.send(f"❌ {e}", ephemeral=True)

@bot.tree.command(name="mc", description="Run a Minecraft console command on the active MaceLimiter server.")
@app_commands.describe(command="Minecraft console command")
async def mc(interaction, command: str):
    if not authorized(interaction):
        return await interaction.response.send_message("You are not authorized.", ephemeral=True)
    if len(command) > 4096:
        return await interaction.response.send_message("Command is too long.", ephemeral=True)
    await interaction.response.defer(ephemeral=True)
    try:
        t = await target()
        await bot.bridge.command(bot.http, t, command)
        await interaction.followup.send(f'✅ Sent to **{t["name"]}**: /{command.lstrip("/")}', ephemeral=True)
    except Exception as e:
        await interaction.followup.send(f"❌ {e}", ephemeral=True)

@bot.tree.command(name="mcstatus", description="Show the active MaceLimiter target server.")
async def mcstatus(interaction):
    if not authorized(interaction):
        return await interaction.response.send_message("You are not authorized.", ephemeral=True)
    await interaction.response.defer(ephemeral=True)
    try:
        t = await target()
        await interaction.followup.send(f'🟢 **{t["name"]}** - MaceLimiter {t["version"]} - bridge online', ephemeral=True)
    except Exception as e:
        await interaction.followup.send(f"🔴 {e}", ephemeral=True)

if __name__ == "__main__":
    bot.run(DISCORD_TOKEN)
