package com.dudebehinddude.discord.handlers

import com.dudebehinddude.annotations.SlashCommand
import com.dudebehinddude.annotations.Handler
import discord.slashcommands.RegisterableSlashCommand
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.rest.RestClient
import reactor.core.publisher.Mono

@Handler
class SlashCommandHandler : RegistrableHandler {
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


        // Register commands globally
        restClient.applicationService
            .bulkOverwriteGlobalApplicationCommand(appId, builders)
            .doOnNext { command ->
                println("Successfully registered command: ${command.name()}")
            }
            .subscribe()
    }
}