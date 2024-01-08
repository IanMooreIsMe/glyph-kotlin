package org.yttr.glyph.config.discord

import io.ktor.client.HttpClient
import io.ktor.client.features.ClientRequestException
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import org.yttr.glyph.shared.Either
import org.yttr.glyph.shared.left
import org.yttr.glyph.shared.right

/**
 * Represents a Discord user
 */
data class User(
    /**
     * The user snowflake id
     */
    val id: Long,
    /**
     * The user's name
     */
    val username: String,
    /**
     * Guilds the user belongs to
     */
    val guilds: List<UserGuild>
) {
    companion object {
        private val client: HttpClient = HttpClient {
            install(JsonFeature)
        }

        private const val USER_API_BASE: String = "https://discord.com/api/users/@me"

        /**
         * Get a user, based on
         */
        suspend fun getUser(token: String): Either<DiscordException, User> = when {
            token.isNotBlank() -> try {
                fun HttpRequestBuilder.addAuth() = header("Authorization", "Bearer $token")
                val user: User = client.get(USER_API_BASE) { addAuth() }
                val guilds: List<UserGuild> = client.get("$USER_API_BASE/guilds") { addAuth() }

                User(user.id, user.username, guilds).right()
            } catch (e: ClientRequestException) {
                DiscordException.Unauthorized.left()
            }
            else -> DiscordException.InvalidToken.left()
        }
    }
}
