package com.dudebehinddude.discord.handlers

import discord4j.core.GatewayDiscordClient

interface RegistrableHandler {
    fun register(gateway: GatewayDiscordClient)
}