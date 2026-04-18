package org.cobalt.api.event.impl.client

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket
import org.cobalt.api.event.Event

@Suppress("UNUSED_PARAMETER")
abstract class ChatEvent(val packet: Packet<*>) : Event(true) {

  class Receive(packet: Packet<*>) : ChatEvent(packet) {
    val message: String? = when (packet) {
      is ClientboundSystemChatPacket -> packet.content().string
      else -> null
    }
  }

  class Send(packet: Packet<*>) : ChatEvent(packet) {
    val message: String? = when (packet) {
      is ServerboundChatPacket -> packet.message
      else -> null
    }
  }

}
