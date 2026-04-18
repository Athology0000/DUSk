package org.cobalt.mixin.render;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.cobalt.api.event.impl.render.GuiRenderEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

  @Inject(method = "render", at = @At("TAIL"))
  private void cobaltRenderGui(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
    org.cobalt.api.event.impl.render.GuiRenderContext.set(graphics, delta);
    new GuiRenderEvent(graphics, delta).post();
  }
}
