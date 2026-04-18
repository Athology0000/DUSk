package org.cobalt.mixin.network;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import org.cobalt.api.event.impl.client.ChatEvent;
import org.cobalt.api.event.impl.client.PacketEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {

  @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
  private static void onPacketReceived(Packet<?> packet, PacketListener listener, CallbackInfo callbackInfo) {
    PacketEvent.Incoming incomingPacketEvent = new PacketEvent.Incoming(packet);

    if (incomingPacketEvent.post()) {
      callbackInfo.cancel();
      return;
    }

    if (packet instanceof ClientboundSystemChatPacket) {
      new ChatEvent.Receive(packet).post();
    }
  }

  @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
  private void onPacketSent(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo callbackInfo) {
    PacketEvent.Outgoing outgoingPacketEvent = new PacketEvent.Outgoing(packet);

    if (outgoingPacketEvent.post()) {
      callbackInfo.cancel();
      return;
    }

    if (packet instanceof ServerboundChatPacket) {
      new ChatEvent.Send(packet).post();
    }
  }

}
