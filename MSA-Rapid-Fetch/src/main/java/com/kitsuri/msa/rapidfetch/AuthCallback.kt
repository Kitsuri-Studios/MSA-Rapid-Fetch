package com.kitsuri.msa.rapidfetch

import net.raphimc.minecraftauth.step.bedrock.session.StepFullBedrockSession

/**
 * Callback interface used to handle the different stages of the
 * Minecraft Bedrock authentication flow.
 *
 * This is typically implemented by a class that initiates
 * authentication and needs to react to status updates.
 *
 * The flow usually works like this:
 * 1. A device code is generated — the user needs to enter it in a browser to sign in.
 * 2. Once the user completes the login, an authenticated session object is received.
 * 3. If something goes wrong, an error message is sent instead.
 */
interface AuthCallback {

    /**
     * Called when the authentication process successfully generates
     * a device code and verification URL for the user.
     *
     * The user must go to [verificationUri] and enter [userCode]
     * to link their account.
     *
     * Example usage:
     * ```
     * onDeviceCodeReceived("ABCD-EFGH", "https://www.microsoft.com/link")
     * // Show the code and URL to the user in the UI.
     * ```
     *
     * @param userCode       Short code the user enters in the browser.
     * @param verificationUri URL where the user should enter the code.
     */
    fun onDeviceCodeReceived(userCode: String, verificationUri: String)

    /**
     * Called when the authentication process completes successfully.
     *
     * This provides a fully authenticated Bedrock session,
     * which contains all the credentials needed to join servers or
     * make authenticated API requests.
     *
     * Example usage:
     * ```
     * onAuthSuccess(session)
     * // Save session tokens to reuse later, or launch the game.
     * ```
     *
     * @param session The fully authenticated Bedrock session.
     */
    fun onAuthSuccess(session: StepFullBedrockSession.FullBedrockSession)

    /**
     * Called if the authentication process fails for any reason.
     *
     * This could happen if:
     * - The user didn’t complete the login in time.
     * - Network issues occurred.
     * - The server returned an invalid response.
     *
     * Example usage:
     * ```
     * onAuthError("Network timeout")
     * // Show error message to the user and retry.
     * ```
     *
     * @param error Human-readable error message describing what went wrong.
     */
    fun onAuthError(error: String)
}
