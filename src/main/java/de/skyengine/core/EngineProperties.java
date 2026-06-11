package de.skyengine.core;

import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL31C.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT;

public class EngineProperties {

    private boolean useDirectStateAccess;
    private boolean useMultiDrawIndirect;
    private boolean useBufferStorage;
    private boolean useClearBuffer;
    private boolean drawPointsWithGS;
    private boolean useInverseDepth;
    private boolean useNvMultisampleCoverage;
    private boolean generateDrawCallsViaShader;
    private boolean useOcclusionCulling;
    private boolean useTemporalCoherenceOcclusionCulling;
    private boolean sourceIndirectDrawCallCountFromBuffer;
    private boolean useRepresentativeFragmentTest;
    private boolean canUseSynchronousDebugCallback;

    /* Other queried OpenGL state/configuration */
    private int uniformBufferOffsetAlignment;

    public void update(GLCapabilities caps) {
        this.useDirectStateAccess = caps.GL_ARB_direct_state_access/* 4.5 */ && caps.GL_ARB_vertex_attrib_binding/* 4.3 */ || caps.OpenGL45;
        this.useMultiDrawIndirect = caps.GL_ARB_multi_draw_indirect || caps.OpenGL43;
        this.useBufferStorage = caps.GL_ARB_buffer_storage || caps.OpenGL44;
        this.useClearBuffer = caps.GL_ARB_clear_buffer_object || caps.OpenGL43;
        this.drawPointsWithGS = useMultiDrawIndirect; // <- we just haven't implemented point/GS rendering without MDI yet
        this.useInverseDepth = caps.GL_ARB_clip_control || caps.OpenGL45;
        this.useNvMultisampleCoverage = caps.GL_NV_framebuffer_multisample_coverage;
        this.canUseSynchronousDebugCallback = caps.GL_ARB_debug_output || caps.OpenGL43;
        this.generateDrawCallsViaShader = caps.GL_ARB_shader_image_load_store/* 4.2 */ && caps.GL_ARB_shader_storage_buffer_object/* 4.3 */ && caps.GL_ARB_shader_atomic_counters/* 4.2 */ || caps.OpenGL43;
        this.useOcclusionCulling = this.generateDrawCallsViaShader && this.useMultiDrawIndirect;
        this.useTemporalCoherenceOcclusionCulling = true;
        this.sourceIndirectDrawCallCountFromBuffer = this.generateDrawCallsViaShader && (caps.GL_ARB_indirect_parameters || caps.OpenGL46);
        this.useRepresentativeFragmentTest = caps.GL_NV_representative_fragment_test;

        this.uniformBufferOffsetAlignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
    }

    public boolean isUseDirectStateAccess() {
        return useDirectStateAccess;
    }

    public boolean isUseMultiDrawIndirect() {
        return useMultiDrawIndirect;
    }

    public boolean isUseBufferStorage() {
        return useBufferStorage;
    }

    public boolean isUseClearBuffer() {
        return useClearBuffer;
    }

    public boolean isDrawPointsWithGS() {
        return drawPointsWithGS;
    }

    public boolean isUseInverseDepth() {
        return useInverseDepth;
    }

    public boolean isUseNvMultisampleCoverage() {
        return useNvMultisampleCoverage;
    }

    public boolean isUseSynchronousDebugCallback() {
        return canUseSynchronousDebugCallback;
    }

    public boolean isGenerateDrawCallsViaShader() {
        return generateDrawCallsViaShader;
    }

    public boolean isUseOcclusionCulling() {
        return useOcclusionCulling;
    }

    public boolean isUseTemporalCoherenceOcclusionCulling() {
        return useTemporalCoherenceOcclusionCulling;
    }

    public boolean isSourceIndirectDrawCallCountFromBuffer() {
        return sourceIndirectDrawCallCountFromBuffer;
    }

    public boolean isUseRepresentativeFragmentTest() {
        return useRepresentativeFragmentTest;
    }

    public int getUniformBufferOffsetAlignment() {
        return uniformBufferOffsetAlignment;
    }
}
