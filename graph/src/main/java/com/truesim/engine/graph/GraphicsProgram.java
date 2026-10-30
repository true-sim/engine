package com.truesim.engine.graph;

import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

import com.truesim.engine.vulkan.Format;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a graphics program is made of, for {@link GraphBuilder#graphicsProgram}: shader stages (SPIR-V classpath
 * resources and their entry points), the formats it renders into, its push constant bytes and whether it draws back
 * faces. A value: equal descriptions make one program, built once and shared.
 *
 * <pre>{@code
 * Pipeline draw = g.graphicsProgram(GraphicsProgram.of(DrawPush.BYTES)
 *     .vertex("shaders/particles.spv", "vertexMain").fragment("shaders/particles.spv", "fragmentMain")
 *     .color(Format.R16G16B16A16_SFLOAT).depth(Format.D32_SFLOAT));
 * }</pre>
 *
 * @param stages the shader stages
 * @param colors the formats of the color attachments, location 0 first
 * @param depth the format of the depth attachment (reverse depth, tested and written), or null
 * @param pushBytes the push constant bytes
 * @param doubleSided whether back faces are drawn too; otherwise counter-clockwise triangles are front faces
 */
public record GraphicsProgram(List<Stage> stages, List<Format> colors, Format depth, int pushBytes,
    boolean doubleSided) {

    /** A shader stage of a graphics program. */
    public enum Kind {
        VERTEX(VK_SHADER_STAGE_VERTEX_BIT),
        FRAGMENT(VK_SHADER_STAGE_FRAGMENT_BIT),
        TASK(VK_SHADER_STAGE_TASK_BIT_EXT),
        MESH(VK_SHADER_STAGE_MESH_BIT_EXT);

        final int bit;

        Kind(int bit) {
            this.bit = bit;
        }
    }

    /** Stage {@code kind} running {@code entry} of the SPIR-V classpath resource {@code shader}. */
    public record Stage(Kind kind, String shader, String entry) {
        public Stage {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(shader, "shader");
            Objects.requireNonNull(entry, "entry");
        }
    }

    public GraphicsProgram {
        stages = List.copyOf(stages);
        colors = List.copyOf(colors);
        if (pushBytes < 0 || pushBytes % 4 != 0) {
            throw new IllegalArgumentException("Push constants are a multiple of 4 bytes: " + pushBytes);
        }
        for (Format color : colors) {
            if (color.depth()) {
                throw new IllegalArgumentException("A color attachment of a depth format: " + color);
            }
        }
        if (depth != null && !depth.depth()) {
            throw new IllegalArgumentException("A depth attachment of a color format: " + depth);
        }
    }

    /** No stages yet, with {@code pushBytes} of push constants. */
    public static GraphicsProgram of(int pushBytes) {
        return new GraphicsProgram(List.of(), List.of(), null, pushBytes, false);
    }

    /** With a vertex stage. */
    public GraphicsProgram vertex(String shader, String entry) {
        return stage(Kind.VERTEX, shader, entry);
    }

    /** With a fragment stage. */
    public GraphicsProgram fragment(String shader, String entry) {
        return stage(Kind.FRAGMENT, shader, entry);
    }

    /** With a task stage, before a {@link #mesh} one. */
    public GraphicsProgram task(String shader, String entry) {
        return stage(Kind.TASK, shader, entry);
    }

    /** With a mesh stage: mesh shaders make their own primitives, so there is no vertex stage. */
    public GraphicsProgram mesh(String shader, String entry) {
        return stage(Kind.MESH, shader, entry);
    }

    /** With stage {@code kind}; a kind once. */
    public GraphicsProgram stage(Kind kind, String shader, String entry) {
        for (Stage stage : stages) {
            if (stage.kind == kind) {
                throw new IllegalArgumentException("A " + kind + " stage already: " + stage);
            }
        }
        List<Stage> more = new ArrayList<>(stages);
        more.add(new Stage(kind, shader, entry));
        return new GraphicsProgram(more, colors, depth, pushBytes, doubleSided);
    }

    /** With another color attachment, of {@code format}. */
    public GraphicsProgram color(Format format) {
        List<Format> more = new ArrayList<>(colors);
        more.add(Objects.requireNonNull(format, "format"));
        return new GraphicsProgram(stages, more, depth, pushBytes, doubleSided);
    }

    /** With a depth attachment of {@code format}. */
    public GraphicsProgram depth(Format format) {
        return new GraphicsProgram(stages, colors, Objects.requireNonNull(format, "format"), pushBytes, doubleSided);
    }

    /** Drawing back faces too. */
    public GraphicsProgram drawBackFaces() {
        return new GraphicsProgram(stages, colors, depth, pushBytes, true);
    }
}
