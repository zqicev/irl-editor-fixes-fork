package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.qualet.irlredactor.editor.LightEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the ImGui light editor on top of the finished frame.
 *
 * <p>1.21.11's GUI is rendered deferred (the screen records {@code DrawContext}
 * commands that the {@code GuiRenderer} flushes only after {@code Screen#render}
 * returns), so drawing raw-GL ImGui from the screen was overwritten by Minecraft's
 * own GUI. We instead hook {@code MinecraftClient#render} right after
 * {@code Framebuffer.blitToScreen()} — at that point the whole frame (world + GUI)
 * has been blitted to the default framebuffer and {@code swapBuffers} hasn't run
 * yet, so ImGui lands on top and is presented. The call is a cheap no-op unless a
 * {@link LightEditorScreen} is open.</p>
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientImGuiMixin
{
    @Inject(
        method = "render(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gl/Framebuffer;blitToScreen()V",
            shift = At.Shift.AFTER
        )
    )
    private void irl$renderImGuiOverlay(boolean tick, CallbackInfo ci)
    {
        LightEditorScreen.renderActiveOverlay();
    }
}
