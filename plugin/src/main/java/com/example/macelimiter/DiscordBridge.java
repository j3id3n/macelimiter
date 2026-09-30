package com.example.macelimiter;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;

import java.util.List;

public final class DiscordBridge extends ListenerAdapter {
    private final MaceLimiterPlugin plugin;
    private final String token;
    private volatile JDA jda;

    public DiscordBridge(MaceLimiterPlugin plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start() {
        jda = JDABuilder.createDefault(token)
                .addEventListeners(this)
                .build();
        plugin.getLogger().info("Discord bot is connecting...");
    }

    public void stop() {
        JDA current = jda;
        jda = null;
        if (current != null) {
            try {
                current.shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        JDA api = event.getJDA();
        api.getPresence().setActivity(Activity.playing("/maces"));
        registerCommands(api);
        plugin.getLogger().info("Discord bot connected as " + api.getSelfUser().getAsTag() + ".");
    }

    private void registerCommands(JDA api) {
        var commands = List.of(
                Commands.slash("maces", "Show the current global mace count."),
                Commands.slash("mcstatus", "Show the Minecraft server status."),
                Commands.slash("mc", "Run a Minecraft console command.")
                        .addOption(OptionType.STRING, "command", "Minecraft console command", true)
        );

        String guildId = plugin.getConfig().getString("discord.guild-id", "").trim();
        if (!guildId.isEmpty()) {
            Guild guild = api.getGuildById(guildId);
            if (guild == null) {
                plugin.getLogger().warning("discord.guild-id is set, but that Discord server is not visible to the bot.");
                return;
            }
            guild.updateCommands().addCommands(commands).queue(
                    unused -> plugin.getLogger().info("Discord slash commands registered in guild " + guild.getName() + "."),
                    error -> plugin.getLogger().warning("Failed to register Discord guild commands: " + error.getMessage())
            );
        } else {
            api.updateCommands().addCommands(commands).queue(
                    unused -> plugin.getLogger().info("Discord global slash commands registered."),
                    error -> plugin.getLogger().warning("Failed to register Discord global commands: " + error.getMessage())
            );
        }
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null) {
            event.reply("Use these commands inside a Discord server.").setEphemeral(true).queue();
            return;
        }

        switch (event.getName()) {
            case "maces" -> handleMaces(event);
            case "mcstatus" -> handleStatus(event);
            case "mc" -> handleCommand(event);
            default -> { }
        }
    }

    private void handleMaces(SlashCommandInteractionEvent event) {
        event.deferReply().setEphemeral(true).queue(hook -> {
            Integer count = plugin.getDiscordMaceCount();
            hook.editOriginal(count == null
                    ? "❌ Mace count is temporarily unavailable."
                    : "🟢 Maces: **" + count + "/6**").queue();
        });
    }

    private void handleStatus(SlashCommandInteractionEvent event) {
        event.deferReply().setEphemeral(true).queue(hook -> {
            String status = plugin.getDiscordStatus();
            hook.editOriginal(status == null
                    ? "❌ Server status is temporarily unavailable."
                    : "🟢 **MaceLimiter**\n" + status).queue();
        });
    }

    private void handleCommand(SlashCommandInteractionEvent event) {
        String command = event.getOption("command") == null ? "" : event.getOption("command").getAsString();
        if (!plugin.dispatchDiscordCommand(command)) {
            event.reply("❌ Invalid or unavailable Minecraft command.").setEphemeral(true).queue();
            return;
        }
        event.reply("✅ Minecraft command sent.").setEphemeral(true).queue();
    }
}
