#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

// Custom Glints PROCEDURAL CHROMATIC overlay fragment shader, LOOSE-occlusion variant for TRANSLUCENT
// entity-layer shells (the slime outer cube). Identical to core/chromatic_overlay.fsh except for the
// occlusion bias, and it is the chromatic twin of core/glint_overlay_loose.fsh.
//
// Why a separate variant: a translucent shell's depth in the committed scene buffer is unstable. Under a
// shader pack Iris re-sorts translucent geometry every frame, so the shell's own window depth flips with
// camera angle (and may not be written to the main depth at all, only the opaque geometry behind it is).
// The slope-scaled bias of the standard variant (MIN_BIAS ~0.015) is far smaller than that per-frame
// wobble, so the shell self-occludes and the slick drops out on some faces at some angles. The design
// glint hit this first and got the loose variant; chromatic kept the tight test and kept dropping out.
//
// The shell is a small convex NO_CULL hull, so fine self-occlusion of it is not wanted (a little back-face
// slick inside a translucent blob is invisible). Only CLEARLY nearer OPAQUE geometry should occlude it,
// hence one flat generous blocks-bias with no fwidth slope term.

uniform sampler2D Sampler0;     // the model texture, drives the cutout alpha-test
uniform sampler2D Sampler1;     // palette strip: 1px tall, one texel per colour
uniform sampler2D DepthSampler; // full-res scene depth (main target)

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec2 noiseCoord;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in vec4 vertexColor;
layout(location = 5) in float vSeed;
layout(location = 6) in float vMorph;
layout(location = 7) in float vCount;
layout(location = 8) in float viewDist;
layout(location = 9) in vec4 screenPos;

layout(location = 0) out vec4 fragColor;

const float DENSITY = 14.0; // noise cells across one UV unit, global chromatic fineness knob (keep in sync
                            // with chromatic/chromatic_overlay/chromatic_block/gui_chromatic)

// Flat occlusion tolerance in BLOCKS of linear view distance (distance-independent), same value the design
// glint's loose variant uses: large enough to absorb the shell's re-sorted-depth wobble, small enough that
// opaque geometry a fraction of a block in front still occludes the shell.
const float OCCLUSION_BIAS = 0.10;

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
    if (texture(Sampler0, texCoord0).a < 0.1) {
        discard;
    }

    vec2 uv = (screenPos.xy / screenPos.w) * 0.5 + 0.5;
    float sceneDepth = texture(DepthSampler, uv).r;
    float sceneDist = eyeDist(sceneDepth);
    if (viewDist > sceneDist + OCCLUSION_BIAS) {
        discard;
    }

    float t = GameTime * 5000.0 * max(0.05, vMorph);
    vec2 uvn = noiseCoord * DENSITY;
    vec2 so = vec2(vSeed * 3.1, vSeed * 6.7);

    int n = int(vCount + 0.5);

    float n1 = fbm(uvn + so + vec2(t * 0.10, -t * 0.07));
    float n2 = fbm(uvn * 1.7 + so.yx - vec2(t * 0.06, t * 0.04));

    vec3 col = cgChroma(n, n1, n2, t);
    float bright = 0.7 + 0.3 * n2;

    float fade = (1.0 - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd)) * GlintAlpha;
    fragColor = vec4(col * bright * fade * vertexColor.a, vertexColor.a);
}
