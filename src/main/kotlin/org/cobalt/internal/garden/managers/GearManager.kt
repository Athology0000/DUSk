package org.cobalt.internal.garden.managers

import org.cobalt.internal.garden.GardenConfig

object GearManager {

    @Volatile var isSwapping = false

    fun reset() { isSwapping = false }

    fun swapForPest() {
        isSwapping = true
        try {
            if (GardenConfig.autoWardrobeEnabled && GardenConfig.autoWardrobePest)
                WardrobeManager.swapTo(WardrobeManager.LoadoutType.PEST)
            if (GardenConfig.autoEquipment && GardenConfig.pestEquipment.isNotBlank())
                EquipmentManager.swapTo(GardenConfig.pestEquipment)
        } finally { isSwapping = false }
    }

    fun swapForFarming() {
        isSwapping = true
        try {
            if (GardenConfig.autoWardrobeEnabled)
                WardrobeManager.swapTo(WardrobeManager.LoadoutType.FARMING)
            if (GardenConfig.autoEquipment && GardenConfig.farmingEquipment.isNotBlank())
                EquipmentManager.swapTo(GardenConfig.farmingEquipment)
        } finally { isSwapping = false }
    }

    fun swapForVisitor() {
        isSwapping = true
        try {
            if (GardenConfig.autoWardrobeEnabled && GardenConfig.autoWardrobeVisitor)
                WardrobeManager.swapTo(WardrobeManager.LoadoutType.VISITOR)
            if (GardenConfig.autoEquipment && GardenConfig.visitorEquipment.isNotBlank())
                EquipmentManager.swapTo(GardenConfig.visitorEquipment)
        } finally { isSwapping = false }
    }
}
