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
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.discordjson.json.ApplicationCommandRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert
import reactor.core.publisher.Mono
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import kotlin.jvm.optionals.getOrNull

@SlashCommand
class BirthdayCommand : RegisterableSlashCommand {
    override val name = "birthday"

    override fun builder(): ApplicationCommandRequest {
        return ApplicationCommandRequest.builder()
            .name(name)
            .description("Displays and manages birthdays.")
            // display subcommand
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
                            .required(false)
                            .build()
                    ).build()
            )
            // set subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("set")
                    .description("Sets your birthday or suggests another user's birthday.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("month")
                            .description("The month of the birthday (1-12).")
                            .type(ApplicationCommandOption.Type.INTEGER.value).required(true)
                            .build()
                    ).addOption(
                        ApplicationCommandOptionData.builder()
                            .name("day")
                            .description("The day of the birthday (1-31).")
                            .type(ApplicationCommandOption.Type.INTEGER.value).required(true)
                            .build()
                    ).addOption(
                        ApplicationCommandOptionData.builder()
                            .name("year")
                            .description("The year of the birthday (optional).")
                            .type(ApplicationCommandOption.Type.INTEGER.value).required(false)
                            .build()
                    ).addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("The user whose birthday to suggest. Leave empty to set your own.")
                            .type(ApplicationCommandOption.Type.USER.value).required(false)
                            .build()
                    ).build()
            ).build()
    }

    override fun execute(event: ChatInputInteractionEvent): Mono<Void> {
        val subcommand = event.options.firstOrNull()
        return when (subcommand?.name) {
            "display" -> birthdayDisplay(subcommand, event)
            "set" -> addBirthday(event, subcommand)
            else -> throw IllegalArgumentException("Invalid command usage. Run `/birthday help` for details.")
        }
    }

    private fun birthdayDisplay(
        subcommand: ApplicationCommandInteractionOption, event: ChatInputInteractionEvent
    ): Mono<Void> {
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .orElse(Mono.empty())
            .flatMap { user -> getBirthdayInfo(user, event.client) }
            .switchIfEmpty (getAllBirthdays())
            .flatMap { replySpec -> event.reply(replySpec) }
    }

    /**
     * Executed when `/birthday display` is run without a user specified
     */
    private fun getAllBirthdays(): Mono<InteractionApplicationCommandCallbackSpec> {
        return Mono.fromCallable {
            transaction {
                Users.selectAll().map { row ->
                    val month = row[Users.birthMonth]
                    val day = row[Users.birthDay]
                    val year = row[Users.birthYear]
                    val userId = row[Users.userid]
                    val birthdayCreatorId = row[Users.birthdayCreatorId]

                    Triple(month, day, year) to (userId to birthdayCreatorId)
                }.filter { (dateData, userData) ->
                    // Filter out entries where month, day, or birthdayCreatorId are null
                    dateData.first != null && dateData.second != null && userData.second != null
                }.sortedWith(
                    compareBy(
                        // Primary sort by month, secondary sort by day
                        { it.first.first!! },
                        { it.first.second!! }
                    ))
            }
        }.flatMap { birthdays ->
            var embed = EmbedCreateSpec.builder()
                .title("\uD83C\uDF82  Registered Birthdays")
                .description("Want your birthday in this list? Do so with `/birthday set`.")
                .timestamp(Instant.now())

            val formatter = DateTimeFormatter.ofPattern("MMMM d")
            var currentMonth = 0
            var currentFieldDescription = ""

            for (birthday in birthdays) {
                val birthdayDate = birthday.first
                val birthdayUserId = birthday.second.first
                val birthdayCreatorId = birthday.second.second

                val birthdayMonthDay = MonthDay.of(birthdayDate.first!!, birthdayDate.second!!)

                // init this here so that it doesn't error if there aren't any birthdays yet
                if (currentMonth == 0) {
                    currentMonth = birthdayDate.first!!
                }

                // create embed field and reset current values
                if (currentMonth != birthdayDate.first!!) {
                    embed = embed.addField(
                        Month.of(currentMonth).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                        currentFieldDescription,
                        false
                    )
                    currentMonth = birthdayDate.first!!
                    currentFieldDescription = ""
                }

                // create current list item
                currentFieldDescription += "- <@$birthdayUserId> - ${birthdayMonthDay.format(formatter)}"
                if (birthdayDate.third != null) {
                    currentFieldDescription += ", " + birthdayDate.third
                }
                currentFieldDescription += if (birthdayUserId == birthdayCreatorId) {
                    "\n"
                } else {
                    " (suggested by <@$birthdayCreatorId>)\n"
                }
            }

            // Add final field
            if (currentMonth != 0) {
                embed = embed.addField(
                    Month.of(currentMonth).getDisplayName(TextStyle.FULL, Locale.getDefault()),
                    currentFieldDescription,
                    false
                )
            }

            Mono.just(InteractionApplicationCommandCallbackSpec.builder()
                .addEmbed(embed.build())
                .build()
            )
        }
    }

    /**
     * Executed when `/birthday display` is run with a user specified
     */
    private fun getBirthdayInfo(
        user: User,
        gatewayDiscordClient: GatewayDiscordClient
    ): Mono<InteractionApplicationCommandCallbackSpec> {
        val userId = user.id.asLong()
        var embed = EmbedCreateSpec.builder()
            .title("\uD83C\uDF82  Birthday Information")
            .timestamp(Instant.now())

        return Mono.fromCallable {
            transaction {
                Users.selectAll().where(Users.userid eq userId).map { row ->
                    Pair(
                        Triple(
                            row[Users.birthMonth],
                            row[Users.birthDay],
                            row[Users.birthYear],
                        ), row[Users.birthdayCreatorId]
                    )
                }.firstOrNull()
            } ?: Pair(null, null) // because otherwise the next part won't run if it's null apparently...?
        }.flatMap { birthdayData ->
            val dateData = birthdayData.first
            val birthdayCreatorId = birthdayData.second

            if (dateData?.first == null || dateData.second == null || birthdayCreatorId == null) {
                embed = embed.description(
                    "<@$userId> does not have a birthday. Perhaps suggest one with `/birthday suggest`?"
                )

                val replySpec = InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed.build())
                    .build()

                return@flatMap Mono.just(replySpec)
            }

            val nextBirthday = getNextOccurrenceUnixSeconds(MonthDay.of(dateData.first!!, dateData.second!!))
            var birthdayString = "<@$userId>'s next birthday is <t:$nextBirthday:R> on <t:$nextBirthday:D>."
            if (dateData.third != null) {
                val dob = LocalDate.of(dateData.third!!, dateData.first!!, dateData.second!!)
                val age = Period.between(dob, LocalDate.now()).years
                birthdayString += " They are currently $age years old!"
            }

            gatewayDiscordClient.getUserById(Snowflake.of(birthdayCreatorId)).flatMap { birthdayCreatorUser ->
                if (birthdayCreatorId == userId) {
                    embed.footer(
                        "Verified by ${getUserName(birthdayCreatorUser)}", birthdayCreatorUser.avatarUrl
                    )
                } else {
                    birthdayString += "\n-# --\n-# Birthday suggested by <@$birthdayCreatorId>" +
                            "\n-# <@$userId> can verify this with `/birthday verify`."
                }
                embed = embed.description(birthdayString)


                val replySpec = InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed.build())
                    .build()

                Mono.just(replySpec)
            }
        }
    }

    private fun addBirthday(
        event: ChatInputInteractionEvent, subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser).orElse(Mono.just(event.interaction.user))
            .flatMap { user -> validateCanHaveBirthday(user) }
            .flatMap { user -> parseDateInput(subcommand).map { user to it } }.flatMap { (user, dateInput) ->
                updateBirthday(user, event, dateInput)
            }.flatMap { successMessage -> event.reply(successMessage) }
    }

    private fun validateCanHaveBirthday(user: User): Mono<User> {
        return if (user.isBot) {
            Mono.error(IllegalArgumentException("<@${user.id.asLong()}> is a bot! Bots can't have birthdays!"))
        } else {
            Mono.just(user)
        }
    }

    private fun parseDateInput(subcommand: ApplicationCommandInteractionOption): Mono<Triple<Int, Int, Int?>> {
        return Mono.fromCallable {
            val month = subcommand.getOption("month").flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong).get()
            val day = subcommand.getOption("day").flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong).get()
            val year = subcommand.getOption("year").flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asLong).getOrNull()

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
        user: User, event: ChatInputInteractionEvent, dateInput: Triple<Int, Int, Int?>
    ): Mono<String> {
        return Mono.fromCallable {
            val eventCallerId = event.interaction.user.id.asLong()
            val userQueryId = user.id.asLong()
            transaction {
                val existingBirthdayCreator = Users.selectAll().where(Users.userid eq userQueryId).map { row ->
                    row[Users.birthdayCreatorId]
                }.firstOrNull()

                if (existingBirthdayCreator != null && existingBirthdayCreator != eventCallerId) {
                    // Throw an exception so the transaction does not continue
                    throw IllegalStateException("${getUserName(user)} has already verified their birthday, so you cannot suggest one.")
                }

                Users.upsert {
                    it[userid] = userQueryId
                    it[birthMonth] = dateInput.first
                    it[birthDay] = dateInput.second
                    it[birthYear] = dateInput.third
                    it[birthdayCreatorId] = eventCallerId
                }
            }

            val userString = if (userQueryId == eventCallerId) {
                "your"
            } else {
                "<@$userQueryId>'s"
            }

            // return success string
            val successString = if (dateInput.third == null) {
//                val monthDay = MonthDay.of(dateInput.first, dateInput.second)
                "Set $userString birthday to ${dateInput.first}/${dateInput.second}!"
            } else {
//                val localDate = LocalDate.of(dateInput.first, dateInput.second, dateInput.third!!)
                "Set $userString birthday to ${dateInput.first}/${dateInput.second}/${dateInput.third}!"
            }

            successString
        }
    }

    /**
     * Temporary method until I set up embeds. Gets the user's username as an alternative to pinging them.
     */
    private fun getUserName(user: User): String {
        return user.globalName.orElse(user.username)
    }


    /**
     * Calculates the Unix seconds (because discord) of the next occurrence of a given MonthDay.
     * The "next occurrence" means strictly in the future. If the MonthDay is today,
     * the next occurrence will be in the following year.
     *
     * This will probably error on leap days, but I don't feel like handling that
     *
     * @param monthDay The MonthDay (e.g., MonthDay.of(Month.JULY, 10)).
     * @param zoneId The time zone to consider for "today" and for the start of the day.
     * Defaults to "America/New_York" (EDT/EST) for Charlotte, NC.
     * @return The Unix milliseconds (epoch milliseconds) of the next occurrence.
     */
    private fun getNextOccurrenceUnixSeconds(
        monthDay: MonthDay,
        zoneId: ZoneId = ZoneId.of("America/New_York")
    ): Long {
        // Get today's date in the specified timezone
        val today = LocalDate.now(zoneId)
        var targetYear = today.year
        val dateForCurrentTargetYear = LocalDate.of(targetYear, monthDay.month, monthDay.dayOfMonth)

        // Determine if the MonthDay for the current 'targetYear' has already passed or is today.
        // If it's today or has passed, the next occurrence must be in the next calendar year.
        if (!dateForCurrentTargetYear.isAfter(today)) {
            targetYear++
        }

        val nextOccurrenceDate = LocalDate.of(targetYear, monthDay.month, monthDay.dayOfMonth)
        return nextOccurrenceDate.atStartOfDay(zoneId).toInstant().toEpochMilli() / 1000
    }
}
