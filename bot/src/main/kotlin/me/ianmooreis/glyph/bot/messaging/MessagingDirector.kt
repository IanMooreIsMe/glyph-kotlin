/*
 * MessagingDirector.kt
 *
 * Glyph, a Discord bot that uses natural language instead of commands
 * powered by DialogFlow and Kotlin
 *
 * Copyright (C) 2017-2020 by Ian Moore
 *
 * This file is part of Glyph.
 *
 * Glyph is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ianmooreis.glyph.bot.messaging

import kotlinx.coroutines.launch
import me.ianmooreis.glyph.bot.Director
import me.ianmooreis.glyph.bot.ai.AIAgent
import me.ianmooreis.glyph.bot.database.Key
import me.ianmooreis.glyph.bot.database.RedisAsync
import me.ianmooreis.glyph.bot.directors.skills.SkillDirector
import me.ianmooreis.glyph.bot.extensions.contentClean
import net.dv8tion.jda.api.MessageBuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.TextChannel
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import org.apache.commons.codec.digest.DigestUtils
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Manages message events including handling incoming messages
 * and dispatching the SkillDirector in addition to the message ledger
 */
class MessagingDirector(
    private val aiAgent: AIAgent,
    private val redis: RedisAsync,
    configure: Config.() -> Unit = {}
) : Director() {
    /**
     * HOCON-like config for the messaging director
     */
    class Config {
        /**
         * How long associated messages should be remembered for DeleteWith functionality
         */
        var volatileTrackingExpiration: Duration = Duration.ofDays(DEFAULT_VOLATILE_TRACKING_EXPIRATION_DAYS)
    }

    companion object {
        /**
         * By default how long to track volatile messages for
         */
        const val DEFAULT_VOLATILE_TRACKING_EXPIRATION_DAYS: Long = 14
    }

    private val config = Config().also(configure)
    private val volatileTrackingExpirationSeconds = config.volatileTrackingExpiration.toSeconds()

    /**
     * Add a message to the ledger
     *
     * @param invokerId the message id the invoked the response message
     * @param responseId the message id of the response message to the invoking message
     */
    fun trackVolatile(invokerId: String, responseId: String) {
        redis.setex(Key.VOLATILE_MESSAGE_PREFIX.value + invokerId, volatileTrackingExpirationSeconds, responseId)
    }

    /**
     * Log a failure to send a message, useful so figuring out the if someone complains Glyph won't respond
     *
     * @param channel the channel where the message failed to send
     */
    private fun logSendFailure(channel: TextChannel) {
        if (channel.type.isGuild) {
            log.warn("Failed to send message in $channel of ${channel.guild}!")
        } else {
            log.warn("Failed to send message in $channel!.")
        }
    }

    /**
     * When a new message is seen anywhere
     */
    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.isIgnorable) return

        val message: Message = event.message

        // Get ready to ask the DialogFlow agent
        val sessionId = DigestUtils.md5Hex(event.author.id + event.channel.id)
        val ai = try {
            aiAgent.request(event.message.contentClean, sessionId)
        } catch (e: IllegalArgumentException) {
            message.addReaction("⁉").queue()
            return
        }

        // In the rare circumstance the agent is unavailable or has an issue, warn the user
        if (ai.isError) {
            message.reply(
                "Sorry, due to an issue with ${aiAgent.name} I'm currently unable to interpret your message.",
                volatile = true
            )
            return
        }

        // Assuming everything else went well, launch the appropriate skill with the event info and ai response
        SkillDirector.launch {
            when (val response = SkillDirector.trigger(event, ai)) {
                is Response.Ephemeral -> message.reply(response.content, response.embed, ttl = response.ttl)
                is Response.Volatile -> message.reply(response.content, response.embed, volatile = true)
                is Response.Permanent -> message.reply(response.content, response.embed, volatile = false)
                is Response.Reaction -> message.addReaction(response.emoji)
            }

            // Increment the total message count for curiosity's sake
            redis.incr(Key.MESSAGE_COUNT.value)
        }
    }

    /**
     * When a message is deleted anywhere, remove the invoking message if considered volatile
     */
    override fun onMessageDelete(event: MessageDeleteEvent) {
        val key = Key.VOLATILE_MESSAGE_PREFIX.value + event.messageId
        redis.get(key).thenAccept { responseId ->
            redis.del(key)
            event.channel.retrieveMessageById(responseId).queue {
                it.addReaction("❌").queue()
                it.delete().queueAfter(1, TimeUnit.SECONDS)
            }
        }
    }

    private val MessageReceivedEvent.isIgnorable
        get() = author.isBot || // ignore other bots
            (author == jda.selfUser) || // ignore self
            isWebhookMessage || // ignore webhooks
            (isFromGuild && !message.isMentioned(jda.selfUser)) || // require mention except in DMs
            message.contentClean.isEmpty() || // ignore empty messages
            (isFromGuild && !message.contentRaw.startsWith("<@!" + jda.selfUser.id)) // must start with mention

    private fun Message.reply(
        content: String? = null,
        embed: MessageEmbed? = null,
        ttl: Duration? = null,
        volatile: Boolean = true
    ) {
        // require some content
        if (content == null && embed == null) return
        // build the message
        val message = MessageBuilder().setContent(content?.trim()).setEmbed(embed).build()
        // try to send the message
        try {
            this.channel.sendMessage(message).queue {
                if (ttl != null) {
                    it.delete().queueAfter(ttl.seconds, TimeUnit.SECONDS)
                } else if (volatile) {
                    trackVolatile(this.id, it.id)
                }
            }
        } catch (e: InsufficientPermissionException) {
            logSendFailure(this.textChannel)
        }
    }
}
