#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC vertex shader. Same skeleton as core/glint_color.vsh, but the
// chromatic design has no texture: the fragment shader synthesises an oil-slick from value-noise.
// The per-layer payload that the immutable RenderPipeline can't carry as a uniform rides spare slots
// of the TextureMat (fed by CustomGlintRenderer's chromatic animation supplier):
//   TextureMat[2][3] = per-trim seed   (decorrelates each trim's pattern; "no two look alike")
//   TextureMat[2][0] = morph speed     (scales the GameTime-driven flow)
//   TextureMat[2][1] = colour count    (0 => rainbow fallback; 1..8 => palette texels)
// The 2D part of TextureMat still scales/positions the noise UV exactly like the normal glint.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 noiseCoord;
layout(location = 3) out vec4 vertexColor;
layout(location = 4) out float vSeed;
layout(location = 5) out float vMorph;
layout(location = 6) out float vCount;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
}
