package com.couchtommouth.bridge.payment

import android.content.Context
import android.util.Log
import com.couchtommouth.bridge.config.AppConfig
import com.google.gson.Gson
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches the SumUp access token the POS holds on our behalf, so the SDK can be
 * logged in without anyone typing merchant credentials.
 *
 * The SumUp SDK forgets its login whenever the app process dies — that is by
 * design on their side, and the supported answer is to log in with an OAuth
 * access token instead. The refresh token that mints these lives on the POS
 * server, because the OAuth client secret needed to use it must never ship
 * inside an APK.
 *
 * The token is held in memory only, never written to disk. Caching it would buy
 * nothing: this app is a WebView onto the POS, so if the server is unreachable
 * the till has no orders to take anyway — and a payment-capable token sitting
 * in SharedPreferences is a needless thing to leave on a counter tablet.
 */
class SumUpSession(context: Context) {

    companion object {
        private const val TAG = "SumUpSession"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val TOKEN_PATH = "api/sumup/token"
        // Treat a token as spent slightly early so it can't expire mid-sale.
        private const val EXPIRY_GUARD_MS = 60_000L

        // Process-wide, because the SumUp SDK session is process-wide: Main and
        // Settings each build their own PaymentManager but must agree on who is
        // signed in, and must not each fetch their own token.
        @Volatile
        private var cached: Token? = null

        @Volatile
        private var merchantCode: String? = null
    }

    private val config = AppConfig(context)
    private val gson = Gson()

    /**
     * Merchant from the last successful fetch. Kept after the token itself
     * expires so Settings can still say who the till is signed in as.
     */
    val lastMerchantCode: String?
        get() = merchantCode

    data class Token(
        val accessToken: String,
        val expiresAtMs: Long,
        val merchantCode: String?
    )

    sealed class TokenResult {
        data class Success(val token: Token) : TokenResult()

        /** No POS staff session yet — normal while the login page is showing. */
        object NotSignedIn : TokenResult()

        /** Silent login is off, unauthorised, or the server errored. */
        data class Unavailable(val reason: String) : TokenResult()
    }

    /** The token from a previous fetch, if it is still good. */
    fun cachedToken(): Token? = cached?.takeIf {
        System.currentTimeMillis() < it.expiresAtMs - EXPIRY_GUARD_MS
    }

    /** Drop the cached token after SumUp rejects it, so the next call refetches. */
    fun invalidate() {
        cached = null
    }

    /**
     * Ask the POS for an access token. Blocking — call from a background thread.
     *
     * [cookie] is the WebView's cookie header (read it on the main thread via
     * CookieManager and pass it in); it carries the POS staff session that
     * authorises this request.
     */
    fun fetchToken(cookie: String?): TokenResult {
        cachedToken()?.let { return TokenResult.Success(it) }

        if (cookie.isNullOrBlank()) return TokenResult.NotSignedIn

        val url = buildTokenUrl() ?: return TokenResult.Unavailable("POS URL not set")
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cookie", cookie)
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseToken(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_UNAUTHORIZED -> TokenResult.NotSignedIn
                else -> TokenResult.Unavailable("POS returned HTTP $code: ${readError(connection)}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch a SumUp token", e)
            TokenResult.Unavailable(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseToken(body: String): TokenResult {
        val payload = try {
            gson.fromJson(body, TokenPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed token response", e)
            null
        }
        val accessToken = payload?.access_token
        if (accessToken.isNullOrBlank()) {
            return TokenResult.Unavailable("POS returned no access token")
        }
        val token = Token(
            accessToken = accessToken,
            expiresAtMs = parseExpiry(payload.expires_at),
            merchantCode = payload.merchant_code
        )
        cached = token
        token.merchantCode?.let { merchantCode = it }
        Log.d(TAG, "Got a SumUp token (merchant=${token.merchantCode ?: "unknown"})")
        return TokenResult.Success(token)
    }

    /**
     * ISO-8601 from the API. On an unparseable value assume the SumUp default of
     * an hour: worst case the token is refetched sooner than it needed to be.
     */
    private fun parseExpiry(raw: String?): Long {
        if (raw.isNullOrBlank()) return System.currentTimeMillis() + 3_600_000L
        return try {
            java.time.Instant.parse(raw.replace(" ", "T")).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                Log.w(TAG, "Unparseable token expiry '$raw'; assuming one hour")
                System.currentTimeMillis() + 3_600_000L
            }
        }
    }

    private fun readError(connection: HttpURLConnection): String = try {
        connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200).orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun buildTokenUrl(): String? {
        val base = config.getPosUrl().trim()
        if (base.isEmpty()) return null
        return if (base.endsWith("/")) "$base$TOKEN_PATH" else "$base/$TOKEN_PATH"
    }

    private data class TokenPayload(
        val access_token: String?,
        val expires_at: String?,
        val merchant_code: String?
    )
}
