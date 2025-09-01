package com.dudebehinddude.schedulers

import com.dudebehinddude.database.Users
import com.dudebehinddude.discord.handlers.SlashCommandHandler
import com.dudebehinddude.util.getTimezone
import com.dudebehinddude.util.toOrdinal
import discord4j.common.util.Snowflake
import discord4j.core.GatewayDiscordClient
import discord4j.core.`object`.component.TextDisplay
import discord4j.core.`object`.entity.Message
import discord4j.core.`object`.entity.channel.TextChannel
import discord4j.core.spec.EmbedCreateSpec
import discord4j.core.spec.MessageCreateSpec
import io.github.cdimascio.dotenv.dotenv
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import reactor.core.publisher.Mono
import java.time.Instant
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BirthdayScheduler {
    private val dotenv = dotenv { ignoreIfMissing = true }
    private val gateway: GatewayDiscordClient
    private val channelId: Long
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val birthdayCommandId: Long?

    constructor(gateway: GatewayDiscordClient) {
        this.gateway = gateway
        this.birthdayCommandId = SlashCommandHandler.getCommandID(gateway, "birthday")

        val channelIdStr: String? =
            dotenv["BIRTHDAY_ANNOUNCEMENT_CHANNEL_ID"] ?: System.getenv("BIRTHDAY_ANNOUNCEMENT_CHANNEL_ID")
        val channelId = channelIdStr?.toLongOrNull()

        if (channelId == null) {
            println("Warning: BIRTHDAY_ANNOUNCEMENT_CHANNEL_ID is invalid or missing; disabling birthday announcements")
            this.channelId = 0
            return
        }
        this.channelId = channelId

        Runtime.getRuntime().addShutdownHook(Thread { stop() })
        start()
    }

    fun start() {
        val now = ZonedDateTime.now(getTimezone())
        var firstRunTime = now.with(LocalTime.of(0, 0, 5)) // +5 seconds for time funkiness
        if (now.isAfter(firstRunTime)) {
            firstRunTime = firstRunTime.plus(1, ChronoUnit.DAYS)
        }

        val firstRunDelay = ChronoUnit.SECONDS.between(now, firstRunTime)
        println("Sending first birthday message in ${firstRunDelay / 60} MINUTES")
        scheduler.scheduleAtFixedRate(this::doStuff, firstRunDelay, 60 * 60 * 24, TimeUnit.SECONDS)
    }

    fun doStuff() {
        val channelSnowflake = Snowflake.of(channelId)
        val textChannel = gateway.getChannelById(channelSnowflake).cast(TextChannel::class.java)

        // Monthly birthday list
        if (ZonedDateTime.now(getTimezone()).dayOfMonth == 1) {
            textChannel.flatMap { channel ->
                getMonthlyBirthdays().flatMap { message ->
                    channel.createMessage(message)
                }
            }.subscribe()
        }

        // Daily birthday list
        textChannel.flatMap { channel ->
            getTodayBirthdays().flatMap { message ->
                channel.createMessage(message)
            }
        }.subscribe()
    }

    private fun getTodayBirthdays(): Mono<MessageCreateSpec> {
        val today = ZonedDateTime.now(getTimezone())
        val day = today.dayOfMonth
        val month = today.monthValue
        val year = today.year

        return Mono.fromCallable {
            transaction {
                Users.selectAll()
                    .where { (Users.birthMonth eq month) and (Users.birthDay eq day) }
                    .map { row ->
                        val year = row[Users.birthYear]
                        val userId = row[Users.userid]

                        userId to year
                    }.map { (userID, birthYear) ->
                        val age = if (birthYear != null) {
                            " ${(year - birthYear).toOrdinal()}"
                        } else ""
                        TextDisplay.of("Happy$age birthday to <@$userID>!")
                    }
            }
        }.flatMap { birthdays ->
            if (birthdays.isEmpty()) {
                return@flatMap Mono.empty()
            }

            Mono.just(
                MessageCreateSpec.create()
                    .withFlags(Message.Flag.IS_COMPONENTS_V2)
                    .withComponents(birthdays)
            )
        }
    }

    private fun getMonthlyBirthdays(): Mono<MessageCreateSpec> {
        val currentMonth = MonthDay.now(getTimezone()).monthValue
        return Mono.fromCallable {
            transaction {
                Users.selectAll()
                    .where { Users.birthMonth eq currentMonth }
                    .map { row ->
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
                        // sort by day
                        compareBy { it.first.second!! }
                    )
                    .map { (dateData, userData) ->
                        // Format list items
                        val formatter = DateTimeFormatter.ofPattern("MMMM d")
                        val formattedDate = MonthDay.of(dateData.first!!, dateData.second!!).format(formatter)

                        var formattedBirthday = "- <@${userData.first}> - $formattedDate"
                        if (dateData.third != null) {
                            formattedBirthday += ", ${dateData.third}"
                        }
                        if (userData.first != userData.second) {
                            formattedBirthday += " (suggested by <@${userData.second}>)"
                        }

                        formattedBirthday + "\n"
                    }
            }
        }.flatMap { birthdays ->
            var message = MessageCreateSpec.create()
            if (birthdays.isEmpty()) {
                message = message.withFlags(Message.Flag.IS_COMPONENTS_V2).withComponents(
                    TextDisplay.of("Happy new month! Unfortunately, there are no known birthdays this month :confounded:."),
                    TextDisplay.of(
                        "If you do have a birthday this month (or know someone who does), add it with " +
                                "</birthday set:$birthdayCommandId> or </birthday suggest:$birthdayCommandId>!"
                    ),
                    TextDisplay.of("View the full list of birthdays with </birthday display:$birthdayCommandId>!"),
                )
                return@flatMap Mono.just(message)
            }

            val embed = EmbedCreateSpec.builder()
                .title("\uD83C\uDF82  Birthdays This Month")
                .description(
                    "Want yours on this list? Add it with </birthday set:$birthdayCommandId> (or add someone " +
                            "else's with </birthday suggest:$birthdayCommandId>)."
                )
                .addField("** **", birthdays.joinToString(""), false)
                .addField("** **", "View all birthdays with </birthday display:$birthdayCommandId>", false)
                .timestamp(Instant.now())

            Mono.just(message.withEmbeds(embed.build()))
        }
    }

    fun stop() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            error(e)
            scheduler.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}