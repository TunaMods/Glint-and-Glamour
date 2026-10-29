#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// Custom Glints glow-outline composite: PER-OBJECT id-aware, PER-CATEGORY thickness, single pass. The
// mask (core/glow_silhouette) packs a per-object KEY + visibility into alpha:
//   0        : empty (no silhouette here).
//   1..127   : IN-SHAPE but OCCLUDED, key = v.
//   128..255 : VISIBLE, key = v - 128.
// The key is (category << 5) | id: the top 2 bits are the object's category, the low 5 are its id. rgb is
// that object's glow colour.
//
// A pixel rings iff a VISIBLE silhouette of a DIFFERENT object (different id) is within that source's
// CATEGORY thickness. That rings the outer edge of every object PLUS the boundary wherever two objects overlap,
// and each category can have its own ring width. MaskSampler MUST be NEAREST (alpha is a packed classifier).
//
// Cost note: an in-shape interior pixel with no other object within reach scans the whole kernel before
// discarding (no cheap "deep interior" early-out). Fine for normal scenes; heavier under extreme entity
// crams. Mitigate with outlineRenderScale (downscale) or smaller THICKNESS values.

uniform sampler2D MaskSampler;
uniform sampler2D DepthSampler;       // scene depth, sampled at the RING pixel
uniform sampler2D MaskDepthSampler;   // the mask target's own depth, sampled at the SOURCE texel

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// Per-category outline thickness, in texels (≈ screen px at DOWNSCALE=1). EDIT to taste. Index = category:
//   [0] entity   [1] armor   [2] item (3rd-person held / dropped)   [3] held (first-person)
// SEARCH MUST be >= ceil(max of these). If you set any above 7, raise SEARCH to match AND raise
// EntityGlintRender.SCISSOR_PAD to >= that + ~4 so the thicker ring isn't clipped at the group rect edge.
// These are the FULL widths, applied at or nearer than REF_DIST; beyond it the ring narrows with distance.
const float THICKNESS[4] = float[](4.0, 4.0, 3.0, 7.0);
const int   SEARCH       = 7;             // ceil(max(THICKNESS))

// Distance-proportional thinning. A constant pixel-width ring looks FATTER the farther (smaller on screen)
// an object gets. To stop that, each source's reach scales by REF_DIST / itsDistance, clamped to [MIN_SCALE,1]:
// closer than REF_DIST keeps the full THICKNESS, farther narrows ∝ 1/distance so the ring tracks the object's
// apparent size. MIN_SCALE floors it so far rings don't vanish.
//
// The source distance comes from the MASK target's own depth, NOT the scene depth. A source texel sits on
// the silhouette RIM, and the scene depth there belongs to whatever the rasterizer resolved for that pixel,
// which along a rim is often the BACKGROUND behind the object rather than the object. (The mask's visibility
// test can't catch it: reading a far background depth still satisfies viewDist <= sceneDist.) So rim texels
// read a distance that flips between the object and the sky from texel to texel and frame to frame. Both
// things that consume srcDist then flip with it: the reach scale snaps between full and MIN_SCALE, and the
// ring-occlusion test below disqualifies whole rim segments. That is the outline WARPING and breaking up at
// range, when the silhouette is almost entirely rim. The mask target has its own depth attachment, cleared
// to far and written by GLOW_MASK_PIPE at exactly the texels it shades, so sampling it gives the object's
// true distance with no background bleed. Do not switch this back to DepthSampler.
const float REF_DIST  = 4.0;     // blocks; at/under this the ring stays full THICKNESS
const float MIN_SCALE = 0.40;    // never thinner than this fraction of THICKNESS (keeps a hairline at range)

// Ring occlusion bias, in BLOCKS. A source's ring skips any pixel whose scene geometry is at least this
// much nearer than the source edge the ring comes from. Applied to ALL categories: it keeps a farther
// object's ring from painting over a nearer object standing in front of it (two players side by side no
// longer bleed their outlines onto each other), and keeps a held/dropped item's halo off the hand gripping
// it. The ring expands outward from a visible edge regardless of what is in front of those outer pixels, so
// without this test the halo draws over whatever is nearer. Measured in blocks so it is distance-independent
// (same basis as core/glow_silhouette's OCCLUSION_BIAS).
const float RING_OCCLUSION_BIAS = 0.10;

