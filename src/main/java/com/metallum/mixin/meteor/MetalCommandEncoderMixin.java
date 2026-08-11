package com.metallum.mixin.meteor;

import com.metallum.render.MetalCommandEncoder;
import com.metallum.render.MetalDevice;
import com.mojang.blaze3d.systems.RenderPassBackend;
import meteordevelopment.meteorclient.mixininterface.IGpuDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies a pending Meteor scissor to Metal render passes.
 *
 * <p>Meteor Client applies the global scissor stored on the device (see
 * {@link MetalDeviceMixin}) whenever a render pass is created. For the GL
 * backend Meteor hooks {@code GlCommandEncoder#createRenderPass} with its own
 * {@code GlCommandEncoderMixin}; this mixin provides the equivalent hook for
 * {@link MetalCommandEncoder}, without which the scissor would never be
 * applied to Metal render passes.</p>
 *
 * <p>Only applied when meteor-client is loaded (see
 * {@code MetallumMixinConfigPlugin}).</p>
 */
@Mixin(MetalCommandEncoder.class)
public abstract class MetalCommandEncoderMixin {
    @Shadow
    @Final
    private MetalDevice device;

    @SuppressWarnings("deprecation")
    @Inject(method = "createRenderPass", at = @At("RETURN"), remap = false)
    private void metallum$onCreateRenderPass(CallbackInfoReturnable<RenderPassBackend> cir) {
        ((IGpuDevice) (Object) device).meteor$onCreateRenderPass(cir.getReturnValue());
    }
}
