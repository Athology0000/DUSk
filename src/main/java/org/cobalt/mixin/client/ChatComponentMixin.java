package org.cobalt.mixin.client;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.cobalt.internal.garden.managers.PestCleaningSequencer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

  @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("TAIL"))
  private void cobalt$handleClientChatMessage(Component message, CallbackInfo ci) {
    forwardToGarden(message);
  }

  @Inject(
    method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
    at = @At("TAIL")
  )
  private void cobalt$handleTaggedClientChatMessage(Component message, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
    forwardToGarden(message);
  }

  private static void forwardToGarden(Component message) {
    if (message == null) {
      return;
    }
    PestCleaningSequencer.INSTANCE.onChatMessage(message.getString());
  }
}
