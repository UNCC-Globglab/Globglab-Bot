package com.dudebehinddude.discord.handlers

import discord4j.core.GatewayDiscordClient

abstract class RegistrableHandler {
    abstract fun register(gateway: GatewayDiscordClient)
}