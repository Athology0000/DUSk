package org.cobalt.internal.qol

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal object SkyblockPriceService {

  data class BazaarQuote(
    val buyPrice: Double?,
    val sellPrice: Double,
  )

  @Volatile private var lowestBinPrices: Map<String, Double> = emptyMap()
  @Volatile private var bazaarQuotes: Map<String, BazaarQuote> = emptyMap()
  @Volatile private var npcSellPrices: Map<String, Double> = emptyMap()

  @Volatile private var lastMarketRefreshMs = 0L
  @Volatile private var lastNpcRefreshMs = 0L

  private val refreshInFlight = AtomicBoolean(false)

  fun refreshIfNeeded(marketRefreshSeconds: Long) {
    val now = System.currentTimeMillis()
    val marketAge = now - lastMarketRefreshMs
    val npcAge = now - lastNpcRefreshMs
    val marketIntervalMs = marketRefreshSeconds.coerceAtLeast(30L) * 1000L
    val npcIntervalMs = NPC_REFRESH_INTERVAL_MS
    if (marketAge < marketIntervalMs && npcAge < npcIntervalMs) return
    if (!refreshInFlight.compareAndSet(false, true)) return

    Thread({
      try {
        val currentNow = System.currentTimeMillis()
        if (currentNow - lastMarketRefreshMs >= marketIntervalMs) {
          val refreshedLowestBins = refreshLowestBins()
          val refreshedBazaar = refreshBazaar()
          if (refreshedLowestBins || refreshedBazaar) {
            lastMarketRefreshMs = System.currentTimeMillis()
          }
        }
        if (currentNow - lastNpcRefreshMs >= npcIntervalMs) {
          if (refreshNpcSellPrices()) {
            lastNpcRefreshMs = System.currentTimeMillis()
          }
        }
      } finally {
        refreshInFlight.set(false)
      }
    }, "Cobalt-PriceRefresh").also { it.isDaemon = true; it.start() }
  }

  fun getLowestBin(apiId: String): Double? = lowestBinPrices[apiId]

  fun getBazaarQuote(apiId: String): BazaarQuote? = bazaarQuotes[apiId]

  fun getNpcSellPrice(internalId: String): Double? = npcSellPrices[internalId]

  fun hasBazaarQuote(apiId: String): Boolean = bazaarQuotes.containsKey(apiId)

  private fun refreshLowestBins(): Boolean {
    val root = fetchJsonObject("https://moulberry.codes/lowestbin.json") ?: return false
    val parsed = HashMap<String, Double>(root.size())
    for ((key, value) in root.entrySet()) {
      val price = value.asDouble
      if (price >= 0.0) parsed[key] = price
    }
    lowestBinPrices = parsed
    return true
  }

  private fun refreshBazaar(): Boolean {
    val root = fetchJsonObject("https://api.hypixel.net/v2/skyblock/bazaar") ?: return false
    val products = root.getAsJsonObject("products") ?: return false
    val parsed = HashMap<String, BazaarQuote>(products.size())
    for ((productId, productValue) in products.entrySet()) {
      val quickStatus = productValue.asJsonObject.getAsJsonObject("quick_status") ?: continue
      val buyPrice = quickStatus.get("buyPrice")?.asDouble
      val sellPrice = quickStatus.get("sellPrice")?.asDouble ?: continue
      parsed[productId] = BazaarQuote(buyPrice = buyPrice, sellPrice = sellPrice)
    }
    bazaarQuotes = parsed
    return true
  }

  private fun refreshNpcSellPrices(): Boolean {
    val root = fetchJsonObject("https://api.hypixel.net/v2/resources/skyblock/items") ?: return false
    val items = root.getAsJsonArray("items") ?: return false
    val parsed = HashMap<String, Double>(items.size())
    for (element in items) {
      val item = element.asJsonObject
      val id = item.get("id")?.asString ?: continue
      val npcSellPrice = item.get("npc_sell_price")?.asDouble ?: continue
      parsed[id] = npcSellPrice
    }
    npcSellPrices = parsed
    return true
  }

  private fun fetchJsonObject(url: String) =
    runCatching {
      val connection = URI(url).toURL().openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.setRequestProperty("User-Agent", "Cobalt")
      connection.connectTimeout = 6_000
      connection.readTimeout = 20_000
      connection.inputStream.bufferedReader().use { reader ->
        JsonParser.parseReader(reader).asJsonObject
      }
    }.getOrNull()

  private const val NPC_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
}
