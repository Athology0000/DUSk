package org.cobalt.mixin.render;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {

    @Inject(method = "renderListBackground", at = @At("HEAD"), cancellable = true)
    private void cobalt$hideServerListBackground(GuiGraphics guiGraphics, CallbackInfo ci) {
        if ((Object) this instanceof ServerSelectionList) {
            ci.cancel();
        }
    }

    @Inject(method = "renderListSeparators", at = @At("HEAD"), cancellable = true)
    private void cobalt$hideServerListSeparators(GuiGraphics guiGraphics, CallbackInfo ci) {
        if ((Object) this instanceof ServerSelectionList) {
            ci.cancel();
        }
    }
}
