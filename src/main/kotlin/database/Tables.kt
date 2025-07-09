package com.dudebehinddude.database

import org.jetbrains.exposed.sql.Table

object Users : Table("userdata") {
    val userid = long("userid").uniqueIndex()
    val birthDay = integer("birth_day").nullable()
    val birthMonth = integer("birth_month").nullable()
    val birthYear = integer("birth_year").nullable()
    val birthdayCreatorId = long("birthday_creator_id").nullable()
}