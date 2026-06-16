package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows mouse button / scroll events before Minecraft (and any open screen,
 * e.g. Replay Mod's timeline) handles them, whenever the ImGui editor wants the
 * mouse. ImGui has already seen the event via its own chained GLFW callback, so the
 * panel still works; this just stops the click from also scrubbing the timeline.
 *
 * <p>1.21.11 wraps the raw GLFW button event in a {@code MouseInput} record, so
 * {@code onMouseButton} is {@code (long, MouseInput, int)} here (was
 * {@code (long, int, int, int)} on 1.20.4); {@code onMouseScroll} is unchanged.</p>
 */
@Mixin(Mouse.class)
public class MouseImGuiMixin
{
    @Inject(method = "onMouseButton(JLnet/minecraft/client/input/MouseInput;I)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseButton(long window, MouseInput input, int mods, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }

    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureMouseScroll(long window, double horizontal, double vertical, CallbackInfo ci)
    {
        if (ImGuiInput.wantsMouse())
        {
            ci.cancel();
        }
    }
}
