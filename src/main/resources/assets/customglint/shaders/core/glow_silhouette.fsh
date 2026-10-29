#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// Custom Glints glow-silhouette fragment shader, the SINGLE combined mask (replaces the old separate
// FULL-shape + VISIBLE passes, which cost a second model render + a second emit per mob). One render of
// the model now produces both pieces of information the composite needs, encoded in alpha:
//
//   alpha == 0            : empty (not part of any entity), discarded shape texels.
//   1..127  (v/255)       : IN SHAPE but OCCLUDED (behind world geometry), object id = v. Marks the shape
//                           (so the composite doesn't ring its interior) but is NOT a ring source.
//   128..255 (v/255)      : VISIBLE (unoccluded), object id = v - 128. The composite rings outward from
//                           these, keeping each object's ring separate by id.
//
// The per-object id rides the vertex-colour ALPHA (forced 1..127 by FullColorOverrideConsumer), so every
// glowing entity / armor piece / item gets its own outline instead of fusing into one union ring.
//
// rgb is always the glow colour. Occlusion is decided here per-fragment by sampling the full-res scene
// depth (DepthSampler = the main target's depth texture, read as a normal sampler2D → depth in .r) and
// comparing it to this fragment's own window depth. This removes the separate depth-downsample pass and
// the second silhouette pass entirely. Sampler0 alpha drives the silhouette shape (transparent texels
// discard) exactly as before.

uniform sampler2D Sampler0;
uniform sampler2D DepthSampler;

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in float viewDist;
layout(location = 3) in vec4 screenPos;

layout(location = 0) out vec4 fragColor;

// Ring thickness is NOT decided here. Alpha carries visibility + object id only. The composite
// (post/glow_outline_id) owns the per-category width, the distance thinning and the edge feather, where
// it has the source depth to scale by.

// Occlusion bias, in BLOCKS of linear view-space distance. The test compares the fragment's eye distance
// (viewDist) against the scene's eye distance reconstructed from the sampled depth, NOT raw window depth.
// Window depth is nonlinear (1/z), so a fixed window-depth epsilon corresponds to a world gap that grows
// as distance² and lets the ring leak through solid cutout leaf texels once the entity is far enough away
// (the leaf surface and the entity collapse into the same window-depth bucket). A bias measured in blocks
// is distance-independent: it occludes a leaf 0.1+ blocks in front identically near and far, while the
// entity's own surface (≈0 blocks away from itself) always reads visible.
//
// SLOPE-SCALED (like the glint/chromatic overlays): a flat 0.10-block bias is larger than a thin model's
// own front-to-back gap, so a part tucked BEHIND another (a shield's handle behind its face) reads as
// visible and its silhouette gets ringed, showing "through" the front, since the composite draws over the
// scene. The bias only needs to be loose at grazing SILHOUETTE edges (where one pixel spans a big depth
// range and a tight bias would wrongly occlude the outer rim). fwidth(viewDist) is that per-pixel depth
// span, so scaling the bias by it stays tight on camera-facing surfaces (hidden inner parts occlude, no
// show-through) and widens only at grazing edges (outer silhouette stays visible → still rings). MIN_BIAS
// covers depth quantization + any Iris-vs-vanilla depth mismatch.
const float MIN_BIAS = 0.015;
const float SLOPE_BIAS = 1.5;

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
    if (texture(Sampler0, texCoord0).a == 0.0) {
        discard;
    }

    // Reconstruct screen UV from the interpolated clip position (resolution-independent; no viewport
    // uniform needed) and sample the full-res scene depth at this fragment.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;

    float sceneDist = eyeDist(sceneDepth);

    float bias = max(MIN_BIAS, SLOPE_BIAS * fwidth(viewDist));
    bool visible = viewDist <= sceneDist + bias;
    // Pack (visibility, object id) into alpha: occluded = id (1..127), visible = 128 + id. The id rides the
    // forced vertex-colour alpha; the composite (post/glow_outline_id) decodes it to keep rings separate.
    int id = clamp(int(vertexColor.a * 255.0 + 0.5), 1, 127);
    int v = visible ? (128 + id) : id;
    fragColor = vec4(vertexColor.rgb, float(v) / 255.0);
}
