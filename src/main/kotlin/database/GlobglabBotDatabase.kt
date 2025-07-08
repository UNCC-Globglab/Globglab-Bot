package com.dudebehinddude.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object GlobglabBotDatabase {
    private const val DB_FILE_NAME = "globglabbot_data.db"

    fun init() {
        val dbFile = File(DB_FILE_NAME)
        dbFile.parentFile?.mkdirs()
        Database.connect("jdbc:sqlite:$DB_FILE_NAME", "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(Users)
        }
    }
}