package ca.mohawk.temi8

import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface OpenAIApiService {
    @Multipart
    @POST("audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part model: MultipartBody.Part
    ): TranscriptionResponse

    @POST("chat/completions")
    suspend fun getChatResponse(@Body request: ChatRequest): ChatResponse
}

// Data Classes
data class TranscriptionResponse(val text: String)
data class ChatRequest(val model: String, val messages: List<ChatMessage>)
data class ChatMessage(val role: String, val content: String)
data class ChatResponse(val choices: List<Choice>)
data class Choice(val message: ChatMessage)
