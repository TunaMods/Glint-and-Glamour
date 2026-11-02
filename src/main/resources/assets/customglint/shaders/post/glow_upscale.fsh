#version 330

// Custom Glints: upscales the reduced-res outline ring onto the main target. Passthrough: the bilinear
// sampler does the upscale, which also softens (anti-aliases) the ring. Blends over the
// scene (ring pixels carry alpha=1, everything else is the cleared 0).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    fragColor = texture(InSampler, texCoord);
}
