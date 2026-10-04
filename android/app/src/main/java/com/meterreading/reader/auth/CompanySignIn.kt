package com.meterreading.reader.auth

import android.app.Activity
import android.content.Context
import com.meterreading.reader.api.SignInRequiredException
import com.meterreading.reader.data.EntraSettings
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAccount
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalServiceException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Company sign-in (spec FR-001). The API client asks it for a token on every call. */
interface CompanySignIn {
    /** The account remembered on this phone, or null when nobody has signed in yet. */
    suspend fun currentAccount(): String?

    /** Shows the Microsoft sign-in page if needed and returns the reader's sign-in name. */
    suspend fun signIn(activity: Activity): String

    /** A current access token for the API, refreshed silently. [SignInRequiredException] when it cannot be. */
    suspend fun token(): String

    suspend fun signOut()
}

/** Company sign-in failed; [message] is safe to show the reader. */
class CompanySignInException(message: String) : Exception(message)

/** The reader closed the Microsoft sign-in page. Not an error: nothing to show. */
class SignInCancelledException : Exception("Sign-in cancelled")

/**
 * [CompanySignIn] with Microsoft's MSAL library: one work account per phone, using the
 * Authenticator or Company Portal app as broker when installed. The values come from Settings,
 * so the configuration file is written at run time instead of shipped in the app.
 */
class MsalCompanySignIn(private val context: Context, private val settings: EntraSettings) : CompanySignIn {
    private val scopes = listOf(settings.apiScope)
    private val lock = Mutex()
    private var app: ISingleAccountPublicClientApplication? = null

    private suspend fun app(): ISingleAccountPublicClientApplication = lock.withLock {
        app ?: withContext(Dispatchers.IO) {
            val config = File(context.noBackupFilesDir, "msal_config.json").apply { writeText(settings.toMsalConfigJson()) }
            try {
                PublicClientApplication.createSingleAccountPublicClientApplication(context, config)
            } catch (e: MsalException) {
                throw CompanySignInException("Company sign-in is not set up correctly. Ask IT to check Settings (${e.errorCode}).")
            }
        }.also { app = it }
    }

    private suspend fun account(app: ISingleAccountPublicClientApplication): IAccount? =
        withContext(Dispatchers.IO) { app.currentAccount.currentAccount }

    override suspend fun currentAccount(): String? = account(app())?.username

    override suspend fun signIn(activity: Activity): String {
        val app = app()
        val existing = account(app)
        if (existing != null) {
            if (runCatching { token() }.isSuccess) return existing.username
            return interactive { callback -> app.signInAgain(parameters(activity, callback)) }
        }
        return interactive { callback -> app.signIn(parameters(activity, callback)) }
    }

    override suspend fun token(): String = withContext(Dispatchers.IO) {
        val app = app()
        val account = app.currentAccount.currentAccount ?: throw SignInRequiredException("Sign in with your company account.")
        try {
            app.acquireTokenSilent(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(scopes)
                    .build(),
            ).accessToken
        } catch (e: MsalUiRequiredException) {
            throw SignInRequiredException("Your company sign-in has run out. Sign in again.")
        } catch (e: MsalException) {
            // Usually no signal: treated like any network failure, so readings wait on the phone.
            throw IOException("Company sign-in could not be checked (${e.errorCode}).", e)
        }
    }

    override suspend fun signOut() {
        val app = app()
        withContext(Dispatchers.IO) { runCatching { app.signOut() } }
    }

    private fun parameters(activity: Activity, callback: AuthenticationCallback): SignInParameters =
        SignInParameters.builder().withActivity(activity).withScopes(scopes).withCallback(callback).build()

    private suspend fun interactive(start: (AuthenticationCallback) -> Unit): String = suspendCancellableCoroutine { cont ->
        start(object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) = cont.resume(result.account.username)
            override fun onCancel() = cont.resumeWithException(SignInCancelledException())
            override fun onError(e: MsalException) = cont.resumeWithException(CompanySignInException(message(e)))
        })
    }

    private fun message(e: MsalException): String = when (e) {
        is MsalServiceException -> "Company sign-in was refused. Ask IT to check your account (${e.errorCode})."
        is MsalClientException -> "Company sign-in did not work. Check the signal, then ask IT if it keeps failing (${e.errorCode})."
        else -> "Company sign-in did not work (${e.errorCode})."
    }
}
