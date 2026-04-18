package org.cobalt.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.cobalt.internal.visual.HotbarOverlayModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class HotbarMixin {

    @Inject(method = "renderItemHotbar", at = @At("HEAD"), cancellable = true)
    private void cancelVanillaHotbar(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
        if (HotbarOverlayModule.INSTANCE.isEnabled()) {
            ci.cancel();
        }
    }
}
