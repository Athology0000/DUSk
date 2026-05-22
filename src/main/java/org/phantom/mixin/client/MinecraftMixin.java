package org.phantom.mixin.client;

import java.util.List;
import kotlin.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.phantom.api.addon.Addon;
import org.phantom.api.addon.AddonMetadata;
import org.phantom.api.event.impl.client.ScreenOpenEvent;
import org.phantom.api.event.impl.client.TickEvent;
import org.phantom.internal.loader.AddonLoader;
import org.phantom.internal.visual.PhantomStartupGate;
import org.phantom.internal.visual.PhantomWelcomeScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

  @Unique
  private boolean phantom$redirectingWelcome;

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

  @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
  private void phantom$showWelcomeBeforeTitle(Screen screen, CallbackInfo callbackInfo) {
    new ScreenOpenEvent(screen).post();

    if (
      phantom$redirectingWelcome ||
      screen == null ||
      PhantomStartupGate.INSTANCE.isUnlocked() ||
      screen instanceof PhantomWelcomeScreen ||
      !(screen instanceof TitleScreen)
    ) {
      return;
    }

    phantom$redirectingWelcome = true;
    try {
      ((Minecraft) (Object) this).setScreen(new PhantomWelcomeScreen());
    } finally {
      phantom$redirectingWelcome = false;
    }

    callbackInfo.cancel();
  }

}
