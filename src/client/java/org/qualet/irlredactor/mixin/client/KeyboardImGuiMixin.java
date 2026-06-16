package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.Keyboard;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.qualet.irlredactor.imgui.ImGuiInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Swallows key / char events when an ImGui text field is active (e.g. editing a
 * light's name), so typing doesn't also trigger Minecraft / Replay Mod keybinds.
 * Gated on WantCaptureKeyboard, which — with keyboard nav disabled (see
 * {@code ImGuiRuntime}) — is true only while a text input is focused.
 *
 * <p>1.21.11 wraps the raw GLFW key/char events in {@code KeyInput} / {@code CharInput}
 * records, so {@code onKey} is {@code (long, int, KeyInput)} and {@code onChar} is
 * {@code (long, CharInput)} here (were {@code (long,int,int,int,int)} /
 * {@code (long,int,int)} on 1.20.4).</p>
 */
@Mixin(Keyboard.class)
public class KeyboardImGuiMixin
{
    @Inject(method = "onKey(JILnet/minecraft/client/input/KeyInput;)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureKey(long window, int key, KeyInput input, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }

    @Inject(method = "onChar(JLnet/minecraft/client/input/CharInput;)V", at = @At("HEAD"), cancellable = true)
    private void irl$captureChar(long window, CharInput input, CallbackInfo ci)
    {
        if (ImGuiInput.wantsKeyboard())
        {
            ci.cancel();
        }
    }
}
