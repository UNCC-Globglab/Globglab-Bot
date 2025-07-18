package com.dudebehinddude.discord.slashcommands

import com.dudebehinddude.annotations.SlashCommand
import discord.slashcommands.RegisterableSlashCommand
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.component.Container
import discord4j.core.`object`.component.TextDisplay
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec
import discord4j.discordjson.json.ApplicationCommandRequest
import discord4j.gateway.GatewayClient
import reactor.core.publisher.Mono

@SlashCommand
class PingCommand : RegisterableSlashCommand {
    override val name = "ping"

    override fun builder(): ApplicationCommandRequest {
        return ApplicationCommandRequest.builder()
            .name(name)
            .description("Checks if the bot is online.")
            .build()
    }

    override fun execute(event: ChatInputInteractionEvent): Mono<Void> {
        val shard = event.shardInfo.index
        val client: GatewayClient = event.client.getGatewayClient(shard).get()
        val latency = client.responseTime

        val message = InteractionApplicationCommandCallbackSpec.builder()
            .addComponent(
                Container.of(
                    TextDisplay.of("Pong! Last heartbeat time: ${latency.toMillis()} ms.")
                )
            )
            .ephemeral(true)
            .build()

        return event.reply(message)
    }
}