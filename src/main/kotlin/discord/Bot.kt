package com.dudebehinddude.discord

import com.dudebehinddude.discord.handlers.Mention
import com.dudebehinddude.discord.handlers.RegisterableHandler
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import io.github.cdimascio.dotenv.dotenv

class Bot {
    // Attempt to get discord token
    private val dotenv = dotenv()
    private val token =
        dotenv["DISCORD_TOKEN"] ?: System.getenv("DISCORD_TOKEN") ?: error("No DISCORD_TOKEN env variable!")
    private val client: DiscordClient = DiscordClient.create(token)

    /**
     * This function is where all the event handlers for the bot are registered.
     */
    private fun registerHandlers(gateway: GatewayDiscordClient) {
        // All classes to register
        val toRegister: List<RegisterableHandler> = listOf(
            Mention(),
        )

        for (item in toRegister) {
            item.register(gateway)
        }
    }

    /**
     * Starts the bot.
     */
    fun start() {
        // Create a gateway connection and maintain it
        val gateway = client.login().block() ?: throw Exception("Unable to login!")
        registerHandlers(gateway)

        // This will keep the connection alive until the program is terminated
        gateway.onDisconnect().block()
    }
}