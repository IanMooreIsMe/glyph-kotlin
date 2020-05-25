/*
 * WikipediaExtractor.kt
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

import io.ktor.client.features.ResponseException
import io.ktor.client.request.get
import io.ktor.http.URLBuilder
import io.ktor.http.encodeURLPath
import io.ktor.http.takeFrom

/**
 * Grabs articles from Wikipedia
 */
class WikipediaExtractor(
    /**
     * Specifies what edition of Wikipedia to use
     */
    private val languageCode: String = "en"
) : WikiExtractor() {

    companion object {
        /**
         * Page ID used when a query fails
         */
        const val INVALID_PAGE_ID: Int = -1

        /**
         * Represents a thumbnail result
         */
        data class Thumbnail(
            /**
             * URL of the thumbnail image
             */
            val source: String
        )

        /**
         * Represents a page on Wikipedia
         */
        data class Page(
            /**
             * Title of the page
             */
            val title: String,
            /**
             * Excerpt from the page
             */
            val extract: String,
            /**
             * URL linking to the page
             */
            val fullurl: String,
            /**
             * Thumbnail, if any
             */
            val thumbnail: Thumbnail?
        )

        /**
         * Represents the found pages in a search query listing on Wikipedia
         */
        data class Query(
            /**
             * Pages found by the query
             */
            val pages: Map<Int, Page>
        )

        /**
         * Represents the result of a search query on Wikipedia
         */
        data class Result(
            /**
             * Query results
             */
            val query: Query
        )
    }

    /**
     * Tries to find an article from a search
     *
     * @param query the search query
     */
    override suspend fun getArticle(query: String): WikiArticle? = try {
        val apiBase = "https://${languageCode.encodeURLPath()}.wikipedia.org/w/api.php"
        val queryUrl = URLBuilder().takeFrom(apiBase).apply {
            parameters.apply {
                append("action", "query")
                append("format", "json")
                append("prop", "extracts|info|pageimages")
                append("titles", query)
                append("redirects", "1")
                append("explaintext", "1")
                append("exlimit", "1")
                append("exchars", "500")
                append("inprop", "url")
                append("piprop", "thumbnail")
            }
        }.build()

        val result = client.get<Result>(queryUrl)

        result.query.pages.entries.firstOrNull()?.let { (id, page) ->
            if (id != INVALID_PAGE_ID) WikiArticle(
                page.title,
                page.extract,
                page.fullurl,
                page.thumbnail?.source
            ) else null
        }
    } catch (e: ResponseException) {
        null
    }
}

