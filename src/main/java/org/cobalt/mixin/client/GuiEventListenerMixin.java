package org.cobalt.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.inventory.Slot;
import org.cobalt.internal.qol.ItemLockingModule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiEventListener.class)
public interface GuiEventListenerMixin {

  @Inject(method = "keyReleased", at = @At("HEAD"), cancellable = true)
  private void cobalt$handleItemLockKeyReleases(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
    Object listener = this;
    if (!(listener instanceof AbstractContainerScreen<?> containerScreen)) {
      return;
    }

    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.player == null) {
      return;
    }

    Slot hoveredSlot = ((AbstractContainerScreenAccessor) containerScreen).getHoveredSlot();
    if (ItemLockingModule.INSTANCE.handleContainerKeyReleased(hoveredSlot, input)) {
      cir.setReturnValue(true);
    }
  }
}
