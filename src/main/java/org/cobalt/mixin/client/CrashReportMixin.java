package org.cobalt.mixin.client;

import java.util.stream.Collectors;
import net.minecraft.CrashReport;
import org.cobalt.internal.loader.AddonLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {

  @Inject(method = "getDetails(Ljava/lang/StringBuilder;)V", at = @At("HEAD"))
  private void addAddonInfo(StringBuilder crashReportBuilder, CallbackInfo callbackInfo) {
    String addons = AddonLoader.INSTANCE.getAddons().stream()
      .map(info -> info.getFirst().getName() + " v" + info.getFirst().getVersion())
      .collect(Collectors.joining(", "));

    if (addons.isEmpty()) {
      addons = "None";
    }

    crashReportBuilder
      .append("\n========================================")
      .append("\nDutt Client Addons (")
      .append(AddonLoader.INSTANCE.getAddons().size())
      .append("): ")
      .append(addons)
      .append("\n========================================\n");
  }

}
