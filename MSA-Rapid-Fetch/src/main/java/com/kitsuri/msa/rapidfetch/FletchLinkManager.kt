package com.kitsuri.msa.rapidfetch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.service.realms.BedrockRealmsService
import net.raphimc.minecraftauth.service.realms.model.RealmsWorld
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import net.raphimc.minecraftauth.util.MicrosoftConstants
import java.util.concurrent.CompletableFuture

/**
 * Main entry point for FletchLink Core functionality, including Bedrock Realms operations
 */
class FletchLinkManager private constructor(private val context: Context) {

    private val sessionManager = SessionManager(context)
    private val httpClient = MinecraftAuth.createHttpClient()
    private var cachedUserInfo: UserInfo? = null

    companion object {
        private const val TAG = "FletchLinkManager"
        private const val BEDROCK_VERSION = "1.21.30" // Adjust to the latest Bedrock version as needed

        @Volatile
        private var INSTANCE: FletchLinkManager? = null

        fun getInstance(context: Context): FletchLinkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FletchLinkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Check if user has a valid session
     */
    suspend fun hasValidSession(): Boolean = withContext(Dispatchers.IO) {
        val session = sessionManager.loadSavedSession(httpClient)
        session != null && session.realmsXsts != null && !session.isExpiredOrOutdated()
    }

    /**
     * Get current session if valid, null otherwise
     */
    suspend fun getCurrentSession(): StepFullBedrockSession.FullBedrockSession? = withContext(Dispatchers.IO) {
        sessionManager.loadSavedSession(httpClient)
    }

    /**
     * Start authentication flow
     */
    fun startAuthFlow(callback: AuthCallback): AuthSession {
        return AuthSession(httpClient, sessionManager, callback)
    }

    /**
     * Clear saved session and cached user info
     */
    fun clearSession() {
        sessionManager.deleteSession()
        cachedUserInfo = null
        Log.d(TAG, "Session and cached user info cleared")
    }

    /**
     * Get user info from current session, using cache if available
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
     * Checks if Realms service is available
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
     * Fetches the list of Realms worlds for the current session
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
     * Joins a specific Realms world and returns the server address
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
     * Accepts an invite to a Realm using an invite code
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
     * Leaves an invited Realm
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
     * Refreshes the session and re-fetches the Realms worlds
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
     * Creates a BedrockRealmsService instance for the given session
     */
    private fun createRealmsService(session: StepFullBedrockSession.FullBedrockSession): BedrockRealmsService {
        return BedrockRealmsService(httpClient, BEDROCK_VERSION, session.realmsXsts)
    }
}