package ca.mohawk.temi8

data class Message(
    val text: String,
    val isUser: Boolean // true for user, false for OpenAI
)