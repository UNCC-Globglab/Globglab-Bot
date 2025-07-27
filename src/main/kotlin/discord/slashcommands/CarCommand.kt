package com.dudebehinddude.discord.slashcommands

import com.dudebehinddude.annotations.SlashCommand
import com.dudebehinddude.database.Users
import discord.slashcommands.RegisterableSlashCommand
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
import org.jetbrains.exposed.sql.upsert
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.*

@SlashCommand()
class CarCommand : RegisterableSlashCommand {
    override val name: String = "car"

    override fun builder(): ApplicationCommandRequest {
        return ApplicationCommandRequest.builder()
            .name(name)
            .description("Displays and manages car information.")
            // display subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("display")
                    .description("Displays a list of all car information or car information about a specific user.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("If specified, displays whether this person owns a car.")
                            .type(ApplicationCommandOption.Type.USER.value)
                            .required(false)
                            .build()
                    ).build()
            )
            // set subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("set")
                    .description("Sets whether you have a car or not.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("value")
                            .description("Whether you have a car or not.")
                            .type(ApplicationCommandOption.Type.BOOLEAN.value).required(true)
                            .build()
                    ).build()
            )
            // suggest subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("suggest")
                    .description("Suggests if another user has a car or not.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .addOption(
                        ApplicationCommandOptionData.builder()
                            .name("value")
                            .description("Whether this person has a car or not.")
                            .type(ApplicationCommandOption.Type.BOOLEAN.value).required(true)
                            .build()
                    ).addOption(
                        ApplicationCommandOptionData.builder()
                            .name("user")
                            .description("The user to suggest a value for.")
                            .type(ApplicationCommandOption.Type.USER.value).required(true)
                            .build()
                    ).build()
            )
            // remove subcommand
            .addOption(
                ApplicationCommandOptionData.builder()
                    .name("remove")
                    .description("Removes this from your userdata.")
                    .type(ApplicationCommandOption.Type.SUB_COMMAND.value)
                    .build()
            ).build()
    }

    override fun execute(event: ChatInputInteractionEvent): Mono<Void> {
        val subcommand = event.options.firstOrNull()
        return when (subcommand?.name) {
            "display" -> displayCars(event, subcommand)
            "set" -> setCar(event, subcommand)
            "suggest" -> setCar(event, subcommand)
            else -> Mono.error(IllegalStateException("This command hasn't been implemented yet :("))
        }
    }

    /**
     * Handles the `/car set` and `/car suggest` subcommands.
     *
     * @param event The event of the command called.
     * @param subcommand The subcommand associated with the command called.
     */
    private fun setCar(event: ChatInputInteractionEvent, subcommand: ApplicationCommandInteractionOption): Mono<Void> {
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .orElse(Mono.just(event.interaction.user))
            .flatMap { user -> validateCanHaveCar(user) }
            .flatMap { user -> updateCar(user, event, subcommand) }
            .flatMap { successMessage -> event.reply(successMessage) }
    }

    /**
     * Handles the `/car display` subcommand.
     *
     * @param event The event of the command called.
     * @param subcommand The subcommand associated with the command called.
     * @return A `Mono<Void>` that completes when the car information has been displayed.
     */
    private fun displayCars(
        event: ChatInputInteractionEvent,
        subcommand: ApplicationCommandInteractionOption
    ): Mono<Void> {
        val commandID = event.commandId.asLong()
        return subcommand.getOption("user").flatMap(ApplicationCommandInteractionOption::getValue)
            .map(ApplicationCommandInteractionOptionValue::asUser)
            .orElse(Mono.empty())
            .flatMap { user -> getCarInfo(user, commandID) }
            .switchIfEmpty(getAllCarInfo(commandID))
            .flatMap { event.reply(it) }
    }

    /**
     * Retrieves the boolean value for car ownership from the provided subcommand's "value" option.
     *
     * @param subcommand The subcommand containing a `value` option.
     * @return A `Mono<Boolean>` that emits `true` or `false` based on the subcommand's value.
     * @throws IllegalArgumentException if `value` from the subcommand is not provided.
     */
    private fun getHasCarValue(subcommand: ApplicationCommandInteractionOption): Mono<Boolean> {
        return Mono.fromCallable {
            subcommand.getOption("value")
                .flatMap(ApplicationCommandInteractionOption::getValue)
                .map(ApplicationCommandInteractionOptionValue::asBoolean)
                .orElseThrow { IllegalArgumentException("Car ownership value not provided.") }
        }
    }

    /**
     * Validates if a user is eligible to have a car.
     * The only users that cannot have a car at this time are bots.
     *
     * @param user The user to check.
     * @return A `Mono<User>` if the validation was successful.
     * @throws IllegalArgumentException if the validation was unsuccessful.
     */
    private fun validateCanHaveCar(user: User): Mono<User> {
        return if (user.isBot) {
            Mono.error(IllegalArgumentException("<@${user.id.asString()}> is a bot! Bots can't have cars!"))
        } else {
            Mono.just(user)
        }
    }

    /**
     * Updates a user's car ownership status in the database.
     *
     * @param user The user whose car ownership status is to be updated.
     * @param event The event of the command called, used to determine the caller for ephemeral reply.
     * @param subcommand The subcommand containing the car ownership value.
     * @return A `Mono<InteractionApplicationCommandCallbackSpec>` containing an ephemeral success message.
     */
    private fun updateCar(
        user: User,
        event: ChatInputInteractionEvent,
        subcommand: ApplicationCommandInteractionOption
    ): Mono<InteractionApplicationCommandCallbackSpec> {
        return getHasCarValue(subcommand).flatMap { hasCarValue ->
            val eventCallerId = event.interaction.user.id.asLong()
            val userQueryId = user.id.asLong()

            transaction {
                Users.upsert {
                    it[userid] = userQueryId
                    it[hasCar] = hasCarValue
                }
            }

            val userString = if (userQueryId == eventCallerId) {
                "your"
            } else {
                "<@$userQueryId>'s"
            }

            val textDisplay = TextDisplay.of(
                "Set $userString car ownership status to \"${getOwnString(hasCarValue)}\"."
            )

            Mono.just(
                InteractionApplicationCommandCallbackSpec.builder()
                    .ephemeral(true)
                    .addComponent(Container.of(textDisplay))
                    .build()
            )
        }
    }

    /**
     * Retrieves and formats car ownership information for all users in the database.
     *
     * @return A `Mono<InteractionApplicationCommandCallbackSpec>` containing an embed
     * with a list of all users' car ownership statuses.
     */
    private fun getAllCarInfo(commandID: Long): Mono<InteractionApplicationCommandCallbackSpec> {
        return Mono.fromCallable {
            transaction {
                Users.selectAll().map { row ->
                    val userId = row[Users.userid]
                    val hasCar = row[Users.hasCar]

                    userId to hasCar
                }.filter { (_, hasCar) ->
                    hasCar != null
                }
            }
        }.flatMap { users ->
            var hasCarList = ""
            var noCarList = ""
            var embed = EmbedCreateSpec.builder()
                .title("Car Ownership Information")
                .description("Not in this list? Add your ownership status with </car set:$commandID>.")
                .timestamp(Instant.now())

            for (user in users) {
                val listItem = "- <@${user.first}>\n"
                if (user.second!!) {
                    hasCarList += listItem
                } else {
                    noCarList += listItem
                }
            }

            if (hasCarList != "") {
                embed = embed.addField("People with cars", hasCarList, false)
            }
            if (noCarList != "") {
                embed = embed.addField("People without cars", noCarList, false)
            }

            Mono.just(
                InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed.build())
                    .build()
            )
        }
    }

    /**
     * Retrieves and formats car ownership information for a specific user from the database.
     *
     * @param user The user whose car ownership information is to be retrieved.
     * @return A `Mono<InteractionApplicationCommandCallbackSpec>` containing an embed
     * with the specified user's car ownership status.
     */
    private fun getCarInfo(user: User, commandID: Long): Mono<InteractionApplicationCommandCallbackSpec> {
        return Mono.fromCallable {
            Optional.ofNullable(transaction {
                Users.selectAll().where(Users.userid eq user.id.asLong()).map { row ->
                    row[Users.hasCar]
                }.firstOrNull()
            })
        }.flatMap { optionalHasCar ->
            Mono.justOrEmpty(optionalHasCar)
        }.flatMap { hasCarValue ->
            Mono.just("<@${user.id.asString()}> ${getOwnString(hasCarValue)}.")
        }.switchIfEmpty(
            Mono.just(
                "Car ownership information not found for <@${user.id.asString()}>. Perhaps make a suggestion with </car suggest:$commandID>?"
            )
        ).flatMap { carValueString ->
            val embed = EmbedCreateSpec.builder()
                .title("Car Ownership Information")
                .description(carValueString)
                .timestamp(Instant.now())
                .build()

            Mono.just(
                InteractionApplicationCommandCallbackSpec.builder()
                    .addEmbed(embed)
                    .build()
            )
        }
    }

    /**
     * Generates a string indicating whether a user owns a car.
     *
     * @param hasItem `true` if the user owns a car, `false` otherwise.
     * @return A string indicating car ownership status ("does own a car" or "does not own a car").
     */
    private fun getOwnString(hasItem: Boolean): String {
        return if (hasItem) {
            "does"
        } else {
            "does not"
        } + " own a car"
    }
}