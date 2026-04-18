#version 150

in vec2 fragTexCoord;
uniform sampler2D u_texture;
uniform vec2 u_texelSize;
uniform float u_time;
out vec4 fragColor;

float hash(float c) { return fract(sin(c * 12.9898) * 43758.5453); }

const float W = 1.2;
const float T2 = 7.5;
const int N = 8;

float filmic_reinhard_curve(float x) {
    float q = (T2 * T2 + 1.0) * x * x;
    return q / (q + x + T2 * T2);
}

vec3 filmic_reinhard(vec3 x) {
    float w = filmic_reinhard_curve(W);
    return vec3(
        filmic_reinhard_curve(x.r),
        filmic_reinhard_curve(x.g),
        filmic_reinhard_curve(x.b)
    ) / w;
}

vec3 ca(sampler2D textureSampler, vec2 uv, vec4 sample_color) {
    vec2 centered = 1.0 - 2.0 * uv;
    vec3 color = vec3(0.0);
    float red_factor = 1.0;
    float green_factor = 1.0;
    float blue_factor = 1.0;
    float weight = 1.0 / float(N);
    for (int i = 0; i < N; ++i) {
        color.r += weight * texture(textureSampler, 0.5 - 0.5 * (centered * red_factor)).r;
        color.g += weight * texture(textureSampler, 0.5 - 0.5 * (centered * green_factor)).g;
        color.b += weight * texture(textureSampler, 0.5 - 0.5 * (centered * blue_factor)).b;
        red_factor *= 0.9972;
        green_factor *= 0.998;
        blue_factor /= 0.9988;
        color = clamp(color, 0.0, 1.0);
    }
    return color;
}

void main()
{
    const float brightness = 1.0;
    vec2 uv = fragTexCoord;
    vec2 centered = 1.0 - 2.0 * uv;
    centered.y *= u_texelSize.x / u_texelSize.y;
    vec4 sample_color = texture(u_texture, uv);
    vec3 color = ca(u_texture, uv, sample_color).rgb;
    float vignette = 1.25 / (1.1 + 1.1 * dot(centered, centered));
    vignette *= vignette;
    vignette = mix(1.0, smoothstep(0.1, 1.1, vignette), 0.25);
    float noise = 0.012 * vec3(hash(length(centered) * u_time)).x;
    color = color * vignette + noise;
    color = filmic_reinhard(brightness * color);
    color = smoothstep(-0.025, 1.0, color);
    color.r = color.b;
    color.b = 0.53;
    color.rgb *= 1.2;
    color = pow(color, vec3(1.0 / 2.2));
    fragColor = vec4(color, 1.0);
}
