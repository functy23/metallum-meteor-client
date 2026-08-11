package com.metallum.mixin.meteor;

import com.metallum.render.MetalDevice;
import com.mojang.blaze3d.systems.RenderPassBackend;
import meteordevelopment.meteorclient.mixininterface.IGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Implements Meteor Client's {@link IGpuDevice} on the Metal device backend.
 *
 * <p>Meteor's GUI (Scissor#push / Scissor#pop) casts the active
 * {@code GpuDeviceBackend} to {@link IGpuDevice} and stores a pending global
 * scissor on it, which is later applied when a render pass is created. Meteor
 * only implements this interface for {@code GlDevice} and {@code VulkanDevice},
 * so without this mixin opening any Meteor window with a scrollable view
 * (WView) throws a {@link ClassCastException} and crashes the render thread:
 * "com.metallum.render.MetalDevice cannot be cast to IGpuDevice".</p>
 *
 * <p>This mirrors Meteor Client's own {@code GlDeviceMixin} and is only applied
 * when meteor-client is loaded (see {@code MetallumMixinConfigPlugin}).</p>
 */
@Mixin(MetalDevice.class)
public abstract class MetalDeviceMixin implements IGpuDevice {
    @Unique
    private int x, y, width, height;

    @Unique
    private boolean set;

    @Override
    public void meteor$pushScissor(int x, int y, int width, int height) {
        if (set)
            throw new IllegalStateException("Currently there can only be one global scissor pushed");

        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;

        set = true;
    }

    @Override
    public void meteor$popScissor() {
        if (!set)
            throw new IllegalStateException("No scissor pushed");

        set = false;
    }

    @Deprecated
    @Override
    public void meteor$onCreateRenderPass(RenderPassBackend backend) {
        if (set) {
            backend.enableScissor(x, y, width, height);
        }
    }
}
