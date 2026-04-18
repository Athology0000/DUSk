package org.cobalt.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.cobalt.api.event.impl.client.BlockChangeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
abstract class ClientPlayerInteractionManagerMixin {

  @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
  private void onBlockChange(BlockPos blockPos, BlockState newBlockState, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> callbackInfoReturnable) {
    if (Minecraft.getInstance().level != (Object) this) {
      return;
    }

    BlockState oldBlockState = ((Level) (Object) this).getBlockState(blockPos);

    if (oldBlockState.getBlock() != newBlockState.getBlock()) {
      new BlockChangeEvent(blockPos.immutable(), oldBlockState, newBlockState).post();
    }
  }

}
