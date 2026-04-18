package org.cobalt.internal.mining

object MiningBlockRegistry {

  val BLOCK_HARDNESS = linkedMapOf(
    "Custom" to null,
    "Pure Coal" to 600.0,
    "Pure Iron" to 600.0,
    "Pure Gold" to 600.0,
    "Pure Lapis" to 600.0,
    "Pure Redstone" to 600.0,
    "Pure Emerald" to 600.0,
    "Pure Diamond" to 600.0,
    "Pure Quartz" to 600.0,
    "Mithril (Gray)" to 500.0,
    "Mithril (Dark)" to 800.0,
    "Mithril (Hot)" to 1500.0,
    "Titanium" to 2000.0,
    "Ruby Gemstone" to 2300.0,
    "Amber Gemstone" to 3000.0,
    "Amethyst Gemstone" to 3000.0,
    "Jade Gemstone" to 3000.0,
    "Sapphire Gemstone" to 3000.0,
    "Opal Gemstone" to 3000.0,
    "Topaz Gemstone" to 3800.0,
    "Jasper Gemstone" to 4800.0,
    "Onyx Gemstone" to 5200.0,
    "Aquamarine Gemstone" to 5200.0,
    "Citrine Gemstone" to 5200.0,
    "Peridot Gemstone" to 5200.0,
    "Umber" to 5600.0,
    "Tungsten" to 5600.0,
    "Glacite" to 6000.0,
    "Sulphur" to 500.0,
  )

  val BLOCK_TYPES: Array<String> = BLOCK_HARDNESS.keys.toTypedArray()

  val BLOCK_ID_TO_TYPE = mapOf(
    "minecraft:coal_ore" to "Pure Coal",
    "minecraft:deepslate_coal_ore" to "Pure Coal",
    "minecraft:coal_block" to "Pure Coal",
    "minecraft:iron_ore" to "Pure Iron",
    "minecraft:deepslate_iron_ore" to "Pure Iron",
    "minecraft:iron_block" to "Pure Iron",
    "minecraft:gold_ore" to "Pure Gold",
    "minecraft:deepslate_gold_ore" to "Pure Gold",
    "minecraft:gold_block" to "Pure Gold",
    "minecraft:lapis_ore" to "Pure Lapis",
    "minecraft:deepslate_lapis_ore" to "Pure Lapis",
    "minecraft:lapis_block" to "Pure Lapis",
    "minecraft:redstone_ore" to "Pure Redstone",
    "minecraft:deepslate_redstone_ore" to "Pure Redstone",
    "minecraft:redstone_block" to "Pure Redstone",
    "minecraft:emerald_ore" to "Pure Emerald",
    "minecraft:deepslate_emerald_ore" to "Pure Emerald",
    "minecraft:emerald_block" to "Pure Emerald",
    "minecraft:diamond_ore" to "Pure Diamond",
    "minecraft:deepslate_diamond_ore" to "Pure Diamond",
    "minecraft:diamond_block" to "Pure Diamond",
    "minecraft:nether_quartz_ore" to "Pure Quartz",
    "minecraft:quartz_block" to "Pure Quartz",
    "minecraft:gray_wool" to "Mithril (Gray)",
    "minecraft:cyan_terracotta" to "Mithril (Gray)",
    "minecraft:prismarine" to "Mithril (Dark)",
    "minecraft:prismarine_bricks" to "Mithril (Dark)",
    "minecraft:dark_prismarine" to "Mithril (Dark)",
    "minecraft:light_blue_wool" to "Mithril (Hot)",
    "minecraft:polished_diorite" to "Titanium",
    "minecraft:red_stained_glass" to "Ruby Gemstone",
    "minecraft:orange_stained_glass" to "Amber Gemstone",
    "minecraft:purple_stained_glass" to "Amethyst Gemstone",
    "minecraft:green_stained_glass" to "Jade Gemstone",
    "minecraft:light_blue_stained_glass" to "Sapphire Gemstone",
    "minecraft:white_stained_glass" to "Opal Gemstone",
    "minecraft:yellow_stained_glass" to "Topaz Gemstone",
    "minecraft:pink_stained_glass" to "Jasper Gemstone",
    "minecraft:black_stained_glass" to "Onyx Gemstone",
    "minecraft:cyan_stained_glass" to "Aquamarine Gemstone",
    "minecraft:lime_stained_glass" to "Peridot Gemstone",
    "minecraft:light_gray_stained_glass" to "Citrine Gemstone",
    "minecraft:packed_ice" to "Glacite",
    "minecraft:blue_ice" to "Glacite",
    "minecraft:brown_wool" to "Umber",
    "minecraft:yellow_wool" to "Sulphur",
    "minecraft:light_gray_terracotta" to "Tungsten",
  )

  val TYPE_TO_BLOCK_IDS: Map<String, Set<String>> =
    BLOCK_ID_TO_TYPE.entries.groupBy({ it.value }, { it.key }).mapValues { it.value.toSet() }

  private val LEGACY_TYPE_ALIASES = mapOf(
    "Mithril (Prismarine)" to "Mithril (Dark)",
    "Mithril (Blue Wool)" to "Mithril (Hot)",
  )

  private val BLACKLISTED_BLOCK_IDS = setOf(
    "minecraft:stone",
    "minecraft:light_gray_wool",  // hardstone background in tunnels/mineshaft — not titanium
  )

  fun normalizeType(type: String): String = LEGACY_TYPE_ALIASES[type] ?: type

  fun idsForType(type: String): Set<String> {
    val normalizedType = normalizeType(type)
    return TYPE_TO_BLOCK_IDS[normalizedType].orEmpty().filterNotTo(linkedSetOf()) { it in BLACKLISTED_BLOCK_IDS }
  }

  fun isBlacklisted(id: String): Boolean = id in BLACKLISTED_BLOCK_IDS
}
