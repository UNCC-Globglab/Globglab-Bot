package com.dudebehinddude.discord.handlers

import com.dudebehinddude.annotations.Handler
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.message.MessageCreateEvent
import discord4j.core.`object`.entity.Message
import reactor.core.publisher.Mono
import kotlin.jvm.optionals.getOrNull

@Handler
class MentionHandler : RegistrableHandler {
    override fun register(gateway: GatewayDiscordClient) {
        gateway.on(MessageCreateEvent::class.java)
            .flatMap { event ->
                val message = event.message
                val botId = message.client.selfId

                if (message.author.getOrNull()?.isBot != false) {
                    return@flatMap Mono.empty()
                }
                if (message.flags.contains(Message.Flag.SUPPRESS_NOTIFICATIONS)) {
                    return@flatMap Mono.empty()
                }

                // Check if bot was mentioned
                if (message.userMentionIds.contains(botId) && message.content.contains("<@${botId.asString()}>")) {
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