package com.metallum.client.metal.render.mtl;

import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public record MTLDevice(MemorySegment handle) {
    private static final MethodHandle CREATE_SYSTEM_DEFAULT_DEVICE = ObjC.LINKER.downcallHandle(
            ObjC.METAL.findOrThrow("MTLCreateSystemDefaultDevice"), FunctionDescriptor.of(ADDRESS));

    private static final Msg NEW_BUFFER = Msg.of("newBufferWithLength:options:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg NEW_COMMAND_QUEUE = Msg.of("newCommandQueue", ADDRESS);
    private static final Msg NEW_TEXTURE = Msg.of("newTextureWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_SAMPLER_STATE = Msg.of("newSamplerStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_DEPTH_STENCIL_STATE = Msg.of("newDepthStencilStateWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg NEW_FENCE = Msg.of("newFence", ADDRESS);
    private static final Msg NAME = Msg.of("name", ADDRESS);
    private static final Msg MAX_BUFFER_LENGTH = Msg.of("maxBufferLength", JAVA_LONG);
    private static final Msg RECOMMENDED_MAX_WORKING_SET_SIZE = Msg.of("recommendedMaxWorkingSetSize", JAVA_LONG);

    public MTLDevice {
        if (handle == null || handle.address() == 0L) {
            throw new IllegalArgumentException("MTLDevice handle is null");
        }
    }

    @Nullable
    public static MTLDevice createSystemDefault() {
        try {
            MemorySegment device = (MemorySegment) CREATE_SYSTEM_DEFAULT_DEVICE.invokeExact();
            return ObjC.isNil(device) ? null : new MTLDevice(device);
        } catch (Throwable throwable) {
            throw new IllegalStateException("MTLCreateSystemDefaultDevice failed", throwable);
        }
    }

    public String name() {
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            return ObjC.javaString(NAME.sendPtr(handle));
        }
    }

    public long maxBufferLength() {
        return MAX_BUFFER_LENGTH.sendLong(handle);
    }

    public long recommendedMaxWorkingSetSize() {
        return RECOMMENDED_MAX_WORKING_SET_SIZE.sendLong(handle);
    }

    public MTLBuffer newBuffer(final long length, final long options) {
        MemorySegment buffer = NEW_BUFFER.sendPtr(handle, length, options);
        if (ObjC.isNil(buffer)) {
            throw new IllegalStateException("newBufferWithLength:options: returned nil (length=" + length + ")");
        }
        return new MTLBuffer(buffer);
    }

    public MTLCommandQueue newCommandQueue() {
        MemorySegment queue = NEW_COMMAND_QUEUE.sendPtr(handle);
        if (ObjC.isNil(queue)) {
            throw new IllegalStateException("newCommandQueue returned nil");
        }
        return new MTLCommandQueue(queue);
    }

    public MemorySegment newTexture(final MTLTextureDescriptor descriptor) {
        MemorySegment texture = NEW_TEXTURE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(texture)) {
            throw new IllegalStateException("newTextureWithDescriptor: returned nil");
        }
        return texture;
    }

    public MemorySegment newSamplerState(final MTLSamplerDescriptor descriptor) {
        MemorySegment sampler = NEW_SAMPLER_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(sampler)) {
            throw new IllegalStateException("newSamplerStateWithDescriptor: returned nil");
        }
        return sampler;
    }

    public MemorySegment newDepthStencilState(final MTLDepthStencilDescriptor descriptor) {
        MemorySegment state = NEW_DEPTH_STENCIL_STATE.sendPtr(handle, descriptor.handle());
        if (ObjC.isNil(state)) {
            throw new IllegalStateException("newDepthStencilStateWithDescriptor: returned nil");
        }
        return state;
    }

    public MemorySegment newFence() {
        MemorySegment fence = NEW_FENCE.sendPtr(handle);
        if (ObjC.isNil(fence)) {
            throw new IllegalStateException("newFence returned nil");
        }
        return fence;
    }
}
