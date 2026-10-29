#version 330
#extension GL_ARB_separate_shader_objects : require

// Custom Glints GUI procedural-chromatic fragment shader. Same oil-slick as core/chromatic.fsh, but the
// noise runs over the ITEM-LOCAL coords reconstructed from the cached atlas slot (Sampler0 = the slot, used
// as the silhouette mask), and the palette + payload come from Sampler1 + the per-vertex attributes instead
// of TextureMat. Drawn as a GLYPH with BlendFunction.GLINT so it sits on top of the opaque icon and matches
// the world glint's additive look. See GuiRendererMixin.cg_itemGlintOverlay / GuiItemChromaticRenderState.

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:globals.glsl>

uniform sampler2D Sampler0;   // cached item slot, the silhouette mask
uniform sampler2D Sampler1;   // palette strip (1px tall, one texel per colour)

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in float vGuiScale;
layout(location = 3) in float vSeed;
layout(location = 4) in float vCount;
layout(location = 5) in float vPS;
layout(location = 6) in float vSpeed;

layout(location = 0) out vec4 fragColor;

const float EDGE = 0.1;
const float DENSITY = 14.0; // global chromatic fineness knob — keep in sync with
                            // chromatic/chromatic_overlay/chromatic_block so armor/item/GUI match

float hash(vec2 p) {
    p = fract(p * vec2(127.31, 311.7));
    p += dot(p, p + 41.27);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int o = 0; o < 4; o++) {
        v += amp * vnoise(p);
        p *= 2.03;
        amp *= 0.5;
    }
    return v;
}

vec3 hue(float h) {
    vec3 p = abs(fract(vec3(h) + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return clamp(p - 1.0, 0.0, 1.0);
}

// Full-coverage slick colour (rainbow when no palette, else a continuous wrapping sweep through it). No gaps.
vec3 cgChroma(int n, float n1, float n2, float t) {
    if (n <= 0) {
        return hue(fract(n1 * 1.6 + n2 * 0.4 + t * 0.02));
    }
    float fpos = fract(n1 * 1.3 + n2 * 0.3 + t * 0.02) * float(n);
    int i0 = int(floor(fpos)) % n;
    int i1 = (i0 + 1) % n;
    vec3 c0 = texelFetch(Sampler1, ivec2(i0, 0), 0).rgb;
    vec3 c1 = texelFetch(Sampler1, ivec2(i1, 0), 0).rgb;
    return mix(c0, c1, fract(fpos));
}

void main() {
    // Item-local (0..1 over the 16px icon, v measured downward) from the atlas slot coord. Same scheme as
    // gui_item_glint, slot UV size s derived exactly from guiScale.
    float s = 16.0 * vGuiScale / float(textureSize(Sampler0, 0).x);
    float vv = 1.0 - texCoord0.y;
    vec2 origin = vec2(floor(texCoord0.x / s), floor(vv / s)) * s;
    vec2 itemLocal = vec2((texCoord0.x - origin.x) / s, (vv - origin.y) / s);

    if (texture(Sampler0, texCoord0).a < EDGE) {
        discard;
    }

    float t = GameTime * 5000.0 * max(0.05, vSpeed); // matches core/chromatic.fsh (× speed)
    // vPS arrives pre-scaled by the flat-item match factor (1/2, see GuiRendererMixin), so the low-scale
    // floor is scaled to match (0.05/2). Keeps sub-1 patternScales from collapsing to one cell.
    // Scale about the icon CENTRE, matching GlintPipelines.chromaticMatrix. Scaling raw itemLocal pins the
    // noise to the icon's corner, so the scale knob grew the slick out of that corner instead of in place.
    vec2 uv = ((itemLocal - 0.5) * max(0.025, vPS) + 0.5) * DENSITY;
    vec2 so = vec2(vSeed * 3.1, vSeed * 6.7);

    int n = int(vCount + 0.5);

    // Continuous full-coverage oil-slick (no thresholded blobs / no gaps), matching core/chromatic.fsh.
    float n1 = fbm(uv + so + vec2(t * 0.10, -t * 0.07));
    float n2 = fbm(uv * 1.7 + so.yx - vec2(t * 0.06, t * 0.04));
    vec3 col = cgChroma(n, n1, n2, t);
    float bright = 0.7 + 0.3 * n2;

    fragColor = vec4(col * bright * GlintAlpha * vertexColor.a, vertexColor.a);
}
