package com.dudebehinddude.discord.handlers

import com.dudebehinddude.annotations.Handler
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import reactor.core.publisher.Mono

@Handler
class MentionHandler : RegistrableHandler {
    override fun register(gateway: GatewayDiscordClient) {
        gateway.on(MessageCreateEvent::class.java)
            .flatMap { event ->
                val message = event.message
                val botId = message.client.selfId
                println(message.content)

                if (message.author.isPresent && message.author.get().isBot) {
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