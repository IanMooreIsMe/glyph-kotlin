/*
 * WikiSkill.kt
 *
 * Glyph, a Discord bot that uses natural language instead of commands
 * powered by DialogFlow and Kotlin
 *
 * Copyright (C) 2017-2018 by Ian Moore
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

package me.ianmooreis.glyph.skills.wiki

import me.ianmooreis.glyph.directors.config.ConfigDirector
import me.ianmooreis.glyph.directors.config.server.WikiConfig
import me.ianmooreis.glyph.directors.messaging.AIResponse
import me.ianmooreis.glyph.directors.skills.Skill
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.net.URL
import java.time.Instant

/**
 * A skill that allows users to search for stuff across multiple wikis
 */
object WikiSkill : Skill("skill.wiki") {
    override fun onTrigger(event: MessageReceivedEvent, ai: AIResponse) {
        val query: String = ai.result.getStringParameter("search_query") ?: ""
        val config: WikiConfig = event.guild?.config?.wiki ?: ConfigDirector.getDefaultServerConfig().wiki
        val requestedSource: String? = ai.result.getStringParameter("fandom_wiki")
        val sources: List<String> = if (requestedSource != null) listOf(requestedSource) else (config.sources + "wikipedia")
        val sourcesDisplay = sources.map { if (it.toLowerCase() == "wikipedia") "Wikipedia" else "$it wiki" }
        event.channel.sendTyping().queue()
        sources.forEachIndexed { index, source ->
            val article: WikiArticle? = if (source.toLowerCase() == "wikipedia") {
                WikipediaExtractor.getArticle(query)
            } else {
                FandomExtractor.getArticle(source, query, config.minimumQuality)
            }
            if (article != null) {
                event.message.reply(getResultEmbed(article.title, article.url, article.intro, sourcesDisplay[index]))
                return
            }
        }
        event.message.reply("No results found for `$query` on ${sourcesDisplay.joinToString()}!")
    }

    private fun getResultEmbed(title: String, url: URL, description: String, wiki: String): MessageEmbed {
        return EmbedBuilder()
            .setTitle(title, url.toString())
            .setDescription(description)
            .setFooter(wiki, null)
            .setTimestamp(Instant.now())
            .build()
    }
}