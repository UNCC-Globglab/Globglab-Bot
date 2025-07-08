package com.dudebehinddude.annotations

import discord.slashcommands.RegisterableSlashCommand

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class SlashCommand {
    companion object {
        fun findAll(packageName: String = "com.dudebehinddude"): Set<Class<out RegisterableSlashCommand>> {
            val reflections = org.reflections.Reflections(packageName)
            // Get classes with annotation and filter to only those extending RegisterableSlashCommand
            @Suppress("UNCHECKED_CAST")
            return reflections.getTypesAnnotatedWith(SlashCommand::class.java)
                .filter { RegisterableSlashCommand::class.java.isAssignableFrom(it) }
                .map { it as Class<out RegisterableSlashCommand> }
                .toSet()
        }
    }
}
