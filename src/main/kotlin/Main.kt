package com.dudebehinddude

import com.dudebehinddude.database.GlobglabBotDatabase
import com.dudebehinddude.discord.Bot

fun main() {
    // Init database
    GlobglabBotDatabase.init()

    // Start the discord bot
    val bot = Bot()
    bot.start()
}