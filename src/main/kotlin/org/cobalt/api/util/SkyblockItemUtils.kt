package org.cobalt.api.util

import com.google.gson.JsonParser
import java.util.Base64
import java.util.Locale
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.item.ItemStack

fun ItemStack.getSkyblockExtraAttributes(): CompoundTag? {
  return try {
    val customData = get(DataComponents.CUSTOM_DATA) ?: return null
    val root = unwrapOptional<CompoundTag>(customData.copyTag()) ?: return null
    when {
      root.contains("ExtraAttributes") -> unwrapOptional(root.getCompound("ExtraAttributes"))
      root.contains("extra_attributes") -> unwrapOptional(root.getCompound("extra_attributes"))
      root.contains("id") -> root
      else -> null
    }
  } catch (_: Exception) {
    null
  }
}

fun ItemStack.getSkyblockId(): String =
  getSkyblockExtraAttributes()?.tagString("id").orEmpty()

fun ItemStack.getHeadTextureId(): String? {
  val profile = get(DataComponents.PROFILE) ?: return null
  val textures = profile.partialProfile().properties()["textures"]
  val value = textures.firstOrNull()?.value() ?: return null
  val decoded = runCatching { String(Base64.getDecoder().decode(value)) }.getOrNull() ?: return null
  val urlKey = "\"url\""
  val keyIndex = decoded.indexOf(urlKey)
  if (keyIndex < 0) return null
  val colonIndex = decoded.indexOf(':', keyIndex)
  val startQuote = decoded.indexOf('"', colonIndex + 1) + 1
  if (startQuote <= 0) return null
  val endQuote = decoded.indexOf('"', startQuote)
  if (endQuote <= startQuote) return null
  return decoded.substring(startQuote, endQuote).substringAfterLast('/').ifBlank { null }
}

fun ItemStack.getSkyblockApiId(): String {
  val attributes = getSkyblockExtraAttributes() ?: return ""
  val id = attributes.tagString("id").orEmpty()
  if (id.isEmpty()) return ""

  if (attributes.contains("is_shiny")) {
    return "SHINY_$id"
  }

  return when (id) {
    "ENCHANTED_BOOK" -> {
      val enchants = attributes.tagCompound("enchantments")
      val enchant = enchants?.keySet()?.firstOrNull().orEmpty()
      val level = enchants?.tagInt(enchant) ?: 0
      if (enchant.isNotEmpty() && level > 0) {
        "ENCHANTMENT_${enchant.uppercase(Locale.ROOT)}_$level"
      } else {
        id
      }
    }

    "PET" -> {
      val rawPetInfo = attributes.tagString("petInfo").orEmpty()
      val petInfo = runCatching { JsonParser.parseString(rawPetInfo).asJsonObject }.getOrNull()
      val type = petInfo?.get("type")?.asString?.uppercase(Locale.ROOT).orEmpty()
      val tier = petInfo?.get("tier")?.asString?.uppercase(Locale.ROOT).orEmpty()
      if (type.isNotEmpty() && tier.isNotEmpty()) {
        "LVL_1_${tier}_$type"
      } else {
        id
      }
    }

    "POTION" -> {
      val potion = attributes.tagString("potion").orEmpty()
      val level = attributes.tagInt("potion_level") ?: 0
      if (potion.isNotEmpty() && level > 0) {
        buildString {
          append(potion)
          append("_POTION_")
          append(level)
          if (attributes.contains("enhanced")) append("_ENHANCED")
          if (attributes.contains("extended")) append("_EXTENDED")
          if (attributes.contains("splash")) append("_SPLASH")
        }.uppercase(Locale.ROOT)
      } else {
        id
      }
    }

    "RUNE" -> {
      val runes = attributes.tagCompound("runes")
      val rune = runes?.keySet()?.firstOrNull().orEmpty()
      val level = runes?.tagInt(rune) ?: 0
      if (rune.isNotEmpty() && level > 0) {
        "${rune.uppercase(Locale.ROOT)}_RUNE_$level"
      } else {
        id
      }
    }

    "NEW_YEAR_CAKE" -> {
      val cakeYear = attributes.tagInt("new_years_cake") ?: 0
      if (cakeYear > 0) "${id}_$cakeYear" else id
    }

    "PARTY_HAT_CRAB", "PARTY_HAT_CRAB_ANIMATED", "BALLOON_HAT_2024", "BALLOON_HAT_2025" -> {
      val color = attributes.tagString("party_hat_color")?.uppercase(Locale.ROOT).orEmpty()
      if (color.isNotEmpty()) "${id}_$color" else id
    }

    "PARTY_HAT_SLOTH" -> {
      val emoji = attributes.tagString("party_hat_emoji")?.uppercase(Locale.ROOT).orEmpty()
      if (emoji.isNotEmpty()) "${id}_$emoji" else id
    }

    "MIDAS_SWORD" -> if ((attributes.tagInt("winning_bid") ?: 0) >= 50_000_000) "${id}_50M" else id
    "MIDAS_STAFF" -> if ((attributes.tagInt("winning_bid") ?: 0) >= 100_000_000) "${id}_100M" else id
    else -> id
  }
}

fun CompoundTag.tagString(key: String): String? =
  runCatching { unwrapOptional<String>(getString(key)) }.getOrNull()

fun CompoundTag.tagInt(key: String): Int? =
  runCatching { unwrapOptional<Int>(getInt(key)) }.getOrNull()

fun CompoundTag.tagCompound(key: String): CompoundTag? =
  runCatching { if (contains(key)) unwrapOptional<CompoundTag>(getCompound(key)) else null }.getOrNull()

private fun <T> unwrapOptional(value: Any?): T? {
  if (value == null) return null
  @Suppress("UNCHECKED_CAST")
  return when (value) {
    is java.util.Optional<*> -> value.orElse(null) as T?
    is java.util.OptionalInt -> if (value.isPresent) value.orElse(0) as T else null
    is java.util.OptionalLong -> if (value.isPresent) value.orElse(0L) as T else null
    is java.util.OptionalDouble -> if (value.isPresent) value.orElse(0.0) as T else null
    else -> value as? T
  }
}
