#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints GUI item-outline fragment shader. Pairs with vanilla core/position_tex_color.vsh.
//
// Draws the inventory-icon glow as a MARGIN-item-pixel halo around the item silhouette, in the flat per-vertex
// Color (the glow colour fed by BlitRenderState). Uniform outward dilation: a fragment glows iff it is NOT on
// the icon but an item pixel lies within MARGIN item-pixels.
//
// Room to draw outside the icon: GuiRendererMixin blits this through a quad GROWN by MARGIN item-pixels on
// every side (scissor grown to match), UVs pinned to the item's atlas slot, so the icon appears at
// SCALE = (16 + 2*MARGIN)/16 inside the quad with a MARGIN-px border for the halo. Every sample maps back into
// the item's OWN slot (item-local coords clamped to [0,1]); off-item samples return empty, so the neighbouring
// icon is never read. Order-independent (discard-over-icon / draw-in-border), which works although the 26.1
// GUI sorts blits by pipeline, not submission order.
//
// The slot UV size s is computed EXACTLY from guiScale (the slot is always 16*guiScale atlas texels), NOT from
// fwidth or a packed pixel size. See the TRIED note: a drifting s was the real cause of the "cross-section"
// tearing, not the dilation itself.
//
// TRIED — do NOT repeat:
//   * Packing the quad's on-screen px size and recovering s via fwidth: the /4-encoded value OVERFLOWED 7 bits
//     on the zoomed wand preview and clamped to 127, so s was 15-37% wrong, floor(texCoord0/s) flipped mid-
//     icon, and the outline tore into CROSS LINES / diagonal shear. Fixed by deriving s from guiScale exactly.
//   * Scale-from-centre silhouette (enlarge the icon from its centre, mask with the real icon): on a thin
//     DIAGONAL item (sword) the radial scale runs ALONG the blade, so the long sides never stick out — the
//     "outline" collapsed to streaks at the ends. Radial scaling can't outline thin off-radial shapes.
//   * Inner-RIM (paint the icon's own outermost pixels): drew ON the texture, not around it. Looked awful.
//   * Round-disc neighbourhood for the dilation: DASHED 45-degree edges (the (1,1) diagonal drops out below
//     ~1.41 radius). Use the SQUARE (Chebyshev) neighbourhood below.

#include <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

// Halo thickness in ITEM pixels (an icon is 16 item-pixels wide). MUST equal GuiRendererMixin.OUTLINE_MARGIN
// — the mixin grows the blit quad by this much and SCALE is derived from it. This is the thickness knob.
const float MARGIN = 1.0;
const float SCALE  = (16.0 + 2.0 * MARGIN) / 16.0;   // expanded-quad : icon size ratio
// Actual halo thickness in item-pixels. <= MARGIN (the quad only has MARGIN px of room). Drop below 1.0 for a
// thinner outline without touching the quad geometry / GuiRendererMixin.OUTLINE_MARGIN.
const float THICKNESS = 0.6;
// Alpha threshold splitting "item" from "empty". ~0.2 sits at the visible silhouette edge.
const float EDGE = 0.2;

// guiScale is packed in the alpha byte by GuiRendererMixin (the slot is 16*guiScale atlas texels). Shared so
// itemAlpha and main agree on the slot geometry.
float gSlotSize(float guiScale) {
    return 16.0 * guiScale / float(textureSize(Sampler0, 0).x);   // slot UV size, EXACT
}

// Item alpha at an item-local coord (0..1 over the icon, v measured downward). 0 if off the item, so the
// dilation never reads past this slot into the neighbouring icon. origin = this slot's (u, vv) corner.
float itemAlpha(vec2 il, vec2 origin, float s) {
    if (il.x < 0.0 || il.x > 1.0 || il.y < 0.0 || il.y > 1.0) {
        return 0.0;
    }
    vec2 uv = vec2(origin.x + il.x * s, 1.0 - (origin.y + il.y * s));
    return texture(Sampler0, uv).a;
}

void main() {
    float guiScale = floor(vertexColor.a * 255.0 + 0.5);
    float s = gSlotSize(guiScale);

    // Locate this item's slot and our position within it. texCoord0 never leaves the slot (the quad's UVs ARE
    // the slot) and s is exact, so floor() gives the slot corner with no mid-icon flip. v grows down (encoded
    // 1 - k*s), so work in vv = 1 - texCoord0.y.
    float vv = 1.0 - texCoord0.y;
    vec2 origin = vec2(floor(texCoord0.x / s), floor(vv / s)) * s;
    vec2 local  = vec2((texCoord0.x - origin.x) / s, (vv - origin.y) / s);   // 0..1 across the EXPANDED quad

    // Map quad-local -> item-local: the icon occupies the centre 1/SCALE, leaving a MARGIN-px border.
    vec2 itemLocal = 0.5 + (local - 0.5) * SCALE;

    // Over the icon -> discard so it shows through (we draw only the surrounding halo).
    if (itemAlpha(itemLocal, origin, s) > EDGE) {
        discard;
    }

    // Outward dilation: glow iff any item pixel lies within MARGIN item-pixels. SQUARE (Chebyshev) scan in
    // one-item-pixel steps so diagonals stay continuous and thickness is uniform on all sides.
    float stepL = THICKNESS / 16.0;
    int M = int(MARGIN + 0.5);
    float found = 0.0;
    for (int ox = -M; ox <= M; ox++) {
        for (int oy = -M; oy <= M; oy++) {
            if (itemAlpha(itemLocal + vec2(float(ox), float(oy)) * stepL, origin, s) > EDGE) {
                found = 1.0;
                break;
            }
        }
        if (found > 0.5) break;
    }
    if (found < 0.5) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb, ColorModulator.a);
}
