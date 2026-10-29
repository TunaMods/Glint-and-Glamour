#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

// Custom Glints NORMAL-glint overlay fragment shader, the post-Iris counterpart of core/glint_color.fsh.
// The design brightness + per-vertex tint are identical to the in-phase glint; the only additions are the
// cutout alpha-test and the per-fragment scene-depth occlusion test, both needed because the model is
// re-rendered AFTER Iris finishes the frame (no in-phase surface depth to EQUAL-test against). Borrowed
// from core/chromatic_overlay.fsh / core/glow_silhouette.fsh.

uniform sampler2D Sampler0;     // grayscale design (the scrolling pattern; rgb = brightness, a = pattern shape)
uniform sampler2D Sampler1;     // model/atlas texture; its alpha drives the cutout silhouette
uniform sampler2D DepthSampler; // full-res committed scene depth (main target)

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
in vec2 designCoord;
in vec2 cutoutCoord;
in vec4 vertexColor;
in float viewDist;
in vec4 screenPos;

out vec4 fragColor;

// Occlusion tolerance in BLOCKS of linear view distance (distance-independent, unlike a window-depth
// epsilon). This is the TIGHT variant, for OPAQUE entity-surface layers (sheep wool, saddle, clothing) and
// bodies whose depth is stably written to the committed buffer. The glint fills the whole SURFACE, so it must
// occlude the entity's OWN parts against each other (a sheep leg tucked under the wool, a limb behind the
// torso). Those inter-part gaps are small, so a coarse flat bias would leak the covered part through the
// front, the "body parts glint through the model" report. SLOPE-SCALED: the floor needed on a camera-FACING
// surface is tiny (our re-render matches the scene depth to ~mm); the bias only grows at grazing SILHOUETTE
// edges, where one pixel spans a large depth range and a too-tight bias would self-cull the surface's own rim.
// fwidth(viewDist) IS that per-pixel span. SLOPE_BIAS is kept LOW (0.6, matching the chromatic overlay): 1.5
// widened the grazing bias past the inter-part gaps and re-opened the show-through; 0.6 still clears the rim's
// own fwidth without ballooning far enough to leak a hidden limb. MIN_BIAS covers depth quantization + any
// Iris-vs-vanilla depth mismatch. TRANSLUCENT shells (slime) take the flat loose variant instead. See
// core/glint_overlay_loose.fsh (their re-sorted depth needs a coarse bias this tight test would drop out).
const float MIN_BIAS = 0.015;
const float SLOPE_BIAS = 0.6;

// Window depth -> positive linear eye distance, through the same projection the geometry was drawn with.
// ProjMat[3][2] / (ndc + ProjMat[2][2]) holds for any perspective matrix; only the depth -> ndc step depends
// on the clip depth range. 26.2 builds its projection reversed-Z (near and far swapped) in [0,1] clip depth on
// Vulkan and on OpenGL with ARB_clip_control, and in [-1,1] without it. Reversed, ProjMat[2][2] is
// near/(far-near) (~0) in the first case and (far+near)/(far-near) (~1) in the second, so it tells them apart.
float eyeDist(float windowDepth) {
    float ndc = ProjMat[2][2] < 0.5 ? windowDepth : windowDepth * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

void main() {
    // Cutout: the in-phase glint got its silhouette from the EQUAL depth test (the surface only wrote depth
    // on opaque texels). The post-Iris re-render must alpha-test the model/atlas texture itself, so a
    // rectangular mesh (elytra wing, item sprite quad) rings only its real shape. White dummy => alpha 1.
    if (texture(Sampler1, cutoutCoord).a < 0.1) {
        discard;
    }

    // Per-fragment occlusion: reconstruct the scene's eye distance at this pixel and drop anything that
    // isn't the visible front surface (occluded geometry including the entity's OWN farther parts, or our
    // own back faces, cull is off). Slope-scaled bias: tight head-on, widening only at grazing edges.
    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;
    float sceneDist = eyeDist(sceneDepth);
    float bias = max(MIN_BIAS, SLOPE_BIAS * fwidth(viewDist));
    if (viewDist > sceneDist + bias) {
        discard;
    }

    // Identical to core/glint_color.fsh: grayscale design × per-vertex colour (ColorModulator stays white in
    // our path so it's dropped here). The design's alpha is the pattern SHAPE (discard), never the brightness;
    // the additive GLINT blend makes on-screen opacity = the emitted rgb, so dark design texels add nothing.
    vec4 color = texture(Sampler0, designCoord) * vertexColor;
    if (color.a < 0.004) {
        discard;
    }
    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(color.rgb * fade * vertexColor.a, color.a);
}
