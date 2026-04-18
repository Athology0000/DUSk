package org.cobalt.api.event.impl.client

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import org.cobalt.api.event.Event

@Suppress("UNUSED_PARAMETER")
class BlockChangeEvent(
  val pos: BlockPos,
  val oldBlock: BlockState,
  val newBlock: BlockState,
) : Event(false)
