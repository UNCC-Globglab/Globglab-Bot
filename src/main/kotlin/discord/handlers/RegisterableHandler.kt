package com.dudebehinddude.discord.handlers

import discord4j.core.DiscordClient
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.entity.User

abstract class RegisterableHandler {
    abstract fun register(gateway: GatewayDiscordClient)
}