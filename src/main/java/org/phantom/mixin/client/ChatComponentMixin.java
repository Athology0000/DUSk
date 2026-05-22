package org.phantom.mixin.client;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.phantom.api.event.impl.client.ChatMessageEvent;
import org.phantom.api.util.ChatUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

  @Inject(
    method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
    at = @At("HEAD")
  )
  private void phantom$animatePhantomChatMessages(
    GuiGraphics graphics,
    Font font,
    int tick,
    int mouseX,
    int mouseY,
    boolean focused,
    boolean drawIndicator,
    CallbackInfo ci
  ) {
    ChatUtils.refreshAnimatedChat((ChatComponent) (Object) this);
  }

  @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("TAIL"))
  private void phantom$handleClientChatMessage(Component message, CallbackInfo ci) {
    phantom$forwardChatMessage(message);
  }

  @Inject(
    method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
    at = @At("TAIL")
  )
  private void phantom$handleTaggedClientChatMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
    phantom$forwardChatMessage(message);
  }

  private static void phantom$forwardChatMessage(Component message) {
    if (message == null) {
      return;
    }
    new ChatMessageEvent(message.getString()).post();
  }
}
