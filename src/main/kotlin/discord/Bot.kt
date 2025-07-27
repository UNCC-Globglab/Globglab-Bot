package com.dudebehinddude.discord

import com.dudebehinddude.annotations.Handler
import com.dudebehinddude.schedulers.BirthdayScheduler
import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import io.github.cdimascio.dotenv.dotenv

class Bot {
    // Attempt to get discord token
    private val dotenv = dotenv { ignoreIfMissing = true }
    private val token =
        dotenv["DISCORD_TOKEN"] ?: System.getenv("DISCORD_TOKEN") ?: error("No DISCORD_TOKEN env variable!")
    private val client: DiscordClient = DiscordClient.create(token)

    /**
     * This function is where all the event handlers for the bot are registered.
     */
    private fun registerHandlers(gateway: GatewayDiscordClient) {
        // Gets all handlers annotated with @Handler.
        val toRegister = Handler.findAll()

        for (handlerClass in toRegister) {
            val handler = handlerClass.getDeclaredConstructor().newInstance()
            handler.register(gateway)
        }
    }

    private fun registerSchedulers(gateway: GatewayDiscordClient) {
        BirthdayScheduler(gateway)
    }

    /**
     * Starts the bot.
     */
    fun start() {
        // Create a gateway connection and maintain it
        val gateway = client.login().block() ?: throw Exception("Unable to login!")
        registerHandlers(gateway)
        registerSchedulers(gateway)

        // This will keep the connection alive until the program is terminated
        gateway.onDisconnect().block()
    }
}