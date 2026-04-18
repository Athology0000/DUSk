package org.cobalt.mixin.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.client.gui.screens.multiplayer.ServerSelectionList$OnlineServerEntry")
public abstract class ServerSelectionEntryMixin {

    @Redirect(
        method = "renderContent",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"
        )
    )
    private void cobalt$skipServerActionOverlay(
        GuiGraphics guiGraphics,
        RenderPipeline pipeline,
        Identifier sprite,
        int x,
        int y,
        int width,
        int height
    ) {
        if (sprite != null) {
            String path = sprite.getPath();
            if (
                path.startsWith("server_list/join") ||
                path.startsWith("server_list/move_up") ||
                path.startsWith("server_list/move_down")
            ) {
                return;
            }
        }

        guiGraphics.blitSprite(pipeline, sprite, x, y, width, height);
    }
}
