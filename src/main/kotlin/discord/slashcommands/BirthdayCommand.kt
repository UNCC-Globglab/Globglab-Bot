package com.dudebehinddude.discord.slashcommands

import com.dudebehinddude.annotations.SlashCommand
import com.dudebehinddude.database.Users
import discord.slashcommands.RegisterableSlashCommand
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.ApplicationCommandInteractionOptionValue
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.core.`object`.entity.User
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
            .flatMap { user -> getBirthdayInfo(user, event.client) }
            .flatMap { message -> event.reply(message) }
            .onErrorResume { error -> event.reply(error.message ?: "An unknown error occurred.") }
    }

    private fun getBirthdayInfo(user: User, gatewayDiscordClient: GatewayDiscordClient): Mono<String> {
        val userId = user.id.asLong()
        return Mono.fromCallable {
            transaction {
                Users.selectAll()
                    .where(Users.userid eq userId)
                    .map { row ->
                        Pair(
                            Triple(
                                row[Users.birthMonth],
                                row[Users.birthDay],
                                row[Users.birthYear],
                            ),
                            row[Users.birthdayCreatorId]
                        )
                    }
                    .firstOrNull()
            } ?: Pair(null, null) // because otherwise the next part won't run if it's null apparently...?
        }.flatMap { birthdayData ->
            val dateData = birthdayData.first
            val birthdayCreatorId = birthdayData.second

            if (dateData?.first == null || dateData.second == null || birthdayCreatorId == null) {
                return@flatMap Mono.error(IllegalStateException("${getUserName(user)} does not have a birthday. Perhaps suggest one with `/birthday add`?"))
            }

            var birthdayString = "${dateData.first}/${dateData.second}"
            if (dateData.third != null) birthdayString += "/${dateData.third}"

            gatewayDiscordClient.getUserById(Snowflake.of(birthdayCreatorId)).flatMap { birthdayCreatorUser ->
                val suggestionString = if (birthdayCreatorId == userId) {
                    "Verified by ${getUserName(birthdayCreatorUser)}."
                } else {
                    "Suggested by ${getUserName(birthdayCreatorUser)}."
                }

                Mono.just("${getUserName(user)}'s birthday is $birthdayString.\n$suggestionString")
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
            .flatMap { user -> validateCanHaveBirthday(user) }
            .flatMap { user -> parseDateInput(subcommand).map { user to it } }
            .flatMap { (user, dateInput) ->
                updateBirthday(user, event, dateInput)
            }
            .flatMap { successMessage -> event.reply(successMessage) }
            .onErrorResume { error -> event.reply(error.message ?: "An unknown error occurred.") }
    }

    private fun validateCanHaveBirthday(user: User): Mono<User> {
        return if (user.isBot) {
            Mono.error(IllegalArgumentException("${getUserName(user)} is a bot! Bots can't have birthdays!"))
        } else {
            Mono.just(user)
        }
    }

    private fun parseDateInput(subcommand: ApplicationCommandInteractionOption): Mono<Triple<Int, Int, Int?>> {
        return Mono.fromCallable {
            val month = subcommand.getOption("month")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .get()
            val day = subcommand.getOption("day")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .get()
            val year = subcommand.getOption("year")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong)
                .getOrNull()

            // validate month as type Month
            val monthEnum: Month = try {
                Month.of(month.toInt())
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("Could not parse date: ${e.message}.")
            }

            try {
                when (year) {
                    null -> MonthDay.of(monthEnum, day.toInt())
                    else -> LocalDate.of(year.toInt(), monthEnum, day.toInt())
                }
            } catch (e: DateTimeException) {
                throw IllegalArgumentException("Could not parse date: ${e.message}")
            }

            Triple(month.toInt(), day.toInt(), year?.toInt())
        }
    }

    private fun updateBirthday(
        user: User,
        event: ChatInputInteractionEvent,
        dateInput: Triple<Int, Int, Int?>
    ): Mono<String> {
        return Mono.fromCallable {
            val eventCallerId = event.interaction.user.id.asLong()
            val userQueryId = user.id.asLong()
            transaction {
                val existingBirthdayCreator = Users.selectAll()
                    .where(Users.userid eq userQueryId)
                    .map { row ->
                        row[Users.birthdayCreatorId]
                    }
                    .firstOrNull()

                if (existingBirthdayCreator != null && existingBirthdayCreator != eventCallerId) {
                    // Throw an exception so the transaction does not continue
                    throw IllegalStateException("${getUserName(user)} has already set their birthday, so you cannot suggest one.")
                }

                Users.upsert {
                    it[userid] = userQueryId
                    it[birthMonth] = dateInput.first
                    it[birthDay] = dateInput.second
                    it[birthYear] = dateInput.third
                    it[birthdayCreatorId] = eventCallerId
                }
            }

            // return success string
            return@fromCallable if (dateInput.third == null) {
//                val monthDay = MonthDay.of(dateInput.first, dateInput.second)
                "Set ${getUserName(user)}'s birthday to ${dateInput.first}/${dateInput.second}!"
            } else {
//                val localDate = LocalDate.of(dateInput.first, dateInput.second, dateInput.third!!)
                "Set ${getUserName(user)}'s birthday to ${dateInput.first}/${dateInput.second}/${dateInput.third}!"
            }
        }
    }

    /**
     * Temporary method until I set up embeds. Gets the user's username as an alternative to pinging them.
     */
    private fun getUserName(user: User): String {
        return user.globalName.orElse(user.username)
    }
}
