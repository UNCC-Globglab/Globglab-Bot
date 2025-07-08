package discord.slashcommands

import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.discordjson.json.ApplicationCommandRequest
import reactor.core.publisher.Mono

interface RegisterableSlashCommand {
    /**
     * The name of the slash command.
     */
    val name: String

    /**
     * Builds the ApplicationCommandRequest for registration with discord.
     */
    fun builder(): ApplicationCommandRequest

    /**
     * Executes when the command is called.
     */
    fun execute(event: ChatInputInteractionEvent): Mono<Void>
}
