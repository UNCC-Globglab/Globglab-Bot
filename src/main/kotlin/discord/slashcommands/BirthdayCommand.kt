package com.dudebehinddude.discord.slashcommands

import com.dudebehinddude.annotations.SlashCommand
import com.dudebehinddude.database.Users
import discord.slashcommands.RegisterableSlashCommand
import discord4j.common.util.Snowflake
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.ApplicationCommandInteractionOptionValue
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.discordjson.json.ApplicationCommandRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import reactor.core.publisher.Mono
import java.time.DateTimeException
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import kotlin.jvm.optionals.getOrNull

@SlashCommand
class BirthdayCommand : RegisterableSlashCommand {
    override val name = "birthday"

    override fun builder(): ApplicationCommandRequest {
        return ApplicationCommandRequest.builder()
            .name(name)
            .description("Displays and manages birthdays.")
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("display")
                    .description("Displays someone's birthday.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("The user whose birthday to display.")
                            .type(ApplicationCommandOption.Type.USER.value)
                            .required(true)
                            .build()
                    ).build()
            )
            // Add the 'set' subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("add")
                    .description("Sets your birthday or suggests another user's birthday.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("month")
                            .description("The month of the birthday (1-12).")
                            .type(ApplicationCommandOption.Type.INTEGER.value)
                            .required(true)
                            .build()
                    )
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("day")
                            .description("The day of the birthday (1-31).")
                            .type(ApplicationCommandOption.Type.INTEGER.value)
                            .required(true)
                            .build()
                    )
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("year")
                            .description("The year of the birthday (optional).")
                            .type(ApplicationCommandOption.Type.INTEGER.value)
                            .required(false)
                            .build()
                    )
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("The user whose birthday to suggest. Leave empty to set your own.")
                            .type(ApplicationCommandOption.Type.USER.value)
                            .required(false)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    override fun execute(event: ChatInputInteractionEvent): Mono<Void> {
        val subcommand = event.options.firstOrNull()
        return when (subcommand?.name) {
            "display" -> getBirthday(event, subcommand)
            "add" -> addBirthday(event, subcommand)
            else -> event.reply("Invalid command usage. Please use `/birthday display` or `/birthday add`.")
        }
    }

    private fun getBirthday(
        event: ChatInputInteractionEvent,
        subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        return subcommand.getOption("user")
            .flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .get()
            .flatMap { user ->
                val userId = user.id.asLong()

                val birthdayData = transaction {
                    Users.selectAll()
                        .where(Users.userid eq userId)
                        .map { row ->
                            Pair(
                                row[Users.birthday],
                                row[Users.birthdayCreatorId]
                            )
                        }
                        .firstOrNull()
                }

                if (birthdayData == null) {
                    return@flatMap event.reply("**${user.globalName.orElse(user.username)}** does not have a birthday. Perhaps suggest one with `/birthday add`?")
                }

                birthdayData.let { (birthday, creatorId) ->
                    val client = event.client
                    return@flatMap client.getUserById(Snowflake.of(creatorId)).flatMap { creatorUser ->
                        val creatorUserName = creatorUser.globalName.orElse(creatorUser.username)
                        event.reply("Birthday: $birthday. Suggested by $creatorUserName.")
                    }
                }
            }
    }

    private fun addBirthday(
        event: ChatInputInteractionEvent,
        subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        return subcommand.getOption("user")
            .flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .orElse(Mono.just(event.interaction.user))
            .flatMap { user ->
                val queryUserId = user.id.asLong()
                val queryUserName = user.globalName.orElse(user.username)
                val callerUserId = event.interaction.user.id.asLong()

                if (user.isBot) {
                    return@flatMap event.reply("$queryUserName is a bot! Bot's can't have birthdays!")
                }

                val day = subcommand.getOption("day")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asLong)
                    .get()
                val month = subcommand.getOption("month")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asLong)
                    .get()
                val year = subcommand.getOption("year")
                    .flatMap(ApplicationCommandInteractionOption::getValue)
                    .map(ApplicationCommandInteractionOptionValue::asLong)
                    .getOrNull()

                // validate month as type Month
                val monthMonth: Month = try {
                    Month.of(month.toInt())
                } catch (e: DateTimeException) {
                    return@flatMap event.reply("**${month.toInt()}** is not a month.")
                }

                if (year != null) {
                    // Year is provided, validate as LocalDate
                    try {
                        LocalDate.of(year.toInt(), monthMonth, day.toInt())
                    } catch (e: DateTimeException) {
                        return@flatMap event.reply("Invalid date (year, month, day): $year, $monthMonth, $day - ${e.message}")
                    }
                } else {
                    // Year is not provided, validate as MonthDay
                    try {
                        MonthDay.of(monthMonth, day.toInt())
                    } catch (e: DateTimeException) {
                        return@flatMap event.reply("Invalid date (month, day): $monthMonth, $day - ${e.message}")
                    }
                }

                var dateString = "$month/$day"
                if (year != null) dateString += "/$year"

                transaction {
                    val existingBirthdayCreator = Users.selectAll()
                        .where(Users.userid eq queryUserId)
                        .map { row ->
                            row[Users.birthdayCreatorId]
                        }
                        .firstOrNull()

                    if (existingBirthdayCreator != null && existingBirthdayCreator != callerUserId) {
                        // Throw an exception so the transaction does not continue
                        throw IllegalStateException("$queryUserName has already set their birthday, so you cannot suggest one.")
                    }

                    Users.upsert {
                        it[userid] = queryUserId
                        it[birthday] = dateString
                        it[birthdayCreatorId] = callerUserId
                    }
                }

                event.reply("Set $queryUserName's birthday to $dateString!")
                    .onErrorResume(IllegalStateException::class.java) { e ->
                        event.reply("${e.message}")
                    }
            }
    }

}