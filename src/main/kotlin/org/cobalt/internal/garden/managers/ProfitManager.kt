package org.cobalt.internal.garden.managers

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import org.cobalt.internal.garden.GardenConfig
import org.cobalt.internal.garden.GardenWorkerThread
import java.net.URL

object ProfitManager {

    @Volatile var sessionProfit  = 0L
    @Volatile var dailyProfit    = 0L
    @Volatile var lifetimeProfit = 0L
    @Volatile var lastBazaarRefresh = 0L
    private val bazaarPrices = mutableMapOf<String, Double>()

    private val hardcodedPrices = mapOf(
        "enchanted carrot"     to 1_000.0,
        "enchanted potato"     to 1_200.0,
        "enchanted wheat"      to 800.0,
        "enchanted sugar cane" to 900.0,
        "enchanted cactus"     to 1_500.0,
    )

    fun reset() { sessionProfit = 0L }

    fun fullReset() {
        sessionProfit  = 0L
        dailyProfit    = 0L
        lifetimeProfit = 0L
    }

    fun addProfit(amount: Long) {
        sessionProfit  += amount
        dailyProfit    += amount
        lifetimeProfit += amount
    }

    fun onChatMessage(message: String) {
        val m = Regex("""\+\s*(\d+)x?\s+(.+)""").find(message) ?: return
        val count = m.groupValues[1].toLongOrNull() ?: return
        val item  = m.groupValues[2].trim().lowercase()
        val price = bazaarPrices[item] ?: hardcodedPrices[item] ?: return
        addProfit((count * price).toLong())
    }

    fun refreshBazaarIfNeeded() {
        val interval = GardenConfig.bazaarRefreshSecs * 1000L
        if (System.currentTimeMillis() - lastBazaarRefresh < interval) return
        lastBazaarRefresh = System.currentTimeMillis()
        GardenWorkerThread.submit("bazaar-refresh") {
            try {
                val json   = URL("https://api.hypixel.net/v2/skyblock/bazaar").readText()
                val root   = JsonParser.parseString(json).asJsonObject
                val prods  = root.getAsJsonObject("products") ?: return@submit
                val prices = mutableMapOf<String, Double>()
                for ((name, data) in prods.entrySet()) {
                    val buy = data.asJsonObject
                        .getAsJsonObject("quick_status")
                        ?.get("buyPrice")?.asDouble ?: continue
                    prices[name.lowercase().replace("_", " ")] = buy
                }
                Minecraft.getInstance().execute {
                    bazaarPrices.clear()
                    bazaarPrices.putAll(prices)
                }
            } catch (_: Exception) { /* network failure - keep cached prices */ }
        }
    }
}
