package me.ianmooreis.glyph.orchestrators.messaging

import ai.api.AIConfiguration
import ai.api.AIDataService
import ai.api.AIServiceContextBuilder
import ai.api.model.AIRequest
import kotlinx.coroutines.experimental.launch
import me.ianmooreis.glyph.extensions.config
import me.ianmooreis.glyph.extensions.contentClean
import me.ianmooreis.glyph.extensions.reply
import me.ianmooreis.glyph.orchestrators.DatabaseOrchestrator
import me.ianmooreis.glyph.orchestrators.StatusOrchestrator
import me.ianmooreis.glyph.orchestrators.skills.SkillOrchestrator
import me.ianmooreis.glyph.utils.quickview.furaffinity.FurAffinity
import me.ianmooreis.glyph.utils.quickview.picarto.Picarto
import net.dv8tion.jda.core.OnlineStatus
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.message.MessageDeleteEvent
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.slf4j.Logger
import org.slf4j.simple.SimpleLoggerFactory
import java.util.concurrent.TimeUnit

object MessagingOrchestrator : ListenerAdapter() {
    private val log: Logger = SimpleLoggerFactory().getLogger(this.javaClass.simpleName)
    private object DialogFlow : AIDataService(AIConfiguration(System.getenv("DIALOGFLOW_TOKEN")))
    private val ledger = hashMapOf<Long, Long>()
    private val customEmotes = mapOf<String, Emote>()

    fun getCustomEmote(name: String) : Emote? {
        return customEmotes[name]
    }

    private fun loadCustomEmotes(guild: Guild) {
        if (customEmotes.isEmpty()) {
            customEmotes.plus(guild.emotes.map {
                it.name to it
            }.toMap())
        }
    }

    fun amendLedger(invoker: Long, response: Long) {
        ledger[invoker] = response
    }

    fun getLedgerSize() : Int {
        return ledger.size
    }

    fun logSendFailure(channel: TextChannel) {
        if (channel.type.isGuild) {
            log.warn("Failed to send message in $channel of ${channel.guild}!")
        } else {
            log.warn("Failed to send message in $channel!.")
        }
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        loadCustomEmotes(event.jda.getGuildById(System.getenv("EMOJI_GUILD")))
        if (event.author.isBot or (event.author == event.jda.selfUser) or event.isWebhookMessage) return
        val config = if (event.channelType.isGuild) event.guild.config else DatabaseOrchestrator.getDefaultServerConfig()
        launch {
            if (config.quickview.furaffinityEnabled) {
                FurAffinity.makeQuickviews(event)
            }
            if (config.quickview.picartoEnabled) {
                Picarto.makeQuickviews(event)
            }
        }
        val message: Message = event.message
        if ((!message.isMentioned(event.jda.selfUser) or (message.contentStripped.trim() == message.contentClean)) and event.message.channelType.isGuild) return
        if (event.message.contentClean.isEmpty()) {
            event.message.reply("You have to say something!")
            return
        }
        val ctx = AIServiceContextBuilder().setSessionId("${event.author.id}${event.channel.id}".substring(0..20)).build()
        val ai = DialogFlow.request(AIRequest(event.message.contentClean), ctx)
        if (ai.isError) {
            event.message.reply("It appears DialogFlow is currently unavailable, please try again later!")
            StatusOrchestrator.setStatus(event.jda, OnlineStatus.DO_NOT_DISTURB, Game.watching("temporary outage at DialogFlow"))
            return
        }
        val result = ai.result
        val action = result.action
        launch {
            SkillOrchestrator.trigger(event, ai)
        }
        log.info("Received \"${event.message.contentClean}\" from ${event.author} ${if (event.channelType.isGuild) "in ${event.guild}" else "in PM"}, acted with $action")
    }

    override fun onMessageDelete(event: MessageDeleteEvent) {
        val messageId = ledger[event.messageIdLong]
        if (messageId != null) {
            event.channel.getMessageById(messageId).queue {
                it.addReaction("❌").queue()
                it.delete().queueAfter(1, TimeUnit.SECONDS)
                ledger.remove(it.idLong)
            }
        }
    }
}

