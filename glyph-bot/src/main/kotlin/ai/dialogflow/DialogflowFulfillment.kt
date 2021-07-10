/*
 * DialogflowFulfillment.kt
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

package org.yttr.glyph.bot.ai.dialogflow

import com.google.cloud.dialogflow.v2.QueryResult
import org.yttr.glyph.bot.ai.AIFulfillment

/**
 * A wrapper for the new DialogFlow API v2 fulfillment
 */
class DialogflowFulfillment(result: QueryResult) : AIFulfillment {
    /**
     * What the agent wants to say
     */
    override val speech: String = result.fulfillmentText.replace("\\n", "\n", ignoreCase = true).trim()
}
