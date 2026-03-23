package com.nuvio.tv.data.remote.dto.openai

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ChatCompletionRequest(
    @Json(name = "model") val model: String = "gpt-4o-mini",
    @Json(name = "messages") val messages: List<ChatMessage>,
    @Json(name = "temperature") val temperature: Double = 0.7,
    @Json(name = "max_tokens") val maxTokens: Int = 1024
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    @Json(name = "role") val role: String,
    @Json(name = "content") val content: String
)

@JsonClass(generateAdapter = true)
data class ChatCompletionResponse(
    @Json(name = "id") val id: String?,
    @Json(name = "choices") val choices: List<ChatChoice>?
)

@JsonClass(generateAdapter = true)
data class ChatChoice(
    @Json(name = "index") val index: Int?,
    @Json(name = "message") val message: ChatMessage?,
    @Json(name = "finish_reason") val finishReason: String?
)
