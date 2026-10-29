#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints fullscreen-triangle vertex shader (copy of vanilla core/screenquad). Shipped under the
// customglint namespace so the glow-outline composite pipeline resolves it without namespace ambiguity.
// Emits a single oversized triangle covering the screen from gl_VertexIndex alone: draw(0, 3), no buffers.

layout(location = 0) out vec2 texCoord;

void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vec4 pos = vec4(uv * vec2(2, 2) + vec2(-1, -1), 0, 1);

    gl_Position = pos;
    texCoord = uv;
}
