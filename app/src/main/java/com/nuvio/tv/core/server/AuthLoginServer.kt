package com.nuvio.tv.core.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthLoginServer(
    private val supabaseUrl: String,
    private val supabaseAnonKey: String,
    port: Int = 8090
) : NanoHTTPD("0.0.0.0", port) {

    data class AuthTokens(
        val accessToken: String,
        val refreshToken: String
    )

    @Volatile
    private var receivedTokens: AuthTokens? = null
    private val tokenLatch = CountDownLatch(1)

    fun awaitTokens(timeoutSeconds: Long = 300): AuthTokens? {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (tokenLatch.await(1, TimeUnit.SECONDS)) break
            if (Thread.currentThread().isInterrupted) return null
        }
        return receivedTokens
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "serve: $method $uri")

        return when {
            method == Method.GET && uri == "/" -> serveLoginPage()
            method == Method.GET && uri == "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
            method == Method.POST && uri == "/api/auth-callback" -> handleAuthCallback(session)
            method == Method.GET && uri == "/api/status" -> serveStatus()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
    }

    private fun serveLoginPage(): Response {
        Log.d(TAG, "Serving login page")
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            AuthLoginWebPage.getHtml(supabaseUrl, supabaseAnonKey)
        )
    }

    private fun handleAuthCallback(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""
        Log.d(TAG, "Auth callback received")

        return try {
            val json = com.google.gson.JsonParser.parseString(body).asJsonObject
            val accessToken = json.get("access_token")?.asString
            val refreshToken = json.get("refresh_token")?.asString

            if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
                Log.w(TAG, "Auth callback missing tokens")
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error":"Missing tokens"}"""
                )
            } else {
                Log.d(TAG, "Auth callback tokens received successfully")
                receivedTokens = AuthTokens(accessToken, refreshToken)
                tokenLatch.countDown()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"ok"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth callback error", e)
            newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                """{"error":"Invalid request"}"""
            )
        }
    }

    private fun serveStatus(): Response {
        val status = if (receivedTokens != null) "authenticated" else "waiting"
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"status":"$status"}"""
        )
    }

    override fun stop() {
        Log.d(TAG, "Stopping auth login server on port $listeningPort")
        super.stop()
    }

    companion object {
        private const val TAG = "AuthLoginServer"

        fun startOnAvailablePort(
            supabaseUrl: String,
            supabaseAnonKey: String,
            startPort: Int = 8090,
            maxAttempts: Int = 20
        ): AuthLoginServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = AuthLoginServer(supabaseUrl, supabaseAnonKey, port)
                    server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                    // Give the server socket a moment to become ready
                    Thread.sleep(200)
                    Log.d(TAG, "Auth login server started on port $port (isAlive=${server.isAlive})")
                    return server
                } catch (e: Exception) {
                    Log.w(TAG, "Port $port unavailable: ${e.message}")
                }
            }
            Log.e(TAG, "Failed to start auth login server on any port ($startPort-${startPort + maxAttempts - 1})")
            return null
        }
    }
}
