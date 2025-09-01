package com.dudebehinddude.discord.slashcommands

import com.dudebehinddude.annotations.SlashCommand
import com.dudebehinddude.database.Users
import com.dudebehinddude.util.getTimezone
import discord.slashcommands.RegisterableSlashCommand
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent
import discord4j.core.`object`.command.ApplicationCommandInteractionOption
import discord4j.core.`object`.command.ApplicationCommandInteractionOptionValue
import discord4j.core.`object`.command.ApplicationCommandOption
import discord4j.core.`object`.component.Container
import discord4j.core.`object`.component.TextDisplay
import discord4j.core.`object`.entity.User
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.InteractionApplicationCommandCallbackSpec
import discord4j.discordjson.json.ApplicationCommandOptionData
import discord4j.discordjson.json.ApplicationCommandRequest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
                    .description("Displays a list of all birthdays or birthday information about a specific user.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("If specified, displays a specific user's birthday and age.")
                            .type(ApplicationCommandOption.Type.USER.value)
                            .required(false)
                            .build()
                    ).build()
            )
            // set subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("set")
                    .description("Sets your birthday.")
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
                            .description("The year of the birthday.")
                            .type(ApplicationCommandOption.Type.INTEGER.value).required(false)
                            .build()
                    ).build()
            )
            // suggest subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("suggest")
                    .description("Suggests another user's birthday if they haven't set one yet.")
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
                            .name("user")
                            .description("The user whose birthday to suggest.")
                            .type(ApplicationCommandOption.Type.USER.value).required(true)
                            .build()
                    ).addOption(
                        ApplicationCommandOptionData.builder()
                            .name("year")
                            .description("The year of the birthday.")
                            .type(ApplicationCommandOption.Type.INTEGER.value).required(false)
                            .build()
                    ).build()
            )
            // remove subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("remove")
                    .description("Removes your birthday, or someone else's birthday if it isn't verified.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("Removes this user's birthday if it isn't verified.")
                            .type(ApplicationCommandOption.Type.USER.value)
                            .build()
                    )
                    .build()
            )
            // verify subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("verify")
                    .description("Verifies a birthday someone else set for you.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .build()
            )
            .build()
    }

    override fun execute(event: ChatInputInteractionEvent): Mono<Void> {
        val subcommand = event.options.firstOrNull()
        return when (subcommand?.name) {
            "display" -> displayBirthday(event, subcommand)
            "set" -> setBirthday(event, subcommand)
            "suggest" -> setBirthday(event, subcommand)
            "remove" -> removeBirthday(event, subcommand)
            "verify" -> verifyBirthday(event)
            else -> Mono.error(IllegalArgumentException("This command hasn't been implemented yet :("))
        }
    }

    /**
     * Handles the `/birthday display` subcommand.
     *
     * @param event The event of the command called.
     * @param subcommand The subcommand associated with the command called.
     */
    private fun displayBirthday(
        event: ChatInputInteractionEvent, subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        val commandID = event.commandId.asLong()
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .orElse(Mono.empty())
            .flatMap { user -> getBirthdayInfo(user, event.client, commandID) }
            .switchIfEmpty(getAllBirthdays(commandID))
            .flatMap { replySpec -> event.reply(replySpec) }
    }

    /**
     * Handles the `/birthday set` and `/birthday suggest` subcommands.
     *
     * @param event The event of the command called.
     * @param subcommand The subcommand associated with the command called.
     */
    private fun setBirthday(
        event: ChatInputInteractionEvent, subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser).orElse(Mono.just(event.interaction.user))
            .flatMap { user -> validateCanHaveBirthday(user) }
            .flatMap { user -> parseDateInput(subcommand).map { user to it } }
            .flatMap { (user, dateInput) -> updateBirthday(user, event, dateInput) }
            .flatMap { successMessage -> event.reply(successMessage) }
    }

    /**
     * Handles the `/birthday remove` subcommand.
     *
     * @param event The event of the command called.
     */
    private fun removeBirthday(
        event: ChatInputInteractionEvent,
        subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        val caller = event.user
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser).orElse(Mono.just(event.interaction.user))
            .flatMap { user -> remove(user, caller) }
            .flatMap { message -> event.reply(message) }
    }

    /**
     * Handles the `/birthday verify` subcommand.
     *
     * @param event The event of the command called.
     */
    private fun verifyBirthday(event: ChatInputInteractionEvent): Mono<Void> {
        return verify(event.user).flatMap { message -> event.reply(message) }
    }

    /**
     * Returns an `InteractionApplicationCommandCallbackSpec` with an embed
     * containing a list of all birthdays.
     * Executed when `/birthday display` is run without a user specified.
     *
     * @param commandID The command ID for creating slash command mentions
     * @return An `InteractionApplicationCommandCallbackSpec` with an embed
     * containing a list of all birthdays.
     */
    private fun getAllBirthdays(commandID: Long): Mono<InteractionApplicationCommandCallbackSpec> {
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
                .description("Want your birthday in this list? Add it with </birthday set:$commandID>.")
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

            Mono.just(
                InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed.build())
                    .build()
            )
        }
    }

    /**
     * Executed when `/birthday display` is run with a user specified
     *
     * @param user The user of the birthday to lookup
     * @param gatewayDiscordClient Used to get the user that set the birthday
     * @param commandID The command ID for generating slash command mentions
     * @return A `Mono<InteractionApplicationCommandCallbackSpec>` containing
     * an embed with birthday information for the user specified.
     */
    private fun getBirthdayInfo(
        user: User,
        gatewayDiscordClient: GatewayDiscordClient,
        commandID: Long
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
                    "<@$userId> does not have a birthday. Perhaps suggest one with </birthday suggest:$commandID>?"
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
                val age = Period.between(dob, LocalDate.now(getTimezone())).years
                birthdayString += " They are currently $age years old!"
            }

            gatewayDiscordClient.getUserById(Snowflake.of(birthdayCreatorId)).flatMap { birthdayCreatorUser ->
                if (birthdayCreatorId == userId) {
                    embed.footer(
                        "Verified by ${getUserName(birthdayCreatorUser)}", birthdayCreatorUser.avatarUrl
                    )
                } else {
                    birthdayString += "\n-# --\n-# Birthday suggested by <@$birthdayCreatorId>" +
                            "\n-# <@$userId> can verify this with </birthday verify:$commandID>."
                }
                embed = embed.description(birthdayString)


                val replySpec = InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed.build())
                    .build()

                Mono.just(replySpec)
            }
        }
    }

    /**
     * Validates if a user is eligible to have a birthday.
     * The only users that cannot have a birthday at this time are bots.
     *
     * @param user The user to check.
     * @return A `Mono<User>` if the validation was successful.
     * @throws IllegalArgumentException if the validation was unsuccessful.
     */
    private fun validateCanHaveBirthday(user: User): Mono<User> {
        return if (user.isBot) {
            Mono.error(IllegalArgumentException("<@${user.id.asLong()}> is a bot! Bots can't have birthdays!"))
        } else {
            Mono.just(user)
        }
    }

    /**
     * Validates a subcommand's day, month, and year options, and returns a triple
     * containing the month, day, and year.
     *
     * @param subcommand A subcommand containing `month`, `day`, and `year` options.
     * @return A `Mono` containing a `Triple` that includes the month, day, and year
     * from the subcommand if they were valid, in that order. (Note that year is
     * nullable).
     * @throws IllegalArgumentException if the date could not be validated.
     */
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

    /**
     * Updates/sets a user's birthday and returns a InteractionApplicationCommandCallbackSpec
     * with an ephemeral success message.
     *
     * @param user The user whose birthday to update
     * @param event The event from when the command was originally called.
     * @param dateInput A `Triple` containing the month, day, and year to be put in the
     * database. The year can be null.
     * @return An InteractionApplicationCommandCallbackSpec containing an ephemeral message
     * if the user's birthday was successfully set/updated.
     * @throws IllegalStateException if the event caller does not have permission to update the
     * birthday of the user. This happens if the user has already set/verified their birthday.
     */
    private fun updateBirthday(
        user: User, event: ChatInputInteractionEvent, dateInput: Triple<Int, Int, Int?>
    ): Mono<InteractionApplicationCommandCallbackSpec> {
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

            val successString = if (dateInput.third == null) {
                val formatter = DateTimeFormatter.ofPattern("MMMM d")
                val monthDay = MonthDay.of(dateInput.first, dateInput.second)
                """
                    You've set $userString birthday to ${monthDay.format(formatter)}, but I noticed you **didn't include a birth year**.
                    
                    You can add a birth year by re-running this command and including the `year` option. This is recommended as this enables age to be shown along with more personalized birthday messages. You can update this at any time.
                    
                    Otherwise, no further action is needed.
                """.trimIndent()
            } else {
                val formatter = DateTimeFormatter.ofPattern("MMMM d, YYYY")
                val localDate = LocalDate.of(dateInput.third!!, dateInput.first, dateInput.second)
                "Set $userString birthday to ${localDate.format(formatter)}!"
            }

            InteractionApplicationCommandCallbackSpec.builder()
                .ephemeral(true)
                .addComponent(Container.of(TextDisplay.of(successString)))
                .build()
        }
    }

    /**
     * Removes a user's birthday information from the database.
     *
     * @param user The user to remove birthday information for.
     * @param caller The user who called the command
     * @return A Mono containing a success message.
     * @throws IllegalStateException if the user is not in the database.
     */
    private fun remove(user: User, caller: User): Mono<InteractionApplicationCommandCallbackSpec> {
        return Mono.fromCallable {
            transaction {
                if (caller != user) {
                    val userData = Users.selectAll()
                        .where { Users.userid eq user.id.asLong() }
                        .singleOrNull()
                    if (userData == null) {
                        throw IllegalStateException("No birthday information associated with <@${user.id.asString()}>!")
                    }
                    if (userData[Users.birthdayCreatorId] == userData[Users.userid]) {
                        throw IllegalStateException(
                            "<@${user.id.asString()}> has verified their birthday, so you cannot edit it!"
                        )
                    }
                }

                Users.update({ Users.userid eq user.id.asLong() }) { row ->
                    row[birthDay] = null
                    row[birthMonth] = null
                    row[birthYear] = null
                    row[birthdayCreatorId] = null
                }
            }
        }.map { _ ->
            val textDisplay = TextDisplay.of(
                "Successfully removed birthday information."
            )

            InteractionApplicationCommandCallbackSpec.builder()
                .ephemeral(true)
                .addComponent(Container.of(textDisplay))
                .build()
        }
    }

    /**
     * Verifies a user's birthday information in the database.
     *
     * @param user The user to remove birthday information for.
     * @return A Mono containing a success message.
     * @throws IllegalStateException if the user does not have a birthday in the database,
     * or if they are already verified.
     */
    private fun verify(user: User): Mono<InteractionApplicationCommandCallbackSpec> {
        return Mono.fromCallable {
            transaction {
                val existingRow = Users.selectAll()
                    .where { Users.userid eq user.id.asLong() }
                    .singleOrNull()

                if (existingRow == null) throw IllegalStateException("No saved birthday to verify!")

                if (
                    existingRow[Users.birthDay] == null ||
                    existingRow[Users.birthMonth] == null ||
                    existingRow[Users.birthdayCreatorId] == null
                ) {
                    throw IllegalStateException("No saved birthday to verify!")
                }

                if (existingRow[Users.birthdayCreatorId] == user.id.asLong()) {
                    throw IllegalStateException("Your birthday is already verified!")
                }

                Users.update({ Users.userid eq user.id.asLong() }) { row ->
                    row[birthdayCreatorId] = user.id.asLong()
                }
            }
        }.map { _ ->
            val textDisplay = TextDisplay.of(
                "Your birthday has been verified."
            )

            InteractionApplicationCommandCallbackSpec.builder()
                .ephemeral(true)
                .addComponent(Container.of(textDisplay))
                .build()
        }
    }

    /**
     * Gets the user's name or username, depending on which is available.
     *
     * @param user The user whose name to get
     * @return The globalName of the user if they have one, or if not their
     * username.
     */
    private fun getUserName(user: User): String {
        return user.globalName.orElse(user.username)
    }


    /**
     * Calculates the Unix seconds (because discord) of the next occurrence of a given MonthDay.
     * The "next occurrence" means strictly in the future. If the MonthDay is today,
     * the next occurrence will be in the following year.
     *
     * This will probably error on leap days, but I don't feel like handling that.
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
