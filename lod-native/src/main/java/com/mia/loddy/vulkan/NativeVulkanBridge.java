package com.mia.loddy.vulkan;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zero-copy FFI bridge between Minecraft Java Edition and the Rust Vulkan engine
 * using modern Java Project Panama (Foreign Function & Memory API).
 */
public final class NativeVulkanBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("MIA Loddy Vulkan");
    private static boolean panamaAvailable = false;

    private static Linker linker;
    private static SymbolLookup engineLib;
    private static Arena libraryArena;

    private static MethodHandle uploadChunkHandle;
    private static MethodHandle renderFrameHandle;
    private static MethodHandle uploadAtlasHandle;
    private static MethodHandle uploadLayerLookupHandle;

    static {
        try {
            linker = Linker.nativeLinker();
            libraryArena = Arena.ofShared();
            engineLib = SymbolLookup.libraryLookup("lod_native", libraryArena);

            uploadChunkHandle = linkSymbol("antigravity_upload_chunk",
                FunctionDescriptor.ofVoid(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            );

            renderFrameHandle = linkSymbol("antigravity_render_frame",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );

            uploadAtlasHandle = linkSymbol("antigravity_upload_atlas",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT
                )
            );

            uploadLayerLookupHandle = linkSymbol("antigravity_upload_layer_lookup",
                FunctionDescriptor.ofVoid(
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT
                )
            );

            panamaAvailable = true;
            LOGGER.info("[MIA Loddy] Project Panama Zero-Copy Vulkan Bridge initialized successfully!");
        } catch (Throwable t) {
            LOGGER.warn("[MIA Loddy] Project Panama FFI not available or lod_native dylib symbols missing: {}", t.getMessage());
            panamaAvailable = false;
        }
    }

    private static MethodHandle linkSymbol(String name, FunctionDescriptor descriptor) {
        Optional<MemorySegment> sym = engineLib.find(name);
        if (sym.isEmpty()) {
            throw new RuntimeException("Symbol not found in native library: " + name);
        }
        return linker.downcallHandle(sym.get(), descriptor);
    }

    public static boolean isPanamaAvailable() {
        return panamaAvailable;
    }

    /**
     * Uploads chunk mesh geometry to Vulkan VRAM with zero host memory copying.
     */
    public static void uploadChunkGeometry(int chunkId, long rawPointer, int sizeBytes) {
        if (!panamaAvailable || rawPointer == 0 || sizeBytes <= 0) return;
        try {
            MemorySegment ptr = MemorySegment.ofAddress(rawPointer);
            uploadChunkHandle.invokeExact(chunkId, ptr, sizeBytes);
        } catch (Throwable t) {
            LOGGER.error("Panama uploadChunkGeometry call failed", t);
        }
    }

    /**
     * Submits the 4x4 ViewProjection camera matrix (16 floats, 64 bytes) to render a frame.
     */
    public static void renderFrame(long matrixPointer) {
        if (!panamaAvailable || matrixPointer == 0) return;
        try {
            MemorySegment ptr = MemorySegment.ofAddress(matrixPointer);
            renderFrameHandle.invokeExact(ptr);
        } catch (Throwable t) {
            LOGGER.error("Panama renderFrame call failed", t);
        }
    }

    /**
     * Uploads the stitched block texture atlas bitmap to Vulkan.
     */
    public static void uploadAtlas(long atlasPointer, int width, int height) {
        if (!panamaAvailable || atlasPointer == 0 || width <= 0 || height <= 0) return;
        try {
            MemorySegment ptr = MemorySegment.ofAddress(atlasPointer);
            uploadAtlasHandle.invokeExact(ptr, width, height);
        } catch (Throwable t) {
            LOGGER.error("Panama uploadAtlas call failed", t);
        }
    }

    /**
     * Uploads the block state -> 2D array texture layer lookup table.
     */
    public static void uploadBlockLayerLookup(int[] stateToLayerMap) {
        if (!panamaAvailable || stateToLayerMap == null || stateToLayerMap.length == 0) return;
        try {
            MemorySegment arraySegment = MemorySegment.ofArray(stateToLayerMap);
            uploadLayerLookupHandle.invokeExact(arraySegment, stateToLayerMap.length);
        } catch (Throwable t) {
            LOGGER.error("Panama uploadBlockLayerLookup call failed", t);
        }
    }
}
