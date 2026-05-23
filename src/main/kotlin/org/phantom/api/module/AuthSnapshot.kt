package org.phantom.api.module

/** Read-only snapshot of the auth state at module load time. */
data class AuthSnapshot(
    val accountId: String,
    val username: String,
    val minecraftUsername: String,
    val planTier: String,
    val entitledModules: Set<String>,
)
