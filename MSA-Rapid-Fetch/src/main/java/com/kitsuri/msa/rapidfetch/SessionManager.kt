package com.kitsuri.msa.rapidfetch

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.MinecraftAuth
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import net.raphimc.minecraftauth.util.MicrosoftConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Handles persistence and retrieval of Minecraft Bedrock authentication sessions.
 *
 * Responsibilities:
 * - Saves sessions to local storage as JSON.
 * - Loads previously saved sessions if still valid.
 * - Automatically refreshes expired or outdated sessions when possible.
 * - Deletes invalid or unusable sessions from storage.
 *
 * This class also provides the configured authentication flow ([authFlow]) used
 * by [AuthSession] to perform Microsoft device code authentication.
 *
 * @property context Application context used for file storage.
 */
internal class SessionManager(private val context: Context) {

    companion object {
        private const val TAG = "SessionManager"

        /** File name where the Bedrock session JSON is stored. */
        private const val SESSION_FILE = "bedrock_session.json"
    }

    /**
     * Pre-configured Microsoft authentication flow for Minecraft Bedrock.
     *
     * Flow configuration:
     * - Uses the official Bedrock Android client ID.
     * - Requests the `SCOPE_TITLE_AUTH` scope.
     * - Authenticates using Microsoft device code flow.
     * - Includes an Android-specific device token.
     * - Performs XSTS authentication for the Bedrock Realms relying party.
     * - Builds the Minecraft Bedrock authentication chain including Xbox and Realms tokens.
     *
     * **Example:**
     * ```kotlin
     * val manager = SessionManager(context)
     * val flow = manager.authFlow
     * // You can now start device code authentication with this flow
     * ```
     */
    internal val authFlow = MinecraftAuth.builder()
        .withClientId(MicrosoftConstants.BEDROCK_ANDROID_TITLE_ID)
        .withScope(MicrosoftConstants.SCOPE_TITLE_AUTH)
        .deviceCode()
        .withDeviceToken("Android")
        .sisuTitleAuthentication(MicrosoftConstants.BEDROCK_XSTS_RELYING_PARTY)
        .buildMinecraftBedrockChainStep(true, true)

    /**
     * Loads a previously saved session from disk if it exists and is valid.
     *
     * Behavior:
     * - Returns `null` if no session file exists or if it's empty/invalid.
     * - If the session is expired or outdated, attempts to refresh it using [authFlow].
     * - If refresh succeeds, saves the new session and returns it.
     * - If refresh fails or token data is missing, deletes the session file and returns `null`.
     *
     * @param httpClient HTTP client used for refreshing sessions.
     * @return A valid [StepFullBedrockSession.FullBedrockSession] if available, or `null` otherwise.
     *
     * **Example:**
     * ```kotlin
     * val httpClient = HttpClient()
     * val session = SessionManager(context).loadSavedSession(httpClient)
     * if (session != null) {
     *     println("Loaded session with gamertag: ${session.profile?.name}")
     * } else {
     *     println("No valid session found.")
     * }
     * ```
     */
    fun loadSavedSession(httpClient: HttpClient): StepFullBedrockSession.FullBedrockSession? {
        return try {
            val file = File(context.filesDir, SESSION_FILE)
            if (!file.exists()) {
                Log.d(TAG, "Session file does not exist")
                return null
            }

            val jsonString = FileInputStream(file).use { fis ->
                fis.readBytes().toString(Charsets.UTF_8)
            }

            if (jsonString.isBlank()) {
                Log.e(TAG, "Session file is empty")
                deleteSession()
                return null
            }

            val json = JsonParser.parseString(jsonString) as JsonObject
            val session = authFlow.fromJson(json)

            if (session.realmsXsts == null) {
                Log.e(TAG, "Session missing realmsXsts token, discarding")
                deleteSession()
                return null
            }

            if (session.isExpiredOrOutdated()) {
                Log.d(TAG, "Session is expired/outdated, attempting to refresh")
                try {
                    val refreshedSession = authFlow.refresh(httpClient, session)
                    if (refreshedSession.realmsXsts == null) {
                        Log.e(TAG, "Refreshed session missing realmsXsts token")
                        deleteSession()
                        return null
                    }
                    saveSession(refreshedSession)
                    refreshedSession
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh session", e)
                    deleteSession()
                    return null
                }
            } else {
                session
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load session", e)
            deleteSession()
            null
        }
    }

    /**
     * Saves the given [session] to disk as JSON.
     *
     * Overwrites any existing session file.
     *
     * @param session The authenticated Bedrock session to persist.
     * @throws Exception If writing to disk fails.
     *
     * **Example:**
     * ```kotlin
     * val httpClient = HttpClient()
     * val manager = SessionManager(context)
     * val session = manager.authFlow.loginWithDeviceCode(httpClient) // Example login
     * manager.saveSession(session)
     * ```
     */
    fun saveSession(session: StepFullBedrockSession.FullBedrockSession) {
        try {
            val json = authFlow.toJson(session)
            val file = File(context.filesDir, SESSION_FILE)
            FileOutputStream(file).use { fos ->
                fos.write(json.toString().toByteArray())
                Log.d(TAG, "Session saved to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session", e)
            throw e
        }
    }

    /**
     * Deletes the stored session file, if it exists.
     *
     * Used when sessions become invalid, expired, or corrupted.
     *
     * **Example:**
     * ```kotlin
     * val manager = SessionManager(context)
     * manager.deleteSession()
     * println("Session file removed.")
     * ```
     */
    fun deleteSession() {
        try {
            val file = File(context.filesDir, SESSION_FILE)
            if (file.exists()) {
                if (file.delete()) {
                    Log.d(TAG, "Session file deleted")
                } else {
                    Log.e(TAG, "Failed to delete session file")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting session file", e)
        }
    }
}
