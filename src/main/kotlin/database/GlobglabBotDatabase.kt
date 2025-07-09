package com.dudebehinddude.database

import io.github.cdimascio.dotenv.dotenv
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object GlobglabBotDatabase {
    private const val DEFAULT_DB_FILE_NAME = "globglabbot_data.db"
    private const val FALLBACK_DB_DIR = "./data"

    fun init() {
        val dotenv = dotenv { ignoreIfMissing = true }

        val dbDir = dotenv["DB_DIR"] ?: FALLBACK_DB_DIR
        val dbFileName = dotenv["DB_FILE_NAME"] ?: DEFAULT_DB_FILE_NAME

        val dbFile = File(dbDir, dbFileName)
        dbFile.parentFile?.mkdirs()

        Database.connect("jdbc:sqlite:${dbFile.absolutePath}", "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(Users)
            SchemaUtils.addMissingColumnsStatements(Users).forEach { statement ->
                exec(statement)
            }
        }
    }
}