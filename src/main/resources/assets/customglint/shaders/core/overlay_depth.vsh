#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints wing depth PRE-PASS vertex shader. Emits ONLY the clip position and the raw model UV.
// gl_Position is computed with the exact same two statements as core/glint_overlay.vsh and
// core/chromatic_overlay.vsh (viewPos = ModelViewMat * Position; gl_Position = ProjMat * viewPos), so the
// depth this pass writes is bit-identical to the depth the following glint/chromatic wing colour pass
// produces. That lets the pass keep only the nearest wing per pixel with a plain LEQUAL test.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 texCoord0;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;
    texCoord0 = UV0;
}
