package org.cobalt.internal.combat

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.cobalt.api.module.setting.Setting
import org.cobalt.api.module.setting.impl.CheckboxSetting
import org.cobalt.api.module.setting.impl.ModeSetting
import org.cobalt.api.module.setting.impl.TextSetting

internal class SlayerLoadoutSetting(
  val loadoutSlayerType: ModeSetting,
  private val spiderAutoDetectSword: CheckboxSetting,
  private val spiderAutoDetectBow: CheckboxSetting,
  private val spiderMeleeWeapon: TextSetting,
  private val spiderBowWeapon: TextSetting,
  private val powerOrbChoice: ModeSetting,
  val autoOverflux: CheckboxSetting,
  val autoWandOfAtonement: CheckboxSetting,
  val autoRagnarok: CheckboxSetting,
  val autoSwordOfBadHealth: CheckboxSetting,
  val autoZombieSword: CheckboxSetting,
  val autoHyperion: CheckboxSetting,
) : Setting<String>(
  "Loadout Builder",
  "Visual slayer loadout editor for common items and hotbar autodetect picks.",
  ""
) {

  override val defaultValue: String = ""

  override fun read(element: JsonElement) {
    // The visual builder writes into the backing settings directly.
  }

  override fun write(): JsonElement = JsonPrimitive("")

  fun selectedLoadoutType(): Int {
    val safeIndex = loadoutSlayerType.value.coerceIn(0, loadoutSlayerType.options.lastIndex)
    if (safeIndex != loadoutSlayerType.value) {
      loadoutSlayerType.value = safeIndex
    }
    return safeIndex
  }

  fun selectedSpiderSword(): SpiderSwordCard {
    if (spiderAutoDetectSword.value) return SpiderSwordCard.AUTODETECT
    val configured = normalize(spiderMeleeWeapon.value)
    return when {
      configured.contains("scorpion foil") -> SpiderSwordCard.SCORPION_FOIL
      configured.contains("tarantula fang") -> SpiderSwordCard.TARANTULA_FANG
      else -> SpiderSwordCard.THE_STING
    }
  }

  fun selectSpiderSword(card: SpiderSwordCard) {
    when (card) {
      SpiderSwordCard.AUTODETECT -> {
        spiderAutoDetectSword.value = true
      }
      SpiderSwordCard.SCORPION_FOIL -> {
        spiderAutoDetectSword.value = false
        spiderMeleeWeapon.value = "scorpion foil"
      }
      SpiderSwordCard.THE_STING -> {
        spiderAutoDetectSword.value = false
        spiderMeleeWeapon.value = "the sting, sting"
      }
      SpiderSwordCard.TARANTULA_FANG -> {
        spiderAutoDetectSword.value = false
        spiderMeleeWeapon.value = "tarantula fang"
      }
    }
  }

  fun selectedSpiderBow(): SpiderBowCard {
    if (spiderAutoDetectBow.value) return SpiderBowCard.AUTODETECT
    val configured = normalize(spiderBowWeapon.value)
    return when {
      configured.contains("spider shortbow") || configured.contains("spider bow") -> SpiderBowCard.SPIDER_SHORTBOW
      else -> SpiderBowCard.MOSQUITO_SHORTBOW
    }
  }

  fun selectSpiderBow(card: SpiderBowCard) {
    when (card) {
      SpiderBowCard.AUTODETECT -> {
        spiderAutoDetectBow.value = true
      }
      SpiderBowCard.MOSQUITO_SHORTBOW -> {
        spiderAutoDetectBow.value = false
        spiderBowWeapon.value = "mosquito, shortbow"
      }
      SpiderBowCard.SPIDER_SHORTBOW -> {
        spiderAutoDetectBow.value = false
        spiderBowWeapon.value = "spider shortbow, spider bow, shortbow"
      }
    }
  }

  fun selectedPowerOrb(): PowerOrbCard? {
    if (!autoOverflux.value) return null
    return PowerOrbCard.entries.getOrElse(powerOrbChoice.value.coerceIn(0, PowerOrbCard.entries.lastIndex)) {
      PowerOrbCard.OVERFLUX
    }
  }

  fun togglePowerOrb(card: PowerOrbCard) {
    val current = selectedPowerOrb()
    if (current == card && autoOverflux.value) {
      autoOverflux.value = false
      return
    }
    autoOverflux.value = true
    powerOrbChoice.value = card.ordinal
  }

  fun utilityEnabled(card: UtilityCard): Boolean {
    return when (card) {
      UtilityCard.WAND_OF_ATONEMENT -> autoWandOfAtonement.value
      UtilityCard.RAGNAROK -> autoRagnarok.value
      UtilityCard.SWORD_OF_BAD_HEALTH -> autoSwordOfBadHealth.value
      UtilityCard.ZOMBIE_SWORD -> autoZombieSword.value
      UtilityCard.HYPERION -> autoHyperion.value
      UtilityCard.WITHER_CLOAK -> false
    }
  }

  fun toggleUtility(card: UtilityCard) {
    when (card) {
      UtilityCard.WAND_OF_ATONEMENT -> autoWandOfAtonement.value = !autoWandOfAtonement.value
      UtilityCard.RAGNAROK -> autoRagnarok.value = !autoRagnarok.value
      UtilityCard.SWORD_OF_BAD_HEALTH -> autoSwordOfBadHealth.value = !autoSwordOfBadHealth.value
      UtilityCard.ZOMBIE_SWORD -> autoZombieSword.value = !autoZombieSword.value
      UtilityCard.HYPERION -> autoHyperion.value = !autoHyperion.value
      UtilityCard.WITHER_CLOAK -> Unit
    }
  }

  companion object {
    internal fun normalize(raw: String): String =
      raw.lowercase().replace(Regex("\\u00A7."), "").replace(Regex("\\s+"), " ").trim()
  }
}

