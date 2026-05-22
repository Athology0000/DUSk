package org.phantom.mixin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.phantom.api.event.impl.render.ContainerDrawEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class WardrobeScreenMixin<T extends AbstractContainerMenu> {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void phantom$suppressWardrobeRender(
            GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        ContainerDrawEvent event = new ContainerDrawEvent(
            (AbstractContainerScreen<?>) (Object) this, graphics, mouseX, mouseY, partialTick);
        if (event.post()) {
            ci.cancel();
        }
    }
}
