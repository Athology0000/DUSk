package org.cobalt.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.cobalt.internal.wardrobe.WardrobeModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class WardrobeScreenMixin<T extends AbstractContainerMenu> {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void cobalt$suppressWardrobeRender(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (WardrobeModule.INSTANCE.shouldSuppressVanillaRender()) {
            ci.cancel();
        }
    }
}
