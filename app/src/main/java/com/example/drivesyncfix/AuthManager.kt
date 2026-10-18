package com.example.drivesyncfix

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

/**
 * Wraps Google Sign-In + access token retrieval for Drive API calls.
 *
 * You must create your own OAuth client in Google Cloud Console (see README)
 * and enable the Drive API there -- this app has no bundled credentials.
 */
object AuthManager {

    // Full Drive access -- needed because we're writing into a pre-existing folder
    // (your current upload destination with prior data in it), not one this app
    // creates itself. drive.file scope can't see/write into folders it didn't
    // create or that weren't explicitly granted via the Google Picker, so it
    // won't work for "use my existing folder" without extra picker plumbing.
    //
    // This is fine for personal/sideloaded use -- you'll see a one-time
    // "Google hasn't verified this app" warning during sign-in since you're
    // using your own unreviewed OAuth client. Click Advanced -> Go to
    // DriveSyncFix (unsafe) to proceed. That warning exists because Google
    // hasn't reviewed the app, not because anything is actually wrong with it.
    private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"

    fun getSignInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_SCOPE))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    fun getLastSignedInAccount(context: Context): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    @Volatile private var cachedToken: String? = null

    /**
     * Blocking call -- must run off the main thread. Fetches an OAuth2 access token.
     */
    fun getFreshAccessToken(context: Context): String {
        cachedToken?.let { return it }
        return refreshAccessToken(context)
    }

    /**
     * Clears the cached token and forces GoogleAuthUtil to fetch a brand new token from Google.
     * Call this when an HTTP 401 occurs.
     */
    @Synchronized
    fun refreshAccessToken(context: Context): String {
        val account = getLastSignedInAccount(context)
            ?: throw IllegalStateException("No signed-in Google account")
        cachedToken?.let { token ->
            runCatching { GoogleAuthUtil.clearToken(context, token) }
        }
        val newToken = GoogleAuthUtil.getToken(context, account.account!!, "oauth2:$DRIVE_SCOPE")
        cachedToken = newToken
        return newToken
    }
}
