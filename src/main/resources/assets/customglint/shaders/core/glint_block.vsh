#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints BLOCK glint vertex shader. Like glint_color.vsh, but also passes the raw block-atlas UV
// (texCoordMask) untouched so the fragment shader can alpha-test the block texture and mask the glint to
// the block model's cutout silhouette (mooshroom mushrooms, snow-golem pumpkin). Those layers are drawn by
// a different pipeline, so the glint can't lean on EQUAL-depth cutout masking the way item/entity glint does.
// It samples the block texture's alpha itself instead. texCoord0 is still the TextureMat-scrolled glint UV.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 texCoord0;
layout(location = 3) out vec2 texCoordMask;
layout(location = 4) out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoordMask = UV0;
    vertexColor = Color;
}
