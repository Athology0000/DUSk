package org.cobalt.internal.mining

import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import org.cobalt.api.event.EventBus
import org.cobalt.api.event.annotation.SubscribeEvent
import org.cobalt.api.event.impl.client.BlockChangeEvent
import java.net.URL

object MiningProfitTracker {

    @Volatile var sessionCoins = 0L
        private set
    @Volatile var sessionStartTime = 0L
        private set

    private val bazaarPrices = mutableMapOf<String, Double>()
    @Volatile private var lastBazaarRefresh = 0L
    private val BAZAAR_REFRESH_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes

    private val BLOCK_TYPE_TO_BAZAAR_KEY = mapOf(
        "Mithril (Gray)"      to "mithril ore",
        "Mithril (Dark)"      to "mithril ore",
        "Mithril (Hot)"       to "mithril ore",
        "Titanium"            to "titanium ore",
        "Ruby Gemstone"       to "flawed ruby gem",
        "Amber Gemstone"      to "flawed amber gem",
        "Amethyst Gemstone"   to "flawed amethyst gem",
        "Jade Gemstone"       to "flawed jade gem",
        "Sapphire Gemstone"   to "flawed sapphire gem",
        "Opal Gemstone"       to "flawed opal gem",
        "Topaz Gemstone"      to "flawed topaz gem",
        "Jasper Gemstone"     to "flawed jasper gem",
        "Onyx Gemstone"       to "flawed onyx gem",
        "Aquamarine Gemstone" to "flawed aquamarine gem",
        "Citrine Gemstone"    to "flawed citrine gem",
        "Peridot Gemstone"    to "flawed peridot gem",
        "Umber"               to "umber",
        "Tungsten"            to "tungsten",
        "Glacite"             to "glacite",
    )

    init {
        EventBus.register(this)
        refreshBazaarIfNeeded()
    }

    fun ensureInitialized() { /* touching this object causes the init block to run */ }

    fun getPriceForKey(key: String): Double? = bazaarPrices[key]

    fun resetSession() {
        sessionCoins = 0L
        sessionStartTime = System.currentTimeMillis()
        refreshBazaarIfNeeded()
    }

    fun runtimeMs(): Long = if (sessionStartTime == 0L) 0L
        else (System.currentTimeMillis() - sessionStartTime).coerceAtLeast(0L)

    fun coinsPerHour(): Long {
        val elapsedHours = runtimeMs() / 3_600_000.0
        if (elapsedHours < 0.001) return 0L
        return (sessionCoins / elapsedHours).toLong()
    }

    @SubscribeEvent
    fun onBlockChange(event: BlockChangeEvent) {
        if (!event.newBlock.isAir) return
        if (!MiningMacroModule.isActive) return
        val blockId = BuiltInRegistries.BLOCK.getKey(event.oldBlock.block).toString()
        val blockType = MiningBlockRegistry.BLOCK_ID_TO_TYPE[blockId] ?: return
        val bazaarKey = BLOCK_TYPE_TO_BAZAAR_KEY[blockType] ?: return
        val price = bazaarPrices[bazaarKey] ?: return
        if (price <= 0.0) return
        sessionCoins += price.toLong()
    }

    private fun refreshBazaarIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastBazaarRefresh < BAZAAR_REFRESH_INTERVAL_MS) return
        lastBazaarRefresh = now
        Thread({
            try {
                val json = URL("https://api.hypixel.net/v2/skyblock/bazaar").readText()
                val root = JsonParser.parseString(json).asJsonObject
                val prods = root.getAsJsonObject("products") ?: return@Thread
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
            } catch (_: Exception) { /* network failure — keep cached prices */ }
        }, "mining-bazaar-refresh").also { it.isDaemon = true }.start()
    }
}
