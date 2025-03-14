package com.dudebehinddude.discord.handlers

import com.dudebehinddude.discord.handlers.RegisterableHandler
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.User
import reactor.core.publisher.Mono

class Mention : RegisterableHandler() {
    override fun register(gateway: GatewayDiscordClient) {
        gateway.on(MessageCreateEvent::class.java)
            .flatMap { event ->
                val message = event.message
                val botId = message.client.selfId

                if (message.author == gateway.self) {
                    return@flatMap Mono.empty<GatewayDiscordClient>()
                }

                // Check if bot was mentioned
                if (message.userMentionIds.contains(botId)) {
                    message.channel.flatMap { channel ->
                        channel.createMessage("Hi!")
                    }
                } else {
                    Mono.empty()
                }
            }
            .subscribe()
    }

}