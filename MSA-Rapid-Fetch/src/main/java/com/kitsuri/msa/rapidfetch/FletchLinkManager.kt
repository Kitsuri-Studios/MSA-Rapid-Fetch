package com.kitsuri.msa.rapidfetch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.service.realms.BedrockRealmsService
import net.raphimc.minecraftauth.service.realms.model.RealmsWorld
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession

/**
 * **FletchLinkManager**
 *
 * The main controller for FletchLink Core’s interaction with
 * **Minecraft Bedrock Realms**.
 * It is responsible for:
 *
 * - Managing and validating the user’s saved Bedrock session
 * - Authenticating the user via Microsoft/Xbox Live
 * - Fetching Realms availability status
 * - Listing all Realms worlds for the account
 * - Joining, accepting invites, and leaving Realms
 * - Refreshing sessions to prevent token expiry
 *
 *
 * **Threading model:**
 * All network calls are wrapped in `withContext(Dispatchers.IO)` to ensure
 * they run on a background thread, avoiding UI freezes.
 *
 * @property context Application context for session persistence.
 *
 * **Example:**
 * ```kotlin
 * val manager = FletchLinkManager.getInstance(context)
 * if (!manager.hasValidSession()) {
 *     manager.startAuthFlow { status, session ->
 *         if (status == AuthStatus.SUCCESS) {
 *             println("Logged in as ${session?.mcChain?.displayName}")
 *         }
 *     }
 * }
 * ```
 */
class FletchLinkManager private constructor(private val context: Context) {

    private val sessionManager = SessionManager(context)
    private val httpClient = MinecraftAuth.createHttpClient()
    private var cachedUserInfo: UserInfo? = null

