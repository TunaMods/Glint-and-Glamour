#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints glow-silhouette vertex shader. Like outline_color.vsh (POSITION_TEX_COLOR passthrough)
// but also forwards (a) the camera-space distance so the fragment can fade outline thickness with
// distance, and (b) the clip-space position so the fragment can reconstruct its screen UV and sample
// the scene depth for the per-fragment occlusion test (the single-pass combined mask, see
// glow_silhouette.fsh). viewDist is just -(view-space Z): linear blocks-from-camera, no near/far needed.

layout(location = 0) in vec3 Position;
layout(location = 2) in vec4 Color;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) out vec2 texCoord0;
layout(location = 2) out float viewDist;
layout(location = 3) out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    vertexColor = Color;
    texCoord0 = UV0;
    viewDist = -viewPos.z;
    screenPos = gl_Position;
}