internal enum class SpiderSwordCard(
  val label: String,
  val imagePath: String,
  val hotbarKeywords: Array<String>,
) {
  SCORPION_FOIL(
    "Scorpion Foil",
    "/assets/cobalt/textures/loadout/spider/scorpion_foil.png",
    arrayOf("scorpion foil")
  ),
  THE_STING(
    "The Sting",
    "/assets/cobalt/textures/loadout/spider/the_sting.png",
    arrayOf("the sting", "sting")
  ),
  TARANTULA_FANG(
    "Tarantula Fang",
    "/assets/cobalt/textures/loadout/spider/tarantula_fang.png",
    arrayOf("tarantula fang")
  ),
  AUTODETECT(
    "Autodetect Sword",
    "/assets/cobalt/textures/loadout/common/autodetect_sword.png",
    arrayOf("sword", "blade", "katana", "rapier", "falchion", "dagger", "axe")
  ),
}

internal enum class SpiderBowCard(
  val label: String,
  val imagePath: String,
  val hotbarKeywords: Array<String>,
) {
  MOSQUITO_SHORTBOW(
    "Mosquito Shortbow",
    "/assets/cobalt/textures/loadout/spider/mosquito_shortbow.png",
    arrayOf("mosquito")
  ),
  SPIDER_SHORTBOW(
    "Spider Shortbow",
    "/assets/cobalt/textures/loadout/spider/spider_shortbow.png",
    arrayOf("spider shortbow", "spider bow")
  ),
  AUTODETECT(
    "Autodetect Bow",
    "/assets/cobalt/textures/loadout/common/autodetect_bow.png",
    arrayOf("terminator", "juju", "shortbow", "bow")
  ),
}

internal enum class PowerOrbCard(
  val label: String,
  val imagePath: String,
  val hotbarKeywords: Array<String>,
) {
  OVERFLUX(
    "Overflux Power Orb",
    "/assets/cobalt/textures/loadout/common/overflux_power_orb.png",
    arrayOf("overflux")
  ),
  PLASMAFLUX(
    "Plasmaflux Power Orb",
    "/assets/cobalt/textures/loadout/common/plasmaflux_power_orb.png",
    arrayOf("plasmaflux")
  ),
  SOS_FLARE(
    "SOS Flare",
    "/assets/cobalt/textures/loadout/common/sos_flare.png",
    arrayOf("sos flare", "flare")
  ),
  MANA_FLUX(
    "Mana Flux Power Orb",
    "/assets/cobalt/textures/loadout/common/manaflux_power_orb.png",
    arrayOf("mana flux", "manaflux")
  ),
}

internal enum class UtilityCard(
  val label: String,
  val imagePath: String,
  val hotbarKeywords: Array<String>,
  val interactive: Boolean = true,
) {
  WAND_OF_ATONEMENT(
    "Wand Of Atonement",
    "/assets/cobalt/textures/loadout/common/wand_of_atonement.png",
    arrayOf("wand of atonement")
  ),
  RAGNAROK(
    "Ragnarok",
    "/assets/cobalt/textures/loadout/common/ragnarok.png",
    arrayOf("ragnarok", "ragnorak")
  ),
  SWORD_OF_BAD_HEALTH(
    "Sword Of Bad Health",
    "/assets/cobalt/textures/loadout/common/sword_of_bad_health.png",
    arrayOf("sword of bad health")
  ),
  WITHER_CLOAK(
    "Wither Cloak",
    "/assets/cobalt/textures/loadout/common/wither_cloak.png",
    arrayOf("wither cloak", "withercloak"),
    interactive = false
  ),
  ZOMBIE_SWORD(
    "Zombie Sword",
    "/assets/cobalt/textures/loadout/common/zombie_sword.png",
    arrayOf("zombie sword")
  ),
  HYPERION(
    "Hyperion",
    "/assets/cobalt/textures/loadout/common/hyperion.png",
    arrayOf("hyperion", "astraea", "scylla", "valkyrie", "necron blade")
  ),
}
