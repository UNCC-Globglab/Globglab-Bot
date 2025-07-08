package com.dudebehinddude.discord.handlers

import com.dudebehinddude.annotations.Handler
import com.dudebehinddude.annotations.SlashCommand
import discord.slashcommands.RegisterableSlashCommand
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.rest.RestClient
import io.github.cdimascio.dotenv.dotenv
import reactor.core.publisher.Mono

@Handler
class SlashCommandHandler : RegistrableHandler {
    private val dotenv = dotenv()
    private val guildId: String? = dotenv["SLASH_COMMAND_GUILD_ID"] ?: System.getenv("SLASH_COMMAND_GUILD_ID")

    override fun register(gateway: GatewayDiscordClient) {
        println("Registering Slash Commands")

        // Get all slash commands
        val allSlashCommands = SlashCommand.findAll()
            .map { slashCommandClass -> slashCommandClass.getDeclaredConstructor().newInstance() }
            .associateBy { it.name }

        println("Found slash commands: ${allSlashCommands.keys}")

        // Create the command definitions
        val appId = gateway.restClient.applicationId.block() ?: return
        registerCommands(gateway.restClient, appId, allSlashCommands)

        // Handle the slash command interactions
        gateway.on(ChatInputInteractionEvent::class.java)
            .flatMap { event ->
                allSlashCommands[event.commandName]
                    ?.execute(event)
                    ?.onErrorResume { e ->
                        println("Error executing command ${event.commandName}: ${e.message}")
                        event.reply("An error occurred while handling command: ${e.localizedMessage}")
                    }
                    ?: Mono.empty()
            }
            .subscribe()
    }

    private fun registerCommands(restClient: RestClient, appId: Long, commands: Map<String, RegisterableSlashCommand>) {
        val builders = commands.values.map { it.builder() }
        val parsedGuildId = guildId?.toLong()

        // Register commands
        if (parsedGuildId == null) {
            restClient.applicationService
                .bulkOverwriteGlobalApplicationCommand(appId, builders)
                .doOnNext { command ->
                    println("Registered command globally: ${command.name()}")
                }
                .subscribe()
        } else {
            restClient.applicationService
                .bulkOverwriteGuildApplicationCommand(appId, parsedGuildId, builders)
                .doOnNext { command ->
                    println("Registered command to guild: ${command.name()}")
                }
                .subscribe()
        }
    }
}