#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>

// Custom Glints colored glint fragment shader. Vanilla core/glint.fsh multiplies the (grayscale)
// design texture by the global ColorModulator; here the layer color rides the per-vertex Color
// instead, so several colors batch in one draw. ColorModulator stays white but is kept in the
// product so vanilla tint paths still compose. See 26/09-renderer-api-verified.md.

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 texCoord0;
layout(location = 3) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.004) {
        discard;
    }
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    // The glint blend is additive, so on-screen opacity is the brightness of the emitted rgb. The DESIGN
    // renders at FULL brightness: its texture alpha decides the SHAPE only (the discard above), never the
    // brightness, exactly like vanilla core/glint (color.rgb * fade). Only the colour's OWN alpha
    // (vertexColor.a) scales it, so a deliberately-translucent glint colour still fades.
    // Was `* color.a` (= textureAlpha * vertexColor.a), which folded the design texture's own alpha into
    // brightness and washed out soft-edged / gradient designs (the "too transparent" report).
    fragColor = vec4(color.rgb * fade * vertexColor.a, color.a);
}
