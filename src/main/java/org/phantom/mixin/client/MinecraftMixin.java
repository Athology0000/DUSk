package org.phantom.mixin.client;

import java.util.List;
import kotlin.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.phantom.api.addon.Addon;
import org.phantom.api.addon.AddonMetadata;
import org.phantom.api.event.impl.client.ScreenOpenEvent;
import org.phantom.api.event.impl.client.TickEvent;
import org.phantom.internal.loader.AddonLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

  @Inject(at = @At("HEAD"), method = "tick")
  private void onStartTick(CallbackInfo callbackInfo) {
    TickEvent.Start startTickEvent = new TickEvent.Start();
    startTickEvent.post();
  }

  @Inject(at = @At("RETURN"), method = "tick")
  private void onEndTick(CallbackInfo callbackInfo) {
    TickEvent.End endTickEvent = new TickEvent.End();
    endTickEvent.post();
  }

  @Inject(method = "close", at = @At("HEAD"))
  public void onClose(CallbackInfo callbackInfo) {
    List<Pair<AddonMetadata, Addon>> addonsList = AddonLoader.INSTANCE.getAddons();

    addonsList.forEach((addon) -> {
      addon.getSecond().onUnload();
    });
  }

  // Authentication is headless: the loader binds on HWID + Minecraft username
  // + IP via the signed session token. No alias / welcome screen is shown.
  @Inject(method = "setScreen", at = @At("HEAD"))
  private void phantom$onSetScreen(Screen screen, CallbackInfo callbackInfo) {
    new ScreenOpenEvent(screen).post();
  }

}
