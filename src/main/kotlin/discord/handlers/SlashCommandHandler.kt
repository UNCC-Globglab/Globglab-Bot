package com.dudebehinddude.discord.handlers

import com.dudebehinddude.annotations.Handler
import com.dudebehinddude.annotations.SlashCommand
import discord.slashcommands.RegisterableSlashCommand
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.component.Container
import discord4j.core.`object`.component.Separator
import discord4j.core.`object`.component.TextDisplay
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec
import discord4j.discordjson.json.ApplicationCommandData
import discord4j.rest.RestClient
import io.github.cdimascio.dotenv.dotenv
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

@Handler
class SlashCommandHandler : RegistrableHandler {
    companion object {
        private val dotenv = dotenv { ignoreIfMissing = true }
        private val guildIdStr: String? = dotenv["SLASH_COMMAND_GUILD_ID"] ?: System.getenv("SLASH_COMMAND_GUILD_ID")

        /**
         * Gets the Guild ID where commands are being registered to.
         *
         * @return The ID of the guild slash commands are registered to, or null if they are registered globally.
         */
        fun getCommandGuildID(): Long? {
            return guildIdStr?.toLong()
        }

        /**
         * Gets the Command ID of a slash command. Note that this requires a call to the Discord gateway,
         * which adds additional latency.
         *
         * @param gateway The discord gateway (used to query for the command id).
         * @param commandName The command to get the ID of.
         * @return The ID of the specified command, or null if it is not registered to the bot.
         */
        fun getCommandID(gateway: GatewayDiscordClient, commandName: String): Long? {
            val appId = gateway.selfId.asLong()
            val guildId = getCommandGuildID()
            val commands: Flux<ApplicationCommandData> = if (guildId != null) {
                gateway.restClient.applicationService.getGuildApplicationCommands(appId, guildId)
            } else {
                gateway.restClient.applicationService.getGlobalApplicationCommands(appId)
            }

            return commands
                .filter { it.name() == commandName }
                .blockFirst()
                ?.id()
                ?.asLong()
        }
    }

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
                    ?.onErrorResume { error ->
                        // Generate an error message when an error gets thrown during any command's execution
                        event.reply(getErrorCallbackSpec(error))
                    }
                    ?: Mono.empty()
            }
            .subscribe()
    }

    private fun registerCommands(restClient: RestClient, appId: Long, commands: Map<String, RegisterableSlashCommand>) {
        val builders = commands.values.map { it.builder() }
        val guildId = getCommandGuildID()

        // Register commands
        if (guildId == null) {
            restClient.applicationService
                .bulkOverwriteGlobalApplicationCommand(appId, builders)
                .doOnNext { command ->
                    println("Registered command globally: ${command.name()}")
                }
                .subscribe()
        } else {
            restClient.applicationService
                .bulkOverwriteGuildApplicationCommand(appId, guildId, builders)
                .doOnNext { command ->
                    println("Registered command to guild: ${command.name()}")
                }
                .subscribe()
        }
    }

    /**
     * Generates an error message when an error gets thrown during any command's execution, using discord's
     * new Components v2.
     *
     * @param error The error to generate a message for.
     * @return An `InteractionApplicationCommandCallbackSpec` that can be directly used in an `event.reply()`.
     */
    private fun getErrorCallbackSpec(error: Throwable): InteractionApplicationCommandCallbackSpec {
        val messageContainer = Container.of(TextDisplay.of("## Something went wrong"))
            .withAddedComponent(Separator.of())
            .withAddedComponent(
                TextDisplay.of(
                    error.message ?: "An unknown error occurred."
                )
            )

        return InteractionApplicationCommandCallbackSpec.builder()
            .addComponent(messageContainer)
            .ephemeral(true)
            .build()
    }
}