package com.dudebehinddude.annotations

import com.dudebehinddude.discord.handlers.RegistrableHandler

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Handler {
    companion object {
        fun findAll(packageName: String = "com.dudebehinddude"): Set<Class<out RegistrableHandler>> {
            val reflections = org.reflections.Reflections(packageName)
            // Get classes with annotation and filter to only those extending RegistrableHandler
            @Suppress("UNCHECKED_CAST")
            return reflections.getTypesAnnotatedWith(Handler::class.java)
                .filter { RegistrableHandler::class.java.isAssignableFrom(it) }
                .map { it as Class<out RegistrableHandler> }
                .toSet()
        }
    }
}
