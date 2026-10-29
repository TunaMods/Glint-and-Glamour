#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints NORMAL-glint overlay fragment shader, LOOSE-occlusion variant for TRANSLUCENT entity-layer
// shells (the slime outer cube). Identical to core/glint_overlay.fsh except for the occlusion bias.
//
// Why a separate variant: a translucent shell's depth in the committed scene buffer is unstable. Under a
// shader pack Iris re-sorts translucent geometry every frame, so the shell's own window depth flips with
// camera angle (and may not be written to the main depth at all; only the opaque geometry behind it is).
// The tight slope-scaled bias of the opaque variant (MIN_BIAS ~0.015) is far smaller than that per-frame
// wobble, so the shell self-occludes and the glint drops out on some faces at some angles. That is exactly
// the translucent-depth instability the 1.20.1 OPAQUE_DECAL fix was built around.
//
// The shell is a small convex NO_CULL hull: we do NOT want fine self-occlusion of it (a little back-face
// glint bleeding through a translucent blob is invisible). We only want CLEARLY nearer OPAQUE geometry (a
// mob or wall in front) to occlude it. So this variant uses a single flat, generous blocks-bias with no
// fwidth slope term: the pre-regression value that worked for slimes before the slope-scaled change.
uniform sampler2D Sampler0;     // grayscale design (scrolling pattern; rgb = brightness, a = pattern shape)
uniform sampler2D Sampler1;     // model/atlas texture; alpha drives the cutout (white dummy => full mesh)
uniform sampler2D DepthSampler; // full-res committed scene depth (main target)

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 designCoord;
layout(location = 3) in vec2 cutoutCoord;
layout(location = 4) in vec4 vertexColor;
layout(location = 5) in float viewDist;
layout(location = 6) in vec4 screenPos;

layout(location = 0) out vec4 fragColor;

// Flat occlusion tolerance in BLOCKS of linear view distance (distance-independent). Generous on purpose:
// large enough to absorb the translucent shell's re-sorted-depth wobble (no self-cull dropout), still small
// enough that opaque geometry a fraction of a block in front occludes the shell. No slope term; the shell
// doesn't need a grazing-rim allowance because we tolerate its self-occlusion outright.
const float OCCLUSION_BIAS = 0.10;

// Window depth -> positive linear eye distance, through the same projection the geometry was drawn with.
// ProjMat[3][2] / (ndc + ProjMat[2][2]) holds for any perspective matrix (reversed-Z included); only the
// depth -> ndc step depends on the clip depth range, which the shader compiler reports.
float eyeDist(float windowDepth) {
#ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    float ndc = windowDepth;
#else
    float ndc = windowDepth * 2.0 - 1.0;
#endif
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

void main() {
    if (texture(Sampler1, cutoutCoord).a < 0.1) {
        discard;
    }

    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;
    float sceneDist = eyeDist(sceneDepth);
    if (viewDist > sceneDist + OCCLUSION_BIAS) {
        discard;
    }

    // Identical to core/glint_overlay.fsh: grayscale design × per-vertex colour, additive GLINT blend.
    vec4 color = texture(Sampler0, designCoord) * vertexColor;
    if (color.a < 0.004) {
        discard;
    }
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(color.rgb * fade * vertexColor.a, color.a);
}
