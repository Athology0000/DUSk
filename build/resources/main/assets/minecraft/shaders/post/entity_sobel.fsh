#version 330

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

in vec2 texCoord;
out vec4 fragColor;

void main(){
    vec2 oneTexel = 1.0 / InSize;

    vec4 center = texture(InSampler, texCoord);
    vec4 left   = texture(InSampler, texCoord - vec2(oneTexel.x, 0.0));
    vec4 right  = texture(InSampler, texCoord + vec2(oneTexel.x, 0.0));
    vec4 up     = texture(InSampler, texCoord - vec2(0.0, oneTexel.y));
    vec4 down   = texture(InSampler, texCoord + vec2(0.0, oneTexel.y));

    // Fill the entire entity silhouette (not just edges) so the blur creates a thick halo
    float maxAlpha = max(max(center.a, left.a), max(right.a, max(up.a, down.a)));

    // Resolve color from whichever neighbor is opaque
    vec3 color = center.rgb;
    if (center.a < 0.1) {
        float neighbourSum = left.a + right.a + up.a + down.a;
        if (neighbourSum > 0.001) {
            color = (left.rgb * left.a + right.rgb * right.a + up.rgb * up.a + down.rgb * down.a) / neighbourSum;
        }
    }

    fragColor = vec4(color, maxAlpha);
}
