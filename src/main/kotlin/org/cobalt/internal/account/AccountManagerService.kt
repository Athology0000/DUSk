package org.cobalt.internal.account

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.UserApiService
import com.mojang.authlib.yggdrasil.ProfileResult
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.minecraft.client.Minecraft
import net.minecraft.client.User
import net.minecraft.client.multiplayer.ProfileKeyPairManager
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment
import net.minecraft.client.multiplayer.chat.report.ReportingContext
import net.minecraft.server.Services
import org.cobalt.api.notification.NotificationManager
import org.cobalt.mixin.client.MinecraftAccessor
import org.slf4j.LoggerFactory

internal object AccountManagerService {

  data class ManagedAccount(
    val alias: String,
    val refreshToken: String,
    val minecraftName: String = "",
    val minecraftUuid: String = "",
    val lastLoginAt: Long = 0L,
  )

  private data class AccountStore(
    val accounts: MutableList<ManagedAccount> = mutableListOf(),
  )

  private data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
  )

  private data class XboxToken(
    val token: String,
    val userHash: String,
  )

  private data class MinecraftSession(
    val accessToken: String,
    val refreshToken: String,
    val minecraftName: String,
    val minecraftUuid: String,
  )

  private val mc = Minecraft.getInstance()
  private val logger = LoggerFactory.getLogger("Cobalt/AccountManager")
  private val gson = GsonBuilder().setPrettyPrinting().create()
  private val httpClient = HttpClient.newBuilder()
    .followRedirects(HttpClient.Redirect.NORMAL)
    .connectTimeout(Duration.ofSeconds(15))
    .build()
  private val storeFile = File(mc.gameDirectory, "config/cobalt/account_manager.json")

  @Volatile private var busy = false
  @Volatile private var statusMessage = "Idle"
  private var store = AccountStore()

  init {
    load()
  }

  fun getAccounts(): List<ManagedAccount> = synchronized(this) {
    store.accounts.sortedBy { it.alias.lowercase(Locale.US) }.toList()
  }

  fun getStatusMessage(): String = statusMessage

  fun isBusy(): Boolean = busy

  fun getCurrentSessionName(): String = mc.user.name

  fun getCurrentSessionUuid(): String = mc.user.profileId.toString()

  fun isCurrentAccount(account: ManagedAccount): Boolean {
    val currentUuid = getCurrentSessionUuid()
    return account.minecraftUuid.equals(currentUuid, ignoreCase = true) ||
      account.minecraftName.equals(getCurrentSessionName(), ignoreCase = true)
  }

  fun login(alias: String) {
    val normalizedAlias = alias.trim()
    if (normalizedAlias.isEmpty()) {
      notifyUser("Account Login", "Enter an alias first")
      return
    }

    synchronized(this) {
      if (busy) {
        notifyUser("Account Login", "Another login is already running")
        return
      }
      busy = true
      statusMessage = "Logging into $normalizedAlias..."
    }

    thread(name = "Cobalt-AccountLogin", isDaemon = true) {
      try {
        logger.info("Account login requested for alias '{}'", normalizedAlias)
        val existing = synchronized(this@AccountManagerService) {
          store.accounts.firstOrNull { it.alias.equals(normalizedAlias, ignoreCase = true) }
        }

        val session = authenticate(normalizedAlias, existing)
        synchronized(this@AccountManagerService) {
          upsertAccount(
            ManagedAccount(
              alias = existing?.alias ?: normalizedAlias,
              refreshToken = session.refreshToken,
              minecraftName = session.minecraftName,
              minecraftUuid = session.minecraftUuid,
              lastLoginAt = System.currentTimeMillis(),
            )
          )
          saveLocked()
        }

        logger.info(
          "Account '{}' authenticated successfully as '{}' ({})",
          normalizedAlias,
          session.minecraftName,
          session.minecraftUuid,
        )
        statusMessage = "Switching session to ${session.minecraftName}..."

        mc.execute {
          try {
            applySession(session)
            logger.info(
              "Account '{}' session swap completed successfully for '{}'",
              normalizedAlias,
              session.minecraftName,
            )
            notifyUser("Account Login", "Now using ${session.minecraftName}")
            statusMessage = "Logged in as ${session.minecraftName}"
          } catch (exception: Exception) {
            logger.error("Account '{}' authenticated but session swap failed", normalizedAlias, exception)
            notifyUser(
              "Account Login Failed",
              exception.message?.take(96) ?: "Session swap failed",
            )
            statusMessage = "Session swap failed"
          } finally {
            busy = false
          }
        }
      } catch (exception: Exception) {
        logger.error("Account '{}' login failed", normalizedAlias, exception)
        notifyUser(
          "Account Login Failed",
          exception.message?.take(96) ?: "Login failed",
        )
        statusMessage = "Login failed"
        busy = false
      }
    }
  }

  fun remove(alias: String) {
    val normalizedAlias = alias.trim()
    if (normalizedAlias.isEmpty()) return

    synchronized(this) {
      if (busy) {
        notifyUser("Accounts", "Wait for the current login to finish")
        return
      }

      val removed = store.accounts.removeIf { it.alias.equals(normalizedAlias, ignoreCase = true) }
      if (!removed) return

      saveLocked()
      logger.info("Removed stored account alias '{}'", normalizedAlias)
    }

    notifyUser("Accounts", "Removed $normalizedAlias")
  }

  private fun authenticate(alias: String, existing: ManagedAccount?): MinecraftSession {
    val tokens =
      existing?.refreshToken
        ?.takeIf { it.isNotBlank() }
        ?.let { refreshToken ->
          runCatching {
            logger.info("Refreshing stored Microsoft session for alias '{}'", alias)
            refreshMicrosoftTokens(refreshToken)
          }.getOrElse { exception ->
            logger.warn("Stored refresh token for alias '{}' failed, falling back to browser login", alias, exception)
            startInteractiveMicrosoftLogin(alias)
          }
        }
        ?: startInteractiveMicrosoftLogin(alias)

    val xblToken = requestXboxLiveToken(tokens.accessToken)
    val xstsToken = requestXstsToken(xblToken.token)
    val minecraftAccessToken = requestMinecraftAccessToken(xstsToken)
    val profile = requestMinecraftProfile(minecraftAccessToken)

    return MinecraftSession(
      accessToken = minecraftAccessToken,
      refreshToken = tokens.refreshToken,
      minecraftName = profile.first,
      minecraftUuid = profile.second,
    )
  }

  private fun startInteractiveMicrosoftLogin(alias: String): OAuthTokens {
    val verifierBytes = ByteArray(32)
    java.security.SecureRandom().nextBytes(verifierBytes)
    val codeVerifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
    val codeChallenge = base64UrlSha256(codeVerifier)

    var server: HttpServer? = null
    try {
      val codeFuture = CompletableFuture<String>()
      server = HttpServer.create(InetSocketAddress("0.0.0.0", OAUTH_PORT), 0)
      server.createContext("/") { exchange ->
        val query = parseQuery(exchange.requestURI.rawQuery.orEmpty())
        val responseHtml = if (query.containsKey("error")) {
          codeFuture.completeExceptionally(
            IllegalStateException(query["error_description"] ?: query["error"] ?: "Microsoft login was denied"),
          )
          FAILURE_HTML
        } else {
          codeFuture.complete(query["code"] ?: "")
          SUCCESS_HTML
        }

        val bytes = responseHtml.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.start()

      val authUrl = buildMicrosoftAuthUrl(codeChallenge)
      logger.info("Starting interactive Microsoft login for alias '{}'", alias)
      logger.info("Microsoft OAuth URL for alias '{}': {}", alias, authUrl)
      statusMessage = "Complete the Microsoft login for $alias in your browser..."
      notifyUser("Microsoft Login", "Browser login started for $alias")

      mc.execute {
        mc.keyboardHandler.clipboard = authUrl
      }

      tryOpenBrowser(authUrl)
      val authCode = codeFuture.get(5, TimeUnit.MINUTES)
      if (authCode.isBlank()) {
        throw IllegalStateException("Microsoft login did not return an authorization code")
      }
      return exchangeAuthorizationCode(authCode, codeVerifier)
    } finally {
      server?.stop(0)
    }
  }

  private fun refreshMicrosoftTokens(refreshToken: String): OAuthTokens {
    val response = postForm(
      OAUTH_TOKEN_URL,
      linkedMapOf(
        "client_id" to MICROSOFT_CLIENT_ID,
        "scope" to MICROSOFT_SCOPES,
        "redirect_uri" to OAUTH_REDIRECT_URI,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
      ),
    )

    return OAuthTokens(
      accessToken = response.requiredString("access_token"),
      refreshToken = response.requiredString("refresh_token"),
    )
  }

  private fun exchangeAuthorizationCode(code: String, codeVerifier: String): OAuthTokens {
    val response = postForm(
      OAUTH_TOKEN_URL,
      linkedMapOf(
        "client_id" to MICROSOFT_CLIENT_ID,
        "scope" to MICROSOFT_SCOPES,
        "redirect_uri" to OAUTH_REDIRECT_URI,
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to codeVerifier,
      ),
    )

    return OAuthTokens(
      accessToken = response.requiredString("access_token"),
      refreshToken = response.requiredString("refresh_token"),
    )
  }

  private fun requestXboxLiveToken(oauthAccessToken: String): XboxToken {
    val response = postJson(
      XBL_URL,
      JsonObject().apply {
        add("Properties", JsonObject().apply {
          addProperty("AuthMethod", "RPS")
          addProperty("SiteName", "user.auth.xboxlive.com")
          addProperty("RpsTicket", "d=$oauthAccessToken")
        })
        addProperty("RelyingParty", "http://auth.xboxlive.com")
        addProperty("TokenType", "JWT")
      },
    )

    val userHash = response
      .getAsJsonObject("DisplayClaims")
      ?.getAsJsonArray("xui")
      ?.firstOrNull()
      ?.asJsonObject
      ?.requiredString("uhs")
      ?: throw IllegalStateException("Xbox Live response did not include a user hash")

    return XboxToken(
      token = response.requiredString("Token"),
      userHash = userHash,
    )
  }

  private fun requestXstsToken(xboxToken: String): XboxToken {
    val response = postJson(
      XSTS_URL,
      JsonObject().apply {
        add("Properties", JsonObject().apply {
          addProperty("SandboxId", "RETAIL")
          add("UserTokens", com.google.gson.JsonArray().apply {
            add(xboxToken)
          })
        })
        addProperty("RelyingParty", "rp://api.minecraftservices.com/")
        addProperty("TokenType", "JWT")
      },
    )

    val userHash = response
      .getAsJsonObject("DisplayClaims")
      ?.getAsJsonArray("xui")
      ?.firstOrNull()
      ?.asJsonObject
      ?.requiredString("uhs")
      ?: throw IllegalStateException("XSTS response did not include a user hash")

    return XboxToken(
      token = response.requiredString("Token"),
      userHash = userHash,
    )
  }

  private fun requestMinecraftAccessToken(xstsToken: XboxToken): String {
    val response = postJson(
      MINECRAFT_AUTH_URL,
      JsonObject().apply {
        addProperty("identityToken", "XBL3.0 x=${xstsToken.userHash};${xstsToken.token}")
      },
    )
    return response.requiredString("access_token")
  }

  private fun requestMinecraftProfile(minecraftAccessToken: String): Pair<String, String> {
    val response = getJson(
      MINECRAFT_PROFILE_URL,
      minecraftAccessToken,
    )
    return response.requiredString("name") to response.requiredString("id")
  }

  private fun applySession(session: MinecraftSession) {
    val minecraft = Minecraft.getInstance()
    val accessor = minecraft as MinecraftAccessor
    val profileId = parseUndashedUuid(session.minecraftUuid)
    val user = User(
      session.minecraftName,
      profileId,
      session.accessToken,
      Optional.empty(),
      Optional.empty(),
    )

    val authService = YggdrasilAuthenticationService(minecraft.proxy)
    val userApiService = runCatching {
      authService.createUserApiService(session.accessToken)
    }.getOrDefault(UserApiService.OFFLINE)
    val userProperties = runCatching {
      userApiService.fetchProperties()
    }.getOrDefault(UserApiService.OFFLINE_PROPERTIES)
    val services = Services.create(authService, minecraft.gameDirectory)
    val profileFuture = CompletableFuture.completedFuture(ProfileResult(GameProfile(profileId, session.minecraftName)))
    val userPropertiesFuture = CompletableFuture.completedFuture(userProperties)

    accessor.setUser(user)
    accessor.setProfileFuture(profileFuture)
    accessor.setUserApiService(userApiService)
    accessor.setUserPropertiesFuture(userPropertiesFuture)
    accessor.setProfileKeyPairManager(ProfileKeyPairManager.EMPTY_KEY_MANAGER)
    accessor.setServices(services)
    accessor.setReportingContext(ReportingContext.create(ReportEnvironment.local(), userApiService))

    minecraft.prepareForMultiplayer()
    if (minecraft.level != null) {
      minecraft.disconnectWithProgressScreen()
    }
  }

  private fun postForm(url: String, params: Map<String, String>): JsonObject {
    val formBody = params.entries.joinToString("&") { (key, value) ->
      "${urlEncode(key)}=${urlEncode(value)}"
    }

    val request = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .header("Accept", "application/json")
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofString(formBody))
      .build()

    return sendJson(request)
  }

  private fun postJson(url: String, body: JsonObject): JsonObject {
    val request = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .header("User-Agent", USER_AGENT)
      .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
      .build()

    return sendJson(request)
  }

  private fun getJson(url: String, bearerToken: String): JsonObject {
    val request = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Accept", "application/json")
      .header("User-Agent", USER_AGENT)
      .header("Authorization", "Bearer $bearerToken")
      .GET()
      .build()

    return sendJson(request)
  }

  private fun sendJson(request: HttpRequest): JsonObject {
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    val body = response.body().orEmpty()
    val parsed = if (body.isNotBlank()) runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() else null

    if (response.statusCode() !in 200..299) {
      throw IllegalStateException(parsed?.bestErrorMessage() ?: "HTTP ${response.statusCode()} from ${request.uri()}")
    }

    return parsed ?: throw IllegalStateException("Empty response from ${request.uri()}")
  }

  private fun load() {
    synchronized(this) {
      if (!storeFile.exists()) {
        storeFile.parentFile?.mkdirs()
        store = AccountStore()
        return
      }

      store = runCatching {
        gson.fromJson(storeFile.readText(StandardCharsets.UTF_8), AccountStore::class.java) ?: AccountStore()
      }.getOrElse { exception ->
        logger.error("Failed to load account store from {}", storeFile.absolutePath, exception)
        AccountStore()
      }
    }
  }

  private fun saveLocked() {
    storeFile.parentFile?.mkdirs()
    storeFile.writeText(gson.toJson(store), StandardCharsets.UTF_8)
  }

  private fun upsertAccount(account: ManagedAccount) {
    val existingIndex = store.accounts.indexOfFirst { it.alias.equals(account.alias, ignoreCase = true) }
    if (existingIndex >= 0) {
      store.accounts[existingIndex] = account
    } else {
      store.accounts.add(account)
    }
  }

  private fun buildMicrosoftAuthUrl(codeChallenge: String): String {
    val params = linkedMapOf(
      "client_id" to MICROSOFT_CLIENT_ID,
      "response_type" to "code",
      "redirect_uri" to OAUTH_REDIRECT_URI,
      "scope" to MICROSOFT_SCOPES,
      "prompt" to "select_account",
      "code_challenge" to codeChallenge,
      "code_challenge_method" to "S256",
    )
    return "$OAUTH_AUTHORIZE_URL?${params.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }}"
  }

  private fun tryOpenBrowser(url: String) {
    runCatching {
      if (Desktop.isDesktopSupported()) {
        Desktop.getDesktop().browse(URI.create(url))
      }
    }.onFailure { exception ->
      logger.warn("Failed to open the Microsoft login URL automatically", exception)
      notifyUser("Microsoft Login", "URL copied to clipboard")
    }
  }

  private fun notifyUser(title: String, description: String) {
    mc.execute {
      NotificationManager.queue(title, description)
    }
  }

  private fun parseQuery(rawQuery: String): Map<String, String> {
    if (rawQuery.isBlank()) return emptyMap()
    return rawQuery.split("&").mapNotNull { part ->
      val separatorIndex = part.indexOf('=')
      if (separatorIndex < 0) {
        null
      } else {
        val key = java.net.URLDecoder.decode(part.substring(0, separatorIndex), StandardCharsets.UTF_8)
        val value = java.net.URLDecoder.decode(part.substring(separatorIndex + 1), StandardCharsets.UTF_8)
        key to value
      }
    }.toMap()
  }

  private fun parseUndashedUuid(raw: String): UUID {
    val normalized = raw.trim()
    val dashed =
      if (normalized.contains('-')) normalized
      else normalized.replaceFirst(UUID_DASH_PATTERN, "$1-$2-$3-$4-$5")
    return UUID.fromString(dashed)
  }

  private fun base64UrlSha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
  }

  private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

  private fun JsonObject.requiredString(key: String): String =
    get(key)?.asString ?: throw IllegalStateException("Missing '$key' in response")

  private fun JsonObject.bestErrorMessage(): String {
    return listOf("error_description", "errorMessage", "message", "Message", "error")
      .mapNotNull { key -> get(key)?.asString }
      .firstOrNull()
      ?: toString()
  }

  private val UUID_DASH_PATTERN = Regex("([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})")

  private const val MICROSOFT_CLIENT_ID = "757bb3b3-b7ca-4bcd-a160-c92e6379c263"
  private const val MICROSOFT_SCOPES = "XboxLive.signin XboxLive.offline_access"
  private const val USER_AGENT = "Mozilla/5.0 (Cobalt Account Manager)"
  private const val OAUTH_PORT = 3000
  private const val OAUTH_REDIRECT_URI = "http://127.0.0.1:3000"
  private const val OAUTH_AUTHORIZE_URL = "https://login.live.com/oauth20_authorize.srf"
  private const val OAUTH_TOKEN_URL = "https://login.live.com/oauth20_token.srf"
  private const val XBL_URL = "https://user.auth.xboxlive.com/user/authenticate"
  private const val XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize"
  private const val MINECRAFT_AUTH_URL = "https://api.minecraftservices.com/authentication/login_with_xbox"
  private const val MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile"

  private const val SUCCESS_HTML =
    "<!doctype html><html><body style=\"font-family:sans-serif;padding:32px;background:#111;color:#f4f4f4;\">" +
      "<h2>Microsoft Login Complete</h2><p>You can return to Minecraft now.</p></body></html>"

  private const val FAILURE_HTML =
    "<!doctype html><html><body style=\"font-family:sans-serif;padding:32px;background:#111;color:#f4f4f4;\">" +
      "<h2>Microsoft Login Failed</h2><p>Return to Minecraft and check the log.</p></body></html>"
}
