package com.kitsuri.msa.rapidfetch

/**
 * Represents basic information about a Minecraft user account.
 *
 * @property displayName The visible name of the user in Minecraft.
 * @property uuid The unique UUID (Universally Unique Identifier) for the user.
 * @property hasRealmsAccess Whether the user has access to Minecraft Realms services.
 *
 * ### Example Usage
 * ```kotlin
 * // Example: Creating a UserInfo object
 * val user = UserInfo(
 *     displayName = "Kitsuri",
 *     uuid = "550e8400-e29b-41d4-a716-446655440000",
 *     hasRealmsAccess = true
 * )
 *
 * // Printing user information
 * println("Username: ${user.displayName}")
 * println("UUID: ${user.uuid}")
 * println("Realms Access: ${if (user.hasRealmsAccess) "Yes" else "No"}")
 * ```
 */
data class UserInfo(
    val displayName: String,
    val uuid: String,
    val hasRealmsAccess: Boolean
)
