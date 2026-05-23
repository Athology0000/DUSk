package org.phantom.api.module

sealed class Result {
    object Ok : Result()
    data class Denied(val reason: String) : Result()
    data class Error(val cause: Throwable) : Result()
}
