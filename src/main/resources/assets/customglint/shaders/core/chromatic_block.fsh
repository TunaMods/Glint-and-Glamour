#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>

// Custom Glints PROCEDURAL CHROMATIC fragment shader for BLOCK-model entity layers. Identical to
// core/chromatic.fsh except it first alpha-tests the block texture (Sampler2) at the raw block UV and
// discards transparent texels, so the oil-slick is masked to the block model's cutout shape (mooshroom
// mushrooms, snow-golem pumpkin) instead of covering the whole quad plane. 0.1 matches the block sheet's
// ALPHA_CUTOUT. Sampler1 stays the palette strip; Sampler0 (the inherited white dummy) is unused.

uniform sampler2D Sampler1; // palette strip: 1px tall, one texel per colour
uniform sampler2D Sampler2; // block atlas (cutout mask)

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 noiseCoord;
layout(location = 3) in vec2 texCoordMask;
layout(location = 4) in vec4 vertexColor;
layout(location = 5) in float vSeed;
layout(location = 6) in float vMorph;
layout(location = 7) in float vCount;

layout(location = 0) out vec4 fragColor;

const float DENSITY = 14.0; // noise cells across one UV unit — global chromatic fineness knob (keep in sync
                            // with chromatic/chromatic_overlay/gui_chromatic so armor/item/GUI match)

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
    if (texture(Sampler2, texCoordMask).a < 0.1) {
        discard; // transparent part of the block texture → no chromatic here
    }
    float t = GameTime * 5000.0 * max(0.05, vMorph);
    vec2 uv = noiseCoord * DENSITY;
    vec2 so = vec2(vSeed * 3.1, vSeed * 6.7);

    int n = int(vCount + 0.5);

    float n1 = fbm(uv + so + vec2(t * 0.10, -t * 0.07));
    float n2 = fbm(uv * 1.7 + so.yx - vec2(t * 0.06, t * 0.04));

    vec3 col = cgChroma(n, n1, n2, t);
    float bright = 0.7 + 0.3 * n2;

    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(col * bright * fade * vertexColor.a, vertexColor.a);
}
