#version 150

uniform vec2 resolution;
uniform float time;
out vec4 fragColor;

mat2 rot(float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return mat2(c, -s, s, c);
}

float map(vec3 point) {
    point.xz *= rot(time * 0.4);
    point.xy *= rot(time * 0.1);
    vec3 warped = point * 2.0 + time;
    return length(point + vec3(sin(time * 0.7))) * log(length(point) + 1.0)
        + sin(warped.x + sin(warped.z + sin(warped.y))) * 0.5 - 1.0;
}

void main()
{
    vec2 uv = gl_FragCoord.xy / resolution.y - vec2(0.9, 0.5);
    vec3 color = vec3(0.0);
    float distance_travelled = 2.5;

    for (int i = 0; i <= 5; i++) {
        vec3 point = vec3(0.0, 0.0, 4.0) + normalize(vec3(uv, -1.0)) * distance_travelled;
        float field = map(point);
        float glow = clamp((field - map(point + 0.1)) * 0.5, -0.1, 1.0);
        vec3 lighting = vec3(0.1, 0.3, 0.4) + vec3(5.0, 2.5, 3.0) * glow;
        color = color * lighting + smoothstep(2.5, 0.0, field) * 0.6 * lighting;
        distance_travelled += min(field, 1.0);
    }

    fragColor = vec4(color, 1.0);
}
