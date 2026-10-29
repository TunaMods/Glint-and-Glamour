#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC overlay vertex shader. Same payload as core/chromatic.vsh (the
// per-trim seed / morph speed / colour count packed into spare TextureMat slots), but it ALSO forwards
// the linear view-space distance and the clip-space position so the fragment can sample the scene depth
// and keep only the visible front surface. Used by the post-Iris drain (EntityGlintRender.drainChromatic
// Overlays): under an active shader pack the chromatic glint can't draw in-phase (Iris replaces our
// procedural program with a pack program → flat white), so we re-render the model AFTER the framegraph
// onto an isolated target and decide occlusion in-shader instead of via the GPU depth test.

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec2 noiseCoord;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out vec4 vertexColor;
layout(location = 5) out float vSeed;
layout(location = 6) out float vMorph;
layout(location = 7) out float vCount;
layout(location = 8) out float viewDist;
layout(location = 9) out vec4 screenPos;

void main() {
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPos;

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    noiseCoord = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
    texCoord0 = UV0;   // raw model UV for the cutout alpha-test (Sampler0 = the model texture)
    vertexColor = Color;
    vSeed  = TextureMat[2][3];
    vMorph = TextureMat[2][0];
    vCount = TextureMat[2][1];
    viewDist = -viewPos.z;
    screenPos = gl_Position;
}
