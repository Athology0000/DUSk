package org.cobalt.mixin.client;

import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.ProfileResult;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import net.minecraft.server.Services;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface MinecraftAccessor {

  @Invoker("startAttack")
  boolean leftClick();

  @Invoker("startUseItem")
  void rightClick();

  @Mutable
  @Accessor("user")
  void setUser(User user);

  @Mutable
  @Accessor("profileFuture")
  void setProfileFuture(CompletableFuture<ProfileResult> profileFuture);

  @Mutable
  @Accessor("userApiService")
  void setUserApiService(UserApiService userApiService);

  @Mutable
  @Accessor("userPropertiesFuture")
  void setUserPropertiesFuture(CompletableFuture<UserApiService.UserProperties> userPropertiesFuture);

  @Mutable
  @Accessor("profileKeyPairManager")
  void setProfileKeyPairManager(ProfileKeyPairManager profileKeyPairManager);

  @Mutable
  @Accessor("services")
  void setServices(Services services);

  @Accessor("reportingContext")
  void setReportingContext(ReportingContext reportingContext);

}
