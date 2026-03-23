package com.nuvio.tv.core.server

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Local HTTP server for Diobot AI Concierge.
 * Phone connects via QR code, sends voice/text messages,
 * TV processes via OpenAI and sends back responses.
 * Phone can also send commands (navigate to detail, play stream).
 */
class DiobotServer(
    port: Int = 8100
) : NanoHTTPD("0.0.0.0", port) {

    companion object {
        private const val TAG = "DiobotServer"
        private val gson = Gson()

        fun startOnAvailablePort(
            startPort: Int = 8100,
            maxAttempts: Int = 20
        ): DiobotServer? {
            for (port in startPort until startPort + maxAttempts) {
                try {
                    val server = DiobotServer(port)
                    server.start(SOCKET_READ_TIMEOUT, true)
                    Thread.sleep(200)
                    Log.d(TAG, "Diobot server started on port $port")
                    return server
                } catch (e: Exception) {
                    Log.w(TAG, "Port $port unavailable: ${e.message}")
                }
            }
            return null
        }
    }

    /** Commands from phone to TV */
    sealed class TvCommand {
        data class NavigateToDetail(val itemId: String, val itemType: String) : TvCommand()
        data class PlayContent(val itemId: String, val itemType: String) : TvCommand()
        data class SaveToLibrary(val itemId: String, val itemType: String, val title: String) : TvCommand()
    }

    private val _commands = MutableSharedFlow<TvCommand>(extraBufferCapacity = 10)
    val commands: SharedFlow<TvCommand> = _commands.asSharedFlow()

    // Chat handler set by ViewModel
    @Volatile
    var chatHandler: (suspend (String) -> ChatResponse)? = null

    data class ChatResponse(
        val message: String,
        val suggestions: List<SuggestionDto>
    )

    data class SuggestionDto(
        val title: String,
        val type: String,
        val tmdbId: Int?,
        val imdbId: String?,
        val poster: String?
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // CORS headers for phone browser
        val corsHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type"
        )

        if (method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").also { resp ->
                corsHeaders.forEach { (k, v) -> resp.addHeader(k, v) }
            }
        }

        val response = when {
            method == Method.GET && uri == "/" -> serveChatPage()
            method == Method.GET && uri == "/health" ->
                newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
            method == Method.POST && uri == "/api/chat" -> handleChat(session)
            method == Method.POST && uri == "/api/command" -> handleCommand(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        corsHeaders.forEach { (k, v) -> response.addHeader(k, v) }
        return response
    }

    private fun serveChatPage(): Response {
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html",
            DiobotWebPage.getHtml()
        )
    }

    private fun handleChat(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val message = json.get("message")?.asString ?: ""

            if (message.isBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    """{"error":"Empty message"}"""
                )
            }

            val handler = chatHandler
            if (handler == null) {
                return newFixedLengthResponse(
                    Response.Status.SERVICE_UNAVAILABLE,
                    "application/json",
                    """{"error":"Diobot not ready"}"""
                )
            }

            // Run chat synchronously (NanoHTTPD blocks per request)
            val result = try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) { handler(message) }
            } catch (e: Exception) {
                Log.e(TAG, "Chat handler error", e)
                ChatResponse(message = "Erro interno: ${e.message}", suggestions = emptyList())
            }

            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                gson.toJson(result)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Internal error"}"""
            )
        }
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        val body = bodyMap["postData"] ?: ""

        return try {
            val json = JsonParser.parseString(body).asJsonObject
            val action = json.get("action")?.asString ?: ""
            val itemId = json.get("itemId")?.asString ?: ""
            val itemType = json.get("itemType")?.asString ?: "movie"

            when (action) {
                "detail" -> {
                    _commands.tryEmit(TvCommand.NavigateToDetail(itemId, itemType))
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }
                "play" -> {
                    _commands.tryEmit(TvCommand.PlayContent(itemId, itemType))
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }
                "save" -> {
                    val title = json.get("title")?.asString ?: ""
                    _commands.tryEmit(TvCommand.SaveToLibrary(itemId, itemType, title))
                    newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"ok"}""")
                }
                else -> newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"Unknown action"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Command error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", """{"error":"Internal error"}""")
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping Diobot server")
        super.stop()
    }
}
