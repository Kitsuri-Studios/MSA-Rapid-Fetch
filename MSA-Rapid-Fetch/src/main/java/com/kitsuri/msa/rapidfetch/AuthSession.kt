package com.kitsuri.msa.rapidfetch

import android.util.Log
import net.lenni0451.commons.httpclient.HttpClient
import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode
import java.util.concurrent.CompletableFuture

/**
 * Handles the Microsoft authentication flow for Minecraft Bedrock,
 * including device code retrieval, user verification, session saving, and error handling.
 *
 * This class runs the authentication process asynchronously and reports progress and results
 * via the provided [AuthCallback].
 *
 * Usage:
 * 1. Create an instance via [FletchLinkManager.startAuthFlow].
 * 2. Call [start] to begin the authentication process.
 * 3. Implement [AuthCallback] to handle events like receiving the device code or successful authentication.
 * 4. Optionally call [cancel] to abort the process.
 *
 * @property httpClient The HTTP client used for making authentication requests.
 * @property sessionManager Manages saving and loading of authenticated sessions.
 * @property callback The callback interface for authentication events.
 */
class AuthSession internal constructor(
    private val httpClient: HttpClient,
    private val sessionManager: SessionManager,
    private val callback: AuthCallback
) {

    companion object {
        private const val TAG = "AuthSession"
    }

    /**
     * Holds the ongoing authentication process as a [CompletableFuture].
     * This allows cancellation or chaining of tasks after authentication completes.
     */
    private var authFuture: CompletableFuture<StepFullBedrockSession.FullBedrockSession>? = null

    /**
     * Starts the Microsoft authentication flow asynchronously.
     *
     * Steps performed:
     * - Requests a device code from Microsoft's OAuth service.
     * - Invokes [AuthCallback.onDeviceCodeReceived] so the user can complete verification in their browser.
     * - Waits for the user to complete verification.
     * - Obtains a full Bedrock session with a Realms XSTS token.
     * - Saves the session via [SessionManager].
     *
     * On success: Calls [AuthCallback.onAuthSuccess].
     * On failure: Calls [AuthCallback.onAuthError].
     *
     * @throws IllegalStateException If authentication succeeds but the Realms XSTS token is missing.
     *
     * **Example:**
     * ```kotlin
     * val authSession = AuthSession(httpClient, sessionManager, object : AuthCallback {
     *     override fun onDeviceCodeReceived(userCode: String, verificationUri: String) {
     *         println("Go to $verificationUri and enter code: $userCode")
     *     }
     *
     *     override fun onAuthSuccess(session: StepFullBedrockSession.FullBedrockSession) {
     *         println("Authentication successful: $session")
     *     }
     *
     *     override fun onAuthError(errorMessage: String) {
     *         println("Authentication failed: $errorMessage")
     *     }
     * })
     * authSession.start()
     * ```
     */
    fun start() {
        authFuture = CompletableFuture.supplyAsync {
            try {
                Log.d(TAG, "Starting authentication flow")
                val session = sessionManager.authFlow.getFromInput(
                    httpClient,
                    StepMsaDeviceCode.MsaDeviceCodeCallback { msaDeviceCode ->
                        Log.d(TAG, "Device code received: ${msaDeviceCode.userCode}")
                        callback.onDeviceCodeReceived(msaDeviceCode.userCode, msaDeviceCode.verificationUri)
                    }
                ) as StepFullBedrockSession.FullBedrockSession

                if (session.realmsXsts == null) {
                    throw IllegalStateException("Authentication succeeded but realmsXsts token is missing")
                }

                sessionManager.saveSession(session)
                session
            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed", e)
                callback.onAuthError(e.message ?: "Unknown error")
                throw e
            }
        }.whenComplete { session, throwable ->
            when {
                throwable != null -> {
                    if (!throwable.isCancellation()) {
                        callback.onAuthError(throwable.message ?: "Unknown error")
                    }
                }
                session != null -> {
                    callback.onAuthSuccess(session)
                }
            }
        }
    }

    /**
     * Determines if the given [Throwable] is a cancellation event.
     *
     * @return `true` if the throwable or its cause is a [java.util.concurrent.CancellationException], `false` otherwise.
     *
     * **Example:**
     * ```kotlin
     * try {
     *     // some code that may throw
     * } catch (t: Throwable) {
     *     if (t.isCancellation()) {
     *         println("Operation was cancelled.")
     *     }
     * }
     * ```
     */
    private fun Throwable.isCancellation(): Boolean {
        return this is java.util.concurrent.CancellationException ||
                this.cause is java.util.concurrent.CancellationException
    }

    /**
     * Cancels the ongoing authentication process, if running.
     *
     * This will interrupt the background task and prevent further callbacks,
     * except for possible [AuthCallback.onAuthError] if already triggered.
     *
     * **Example:**
     * ```kotlin
     * authSession.cancel()
     * println("Authentication flow cancelled.")
     * ```
     */
    fun cancel() {
        authFuture?.cancel(true)
    }
}