    companion object {
        private const val TAG = "FletchLinkManager"
        /**
         * Update This When Needed
         */
        private const val BEDROCK_VERSION = "1.21.94"

        @Volatile
        private var INSTANCE: FletchLinkManager? = null

        /**
         * Retrieves the **singleton** instance of [FletchLinkManager].
         *
         * Why singleton?
         * Realms operations rely on consistent session handling
         * and HTTP client reuse. Using a singleton ensures we
         * don’t create multiple managers that could overwrite or
         * invalidate each other's session data.
         *
         * @param context Any Android `Context` — internally replaced with application context.
         *
         * **Example:**
         * ```kotlin
         * val manager = FletchLinkManager.getInstance(context)
         * ```
         */
        fun getInstance(context: Context): FletchLinkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FletchLinkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Checks if the current device has a valid, unexpired session
     * with **Realms access tokens**.
     *
     * Internally:
     * - Loads the saved session from `SessionManager`
     * - Confirms it contains a valid Realms XSTS token
     * - Ensures the token is not expired
     *
     * This method is useful before attempting any Realms operation
     * so the app can trigger authentication only if required.
     *
     * @return `true` if a valid session exists; otherwise `false`.
     *
     * **Example:**
     * ```kotlin
     * val isValid = manager.hasValidSession()
     * println("Session valid: $isValid")
     * ```
     */
    suspend fun hasValidSession(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionManager.loadSavedSession(httpClient)
        session != null && session.realmsXsts != null && !session.isExpiredOrOutdated()
    }

    /**
     * Retrieves the **current saved session** from persistent storage.
     *
     * Does not validate expiration; use [hasValidSession] to check validity.
     *
     * @return [StepFullBedrockSession.FullBedrockSession] if saved, else `null`.
     *
     * **Example:**
     * ```kotlin
     * val session = manager.getCurrentSession()
     * println(session?.mcChain?.displayName ?: "No session")
     * ```
     */
    suspend fun getCurrentSession(): StepFullBedrockSession.FullBedrockSession? = withContext(Dispatchers.IO) {
        sessionManager.loadSavedSession(httpClient)
    }

    /**
     * Starts the Microsoft/Xbox Live authentication flow for Bedrock.
     *
     * The returned [AuthSession] handles:
     * - Redirecting the user to Microsoft login
     * - Receiving auth codes
     * - Exchanging them for Xbox Live and Bedrock tokens
     * - Saving the session
     *
     * @param callback Callback to receive authentication status and results.
     * @return An [AuthSession] to control the login process.
     *
     * **Example:**
     * ```kotlin
     * manager.startAuthFlow { status, session ->
     *     if (status == AuthStatus.SUCCESS) {
     *         println("Welcome ${session?.mcChain?.displayName}")
     *     }
     * }
     * ```
     */
    fun startAuthFlow(callback: AuthCallback): AuthSession {
        return AuthSession(httpClient, sessionManager, callback)
    }

    /**
     * Deletes any stored Bedrock session and clears cached user info.
     *
     * Call this when:
     * - User logs out
     * - Token refresh fails
     * - You want to force re-authentication
     *
     * **Example:**
     * ```kotlin
     * manager.clearSession()
     * println("Session cleared.")
     * ```
     */
    fun clearSession() {
        sessionManager.deleteSession()
        cachedUserInfo = null
        Log.d(TAG, "Session and cached user info cleared")
    }

    /**
     * Gets basic user profile info for the currently authenticated session.
     *
     * This includes:
     * - Player display name
     * - UUID
     * - Whether Realms access is available
     *
     * Uses a cached value if previously fetched to avoid extra network calls.
     *
     * @return [UserInfo] or `null` if no valid session exists.
     *
     * **Example:**
     * ```kotlin
     * val info = manager.getUserInfo()
     * println("Name: ${info?.displayName}, UUID: ${info?.uuid}")
     * ```
     */
    suspend fun getUserInfo(): UserInfo? = withContext(Dispatchers.IO) {
        if (cachedUserInfo != null) {
            Log.d(TAG, "Returning cached user info")
            return@withContext cachedUserInfo
        }

        getCurrentSession()?.let { session ->
            UserInfo(
                displayName = session.mcChain.displayName,
                uuid = session.mcChain.id.toString(),
                hasRealmsAccess = session.realmsXsts != null
            ).also {
                cachedUserInfo = it
                Log.d(TAG, "User info fetched and cached")
            }
        }
    }

    /**
     * Checks if the Minecraft Bedrock Realms service is currently available.
     *
     * Useful before attempting Realms operations to prevent wasted network calls.
     *
     * @return `true` if available, else `false`.
     *
     * **Example:**
     * ```kotlin
     * val available = manager.isRealmsAvailable()
     * println("Realms online: $available")
     * ```
     */
    suspend fun isRealmsAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext false
                }
            val realmsService = createRealmsService(session)
            realmsService.isAvailable().get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Realms availability", e)
            false
        }
    }

    /**
     * Fetches a list of all Realms worlds the user can access.
     *
     * This includes:
     * - Realms the user owns
     * - Realms the user was invited to
     *
     * @return List of [RealmsWorld] or `null` if fetching fails.
     *
     * **Example:**
     * ```kotlin
     * val worlds = manager.getRealmsWorlds()
     * worlds?.forEach { println("Realm: ${it.name}") }
     * ```
     */
    suspend fun getRealmsWorlds(): List<RealmsWorld>? = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext null
                }
            val realmsService = createRealmsService(session)
            realmsService.getWorlds().get().also {
                Log.d(TAG, "Fetched ${it.size} Realms worlds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Realms worlds", e)
            null
        }
    }

    /**
     * Attempts to join a given Realm.
     *
     * - Rejects joining if the Realm is expired
     * - Rejects joining if the Realm version is incompatible
     * - Returns the server's connection address on success
     *
     * @param realm The [RealmsWorld] to join.
     * @return The server address string, or `null` if joining fails.
     *
     * **Example:**
     * ```kotlin
     * val address = manager.joinRealm(realm)
     * println("Joined at: $address")
     * ```
     */
    suspend fun joinRealm(realm: RealmsWorld): String? = withContext(Dispatchers.IO) {
        try {
            if (realm.isExpired) {
                Log.e(TAG, "Cannot join expired realm: ${realm.name}")
                return@withContext null
            }
            if (!realm.isCompatible()) {
                Log.e(TAG, "Cannot join incompatible realm: ${realm.name}")
                return@withContext null
            }
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext null
                }
            val realmsService = createRealmsService(session)
            val address = realmsService.joinWorld(realm).get()
            Log.d(TAG, "Joined realm ${realm.name} at address $address")
            address
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join realm ${realm.name}", e)
            null
        }
    }

    /**
     * Accepts an invite to a Realm using an invite code.
     *
     * @param inviteCode The unique Realm invitation code.
     * @return The joined [RealmsWorld] or `null` if acceptance fails.
     *
     * **Example:**
     * ```kotlin
     * val realm = manager.acceptRealmInvite("NcPbAMg8wSymui8")
     * println("Joined realm: ${realm?.name}")
     * ```
     */
    suspend fun acceptRealmInvite(inviteCode: String): RealmsWorld? = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext null
                }
            val realmsService = createRealmsService(session)
            val realm = realmsService.acceptInvite(inviteCode).get()
            Log.d(TAG, "Accepted invite for realm ${realm.name}")
            realm
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept realm invite with code $inviteCode", e)
            null
        }
    }

    /**
     * Leaves a Realm the user was invited to.
     *
     * @param realm The target [RealmsWorld] to leave.
     * @return `true` if leaving succeeded, `false` otherwise.
     *
     * **Example:**
     * ```kotlin
     * val success = manager.leaveRealm(realm)
     * println("Left realm: $success")
     * ```
     */
    suspend fun leaveRealm(realm: RealmsWorld): Boolean = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext false
                }
            val realmsService = createRealmsService(session)
            realmsService.leaveInvitedRealm(realm).get()
            Log.d(TAG, "Left realm ${realm.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to leave realm ${realm.name}", e)
            false
        }
    }

    /**
     * Refreshes the current session to renew expired tokens,
     * then re-fetches the Realms world list.
     *
     * This ensures the user’s Realms list is always fetched with
     * valid credentials, even if the original tokens expired.
     *
     * @return Updated list of [RealmsWorld] or `null` if refresh fails.
     *
     * **Example:**
     * ```kotlin
     * val refreshedWorlds = manager.refreshRealms()
     * println("Fetched ${refreshedWorlds?.size ?: 0} worlds after refresh")
     * ```
     */
    suspend fun refreshRealms(): List<RealmsWorld>? = withContext(Dispatchers.IO) {
        try {
            val session = sessionManager.loadSavedSession(httpClient)
                ?: run {
                    Log.e(TAG, "No valid session found")
                    return@withContext null
                }
            val refreshedSession = sessionManager.authFlow.refresh(httpClient, session)
            if (refreshedSession.realmsXsts == null) {
                Log.e(TAG, "Refreshed session missing realmsXsts token")
                sessionManager.deleteSession()
                return@withContext null
            }
            sessionManager.saveSession(refreshedSession)
            val realmsService = createRealmsService(refreshedSession)
            realmsService.getWorlds().get().also {
                Log.d(TAG, "Refreshed session and fetched ${it.size} Realms worlds")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh Realms", e)
            null
        }
    }

    /**
     * Creates a [BedrockRealmsService] instance bound to a specific session.
     *
     * @param session The active authenticated Bedrock session.
     * @return Configured [BedrockRealmsService] ready for Realms calls.
     */
    private fun createRealmsService(session: StepFullBedrockSession.FullBedrockSession): BedrockRealmsService {
        return BedrockRealmsService(httpClient, BEDROCK_VERSION, session.realmsXsts)
    }
}
