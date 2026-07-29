package com.metallum.client.metal.render.mtl;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.ADDRESS;

@Environment(EnvType.CLIENT)
public final class MTLCommandBuffer {
    private static final Msg BLIT_COMMAND_ENCODER = Msg.of("blitCommandEncoder", ADDRESS);

    private MemorySegment handle;

    MTLCommandBuffer(final MemorySegment handle) {
        this.handle = handle;
    }

    public MTLBlitCommandEncoder makeBlitCommandEncoder() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MemorySegment encoder = BLIT_COMMAND_ENCODER.sendPtr(handle());
            if (ObjC.isNil(encoder)) {
                throw new IllegalStateException("Failed to create MTLBlitCommandEncoder");
            }
            return new MTLBlitCommandEncoder(ObjC.retain(encoder));
        }
    }

    public MTLRenderCommandEncoder makeRenderCommandEncoder(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        MemorySegment encoder = MetalNativeBridge.MTLCommandBuffer_makeRenderCommandEncoder(
                handle(),
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColorEnabled,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                clearDepthEnabled,
                clearDepth
        );
        if (ObjC.isNil(encoder)) {
            throw new IllegalStateException("Failed to create MTLRenderCommandEncoder");
        }
        return new MTLRenderCommandEncoder(encoder);
    }

    public void clearColorDepthTexturesRegion(
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int regionX,
            final int regionY,
            final int regionWidth,
            final int regionHeight,
            final MemorySegment globalFence
    ) {
        MetalNativeBridge.MTLCommandBuffer_clearColorDepthTexturesRegion(
                handle(),
                colorTexture,
                clearColorRed,
                clearColorGreen,
                clearColorBlue,
                clearColorAlpha,
                depthTexture,
                clearDepth,
                regionX,
                regionY,
                regionWidth,
                regionHeight,
                globalFence
        );
    }

    public void encodePresentTextureToDrawable(final MemorySegment layer, final MemorySegment sourceTexture, final MemorySegment globalFence) {
        MetalNativeBridge.MTLCommandBuffer_encodePresentTextureToDrawable(handle(), layer, sourceTexture, globalFence);
    }

    public void commit() {
        MetalNativeBridge.MTLCommandBuffer_commit(handle());
    }

    public void commitWithSignal(final MemorySegment semaphore) {
        MetalNativeBridge.MTLCommandBuffer_commitWithSignal(handle(), semaphore);
    }

    public boolean isCompleted() {
        if (ObjC.isNil(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_isCompleted(handle()) == 1;
    }

    public boolean waitUntilCompleted(final long timeoutMs) {
        if (ObjC.isNil(handle)) {
            return true;
        }
        return MetalNativeBridge.MTLCommandBuffer_waitUntilCompleted(handle(), Math.max(timeoutMs, 0L)) == 0;
    }

    public void pushDebugGroup(final String label) {
        MetalNativeBridge.MTLCommandBuffer_pushDebugGroup(handle(), label);
    }

    public void popDebugGroup() {
        MetalNativeBridge.MTLCommandBuffer_popDebugGroup(handle());
    }

    public void close() {
        if (ObjC.isNil(handle)) {
            return;
        }
        ObjC.release(handle);
        handle = MemorySegment.NULL;
    }

    private MemorySegment handle() {
        if (ObjC.isNil(handle)) {
            throw new IllegalStateException("MTLCommandBuffer is closed");
        }
        return handle;
    }
}
