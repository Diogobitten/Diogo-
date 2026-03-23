package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.openai.ChatCompletionRequest
import com.nuvio.tv.data.remote.dto.openai.ChatCompletionResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface OpenAiApi {
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: ChatCompletionRequest
    ): Response<ChatCompletionResponse>
}