// Edge softness, in texels. The ring is the union of a disc of radius r around every visible silhouette
// texel. Tested as a hard in/out (d2 > r*r), that union traces the silhouette's own stair-steps and scallops
// into a sawtooth wherever the edge runs diagonally. At range the distance thinning drops r to ~1.6 texels,
// so the scallop is as big as the band and the whole ring reads jagged. Fading the last texel of the reach
// makes the union a coverage field instead, and the pipeline blends TRANSLUCENT, so fractional alpha
// antialiases the outer boundary and the scallop with it. Larger blurs the ring, smaller brings the steps back.
const float EDGE_FEATHER = 1.0;

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
    vec2 texel = 1.0 / vec2(textureSize(MaskSampler, 0));
    int vP = int(texture(MaskSampler, texCoord).a * 255.0 + 0.5);
    int keyP = vP >= 128 ? vP - 128 : vP;   // this pixel's object key (category<<5 | id); 0 = empty

    // This RING pixel's own scene distance, for the ring-occlusion test below. Computed once (depends only on
    // texCoord, not on the source being scanned).
    float ringDist = eyeDist(texture(DepthSampler, texCoord).r);

    // NEAREST source wins. When two objects' outline bands overlap (a sheep behind a player's armored arm),
    // BOTH have a visible silhouette edge within reach of this pixel, so both want to ring it. Returning on
    // the FIRST edge found made an arbitrary one win, often the farther object (the sheep), whose ring then
    // painted OVER the nearer object's outline (the "back outline draws through my outline" report). The ring
    // bands sit in empty space between the two shapes, so the surface-depth occlusion test alone can't resolve
    // it. Instead scan the whole kernel and keep the source with the SMALLEST eye distance, so the front-most
    // object's outline always draws on top where two rings meet.
    float bestDist = 1.0e30;
    vec3 bestColor = vec3(0.0);
    bool found = false;
    // Ring coverage at this pixel: the largest fade any eligible source gives it (see EDGE_FEATHER). Taken
    // over every eligible source rather than only the colour winner, so where two rings meet the boundary
    // stays solid and only the outermost texel of the combined band fades.
    float coverage = 0.0;

    float maxR2 = float(SEARCH * SEARCH);
    for (int dx = -SEARCH; dx <= SEARCH; dx++) {
        for (int dy = -SEARCH; dy <= SEARCH; dy++) {
            float d2 = float(dx * dx + dy * dy);
            if (d2 > maxR2) continue;                           // outside the largest possible reach
            vec4 n = texture(MaskSampler, texCoord + vec2(float(dx), float(dy)) * texel);
            int vN = int(n.a * 255.0 + 0.5);
            if (vN < 128) continue;                             // visible silhouettes are the only sources
            int keyN = vN - 128;
            // Compare the ID (low 5 bits) only, NOT the category: an entity's body (CAT_ENTITY) and its
            // armor (CAT_ARMOR) share one id, so this skips the seam between them. They ring as ONE shape
            // instead of doubling where two pieces overlap. Distinct objects keep distinct ids and still ring.
            if ((keyN & 31) == (keyP & 31)) continue;           // same identity → not a boundary
            // Reconstruct this source's linear eye distance from the scene depth at its texel, then scale the
            // per-category reach so a far (small-on-screen) object gets a proportionally thinner ring.
            vec2 srcUV = texCoord + vec2(float(dx), float(dy)) * texel;
            float srcDepthRaw = texture(MaskDepthSampler, srcUV).r;
            float srcDist = eyeDist(srcDepthRaw);
            // A VISIBLE silhouette source can only sit on the cleared far plane (depth ~1.0) if its geometry
            // never wrote to the main depth buffer, i.e. the first-person held item, which under an Iris pack
            // is drawn into Iris's gbuffer, not the main target, so the composite samples the cleared far value
            // here. Without this guard srcDist → far → scale → MIN_SCALE and EVERY hand item rings as a thin
            // hairline (TRIED: routing held items through the hand-projection drain fixed the float but left
            // them thin — this is why). Held items are always close; keep them at full THICKNESS.
            //
            // Since srcDist moved to MaskDepthSampler this almost never fires: the mask writes its own depth
            // for the hand item too, and that reads as the real (very close) distance, which clamps to full
            // THICKNESS on its own. Kept as the fallback for any source whose mask depth went unwritten.
            // 26.2 is reversed-Z: the cleared far plane is depth 0.0, not the ~1.0 above.
            bool nearField = srcDepthRaw <= 0.000001;
            float scale = nearField ? 1.0 : clamp(REF_DIST / max(srcDist, 0.001), MIN_SCALE, 1.0);
            float r = THICKNESS[keyN >> 5] * scale;             // this source's distance-scaled reach
            // Coverage instead of an in/out test: full inside the reach, fading over the last texel.
            float cov = clamp((r - sqrt(d2)) / EDGE_FEATHER + 0.5, 0.0, 1.0);
            if (cov <= 0.0) continue;                           // outside THAT category's thickness
            // Occlude the ring where this RING pixel has nearer scene geometry than the source edge the ring
            // comes from, so a farther object's halo never paints over a nearer SURFACE (a held item over the
            // gripping hand, a mob behind a wall). See RING_OCCLUSION_BIAS.
            if (ringDist < srcDist - RING_OCCLUSION_BIAS) continue;  // nearer geometry here → this source can't ring
            // Keep the FRONT-most eligible source. Near-field sources (cleared depth, e.g. the FP hand item)
            // are treated as closest so they win over a distant world object.
            float cmpDist = nearField ? 0.0 : srcDist;
            coverage = max(coverage, cov);
            if (cmpDist < bestDist) { bestDist = cmpDist; bestColor = n.rgb; found = true; }
        }
    }
    if (found) {
        fragColor = vec4(bestColor, coverage);
        return;
    }
    discard;
}
