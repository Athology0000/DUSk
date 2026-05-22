package org.phantom.mixin.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import org.phantom.api.event.impl.client.AutoStashButtonEvent;
import org.phantom.api.event.impl.client.AutoStashToggleEvent;
import org.phantom.api.event.impl.client.ContainerMouseEvent;
import org.phantom.api.event.impl.client.ContainerSlotClickEvent;
import org.phantom.api.event.impl.client.ScreenKeyEvent;
import org.phantom.api.event.impl.render.ContainerSlotRenderEvent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin<T extends AbstractContainerMenu> extends Screen {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow protected int imageWidth;
    @Shadow @Final protected T menu;
    @Shadow protected Slot hoveredSlot;

    @Unique
    private Button phantom$autoStashButton;

    protected AbstractContainerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void phantom$addAutoStashButton(CallbackInfo ci) {
        AutoStashButtonEvent query = new AutoStashButtonEvent((AbstractContainerScreen<?>) (Object) this);
        query.post();
        if (!query.getShow()) {
            phantom$autoStashButton = null;
            return;
        }

        int buttonWidth = 92;
        int buttonHeight = 20;
        int x = this.leftPos + this.imageWidth - buttonWidth;
        int y = Math.max(4, this.topPos - buttonHeight - 4);

        phantom$autoStashButton = this.addRenderableWidget(
            Button.builder(Component.literal(query.getLabel()), btn -> {
                new AutoStashToggleEvent().post();
                AutoStashButtonEvent refreshed = new AutoStashButtonEvent((AbstractContainerScreen<?>) (Object) this);
                refreshed.post();
                btn.setMessage(Component.literal(refreshed.getLabel()));
            }).bounds(x, y, buttonWidth, buttonHeight).build()
        );
    }

    @Inject(method = "containerTick", at = @At("TAIL"))
    private void phantom$syncAutoStashButton(CallbackInfo ci) {
        if (phantom$autoStashButton == null) {
            return;
        }
        AutoStashButtonEvent query = new AutoStashButtonEvent((AbstractContainerScreen<?>) (Object) this);
        query.post();
        phantom$autoStashButton.setMessage(Component.literal(query.getLabel()));
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void phantom$handleContainerKeyPress(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (new ScreenKeyEvent(input, false, hoveredSlot).post()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void phantom$onContainerMouseClicked(MouseButtonEvent input, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (new ContainerMouseEvent(ContainerMouseEvent.Kind.CLICK).post()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void phantom$onContainerMouseReleased(MouseButtonEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (new ContainerMouseEvent(ContainerMouseEvent.Kind.RELEASE).post()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void phantom$onContainerMouseDragged(MouseButtonEvent input, double dragX, double dragY, CallbackInfoReturnable<Boolean> cir) {
        if (new ContainerMouseEvent(ContainerMouseEvent.Kind.DRAG).post()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void phantom$onContainerMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (new ContainerMouseEvent(ContainerMouseEvent.Kind.SCROLL).post()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(
        method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void phantom$onSlotClicked(Slot slot, int slotId, int button, ClickType clickType, CallbackInfo ci) {
        ContainerSlotClickEvent event = new ContainerSlotClickEvent(
            getTitle().getString(), menu, slot, slotId, button, clickType);
        if (event.post()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSlots", at = @At("TAIL"))
    private void phantom$renderSlotOverlays(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        for (Slot slot : menu.slots) {
            if (!slot.isActive() || slot.isFake()) {
                continue;
            }
            new ContainerSlotRenderEvent(graphics, slot, leftPos + slot.x, topPos + slot.y).post();
        }
    }
}
