#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC vertex shader for BLOCK-model entity layers. Identical to
// core/chromatic.vsh, but also passes the raw block-atlas UV (texCoordMask) so the fragment shader can
// alpha-test the block texture (Sampler2) and mask the oil-slick to the block's cutout shape instead of
// tiling over the whole quad plane. The per-layer payload still rides the TextureMat spare slots.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 noiseCoord;
layout(location = 3) out vec2 texCoordMask;
layout(location = 4) out vec4 vertexColor;
layout(location = 5) out float vSeed;
layout(location = 6) out float vMorph;
layout(location = 7) out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoordMask = UV0;
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
