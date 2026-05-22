package org.phantom.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.phantom.api.event.impl.client.FishingBobberFixEvent;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FishingHook.class)
public abstract class FishingHookMixin extends Projectile {

  protected FishingHookMixin(EntityType<? extends Projectile> entityType, Level level) {
    super(entityType, level);
  }

  @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;is(Lnet/minecraft/tags/TagKey;)Z"))
  private boolean phantom$allowLavaBobber(FluidState instance, TagKey<Fluid> tag, Operation<Boolean> original) {
    return original.call(instance, tag) || (phantom$bobberFixEnabled() && instance.is(FluidTags.LAVA));
  }

  @WrapOperation(method = "onSyncedDataUpdated", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getEntity(I)Lnet/minecraft/world/entity/Entity;"))
  private Entity phantom$ignoreFalseArmorStandHook(Level instance, int id, Operation<Entity> original) {
    Entity entity = original.call(instance, id);
    return shouldBlockHook(entity) ? null : entity;
  }

  @Unique
  private boolean phantom$bobberFixEnabled() {
    return new FishingBobberFixEvent().post();
  }

  @Unique
  private boolean shouldBlockHook(@Nullable Entity entity) {
    if (entity == null || !phantom$bobberFixEnabled()) {
      return false;
    }
    return (entity instanceof ArmorStand armorStand) && armorStand.getId() == getId() + 1;
  }
}
