package com.dudebehinddude.database

import org.jetbrains.exposed.sql.Table

object Users : Table("userdata") {
    val userid = long("userid").uniqueIndex()
    val birthday = text("birthday")
    val birthdayCreatorId = long("birthday_creator_id")
}