package org.yttr.glyph

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.yttr.glyph.ai.AIAgent
import org.yttr.glyph.ai.dialogflow.Dialogflow
import org.yttr.glyph.messaging.ComplianceListener
import org.yttr.glyph.messaging.MessagingDirector
import org.yttr.glyph.messaging.ThanksListener
import org.yttr.glyph.messaging.quickview.QuickviewDirector
import org.yttr.glyph.presentation.BotList
import org.yttr.glyph.presentation.ServerDirector
import org.yttr.glyph.presentation.StatusDirector
import org.yttr.glyph.pubsub.redis.RedisAsync
import org.yttr.glyph.skills.SkillDirector
import org.yttr.glyph.skills.config.ConfigDirector
import org.yttr.glyph.skills.config.ServerConfigSkill
import org.yttr.glyph.skills.creator.ChangeStatusSkill
import org.yttr.glyph.skills.moderation.AuditingDirector
import org.yttr.glyph.skills.moderation.BanSkill
import org.yttr.glyph.skills.moderation.GuildInfoSkill
import org.yttr.glyph.skills.moderation.KickSkill
import org.yttr.glyph.skills.moderation.PurgeSkill
import org.yttr.glyph.skills.moderation.UserInfoSkill
import org.yttr.glyph.skills.play.DoomsdayClockSkill
import org.yttr.glyph.skills.play.EphemeralSaySkill
import org.yttr.glyph.skills.play.RankSkill
import org.yttr.glyph.skills.play.RedditSkill
import org.yttr.glyph.skills.roles.RoleListSkill
import org.yttr.glyph.skills.roles.RoleSetSkill
import org.yttr.glyph.skills.roles.RoleUnsetSkill
import org.yttr.glyph.skills.starboard.StarboardDirector
import org.yttr.glyph.skills.util.FallbackSkill
import org.yttr.glyph.skills.util.FeedbackSkill
import org.yttr.glyph.skills.util.HelpSkill
import org.yttr.glyph.skills.util.SnowstampSkill
import org.yttr.glyph.skills.util.SourceSkill
import org.yttr.glyph.skills.util.StatusSkill
import org.yttr.glyph.skills.util.TimeSkill
import org.yttr.glyph.skills.wiki.WikiSkill

/**
 * The Glyph object to use when building the client
 */
object Glyph {
    /**
     * HOCON config from application.conf
     */
    val conf: Config = ConfigFactory.load().getConfig("glyph")

    /**
     * The current version of Glyph
     */
    val version: String = conf.getString("version")

    private val aiAgent: AIAgent = Dialogflow(conf.getString("dialogflow.credentials").byteInputStream())

    private val redis: RedisAsync = RedisClient.create().run {
        val redisUri = RedisURI.create(conf.getString("data.redis-url")).apply {
            // We are using Heroku Redis which is version 5, but for some reason they give us a username.
            // However if we supply the username it runs the version 6 command and fails to login.
            username = null
        }
        connect(redisUri).async()
    }

    private val configDirector = ConfigDirector {
        databaseConnectionUri = conf.getString("data.database-url")
    }

    private val skillDirector = SkillDirector().addSkill(
        HelpSkill(),
        StatusSkill(redis),
        SourceSkill(),
        RoleSetSkill(),
        RoleUnsetSkill(),
        RoleListSkill(),
        ServerConfigSkill(),
        PurgeSkill(),
        UserInfoSkill(),
        GuildInfoSkill(),
        KickSkill(),
        BanSkill(),
        RankSkill(),
        EphemeralSaySkill(),
        RedditSkill(),
        WikiSkill(),
        TimeSkill(),
        FeedbackSkill(),
        DoomsdayClockSkill(),
        SnowstampSkill(),
        ChangeStatusSkill(),
        FallbackSkill()
    )

    /**
     * Build the bot and run
     */
    fun run() {
        val builder = DefaultShardManagerBuilder.createLight(null).also {
            val token = conf.getString("discord-token")

            it.setToken(token)

            it.setEnabledIntents(
                GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_EMOJIS,
                GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS,
                GatewayIntent.GUILD_MEMBERS
            )

            it.enableCache(CacheFlag.EMOTE)

            val serverDirector = ServerDirector { id ->
                if (conf.hasPath("bot-list.top")) {
                    val discordBotList = BotList(
                        "Discord Bot List",
                        "https://top.gg/api/bots/$id/stats",
                        conf.getString("bot-list.top")
                    )

                    botList(discordBotList)
                }
            }

            val messagingDirector = MessagingDirector(aiAgent, redis, skillDirector)

            fun addDirectors(vararg directors: Director) {
                directors.forEach { director ->
                    director.configDirector = configDirector
                    it.addEventListeners(director)
                }
            }

            addDirectors(
                messagingDirector, AuditingDirector, skillDirector, configDirector,
                serverDirector, QuickviewDirector(messagingDirector), StatusDirector, StarboardDirector(redis)
            )

            it.addEventListeners(ComplianceListener, ThanksListener)
        }

        builder.build()
    }
}

/**
 * Where everything begins
 * Registers all the skills and builds the clients with optional sharding
 */
fun main(): Unit = Glyph.run()
