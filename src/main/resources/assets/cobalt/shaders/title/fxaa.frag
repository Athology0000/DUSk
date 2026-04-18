#version 150

in vec2 fragTexCoord;
uniform sampler2D u_texture;
uniform vec2 u_texelSize;
out vec4 fragColor;

void main() {
    vec2 p = fragTexCoord;
    vec2 pp = u_texelSize;
    vec4 color = texture(u_texture, p);
    vec3 luma = vec3(0.299, 0.587, 0.114);
    float lumaNW = dot(texture(u_texture, p + vec2(-1.0, -1.0) * pp).xyz, luma);
    float lumaNE = dot(texture(u_texture, p + vec2( 1.0, -1.0) * pp).xyz, luma);
    float lumaSW = dot(texture(u_texture, p + vec2(-1.0,  1.0) * pp).xyz, luma);
    float lumaSE = dot(texture(u_texture, p + vec2( 1.0,  1.0) * pp).xyz, luma);
    float lumaM  = dot(color.xyz, luma);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    vec2 dir = vec2(
        -((lumaNW + lumaNE) - (lumaSW + lumaSE)),
         ((lumaNW + lumaSW) - (lumaNE + lumaSE)));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 / 8.0), 1.0 / 128.0);
    float rcpDirMin = 2.5 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = min(vec2(8.0), max(vec2(-8.0), dir * rcpDirMin)) * pp;
    vec3 rgbA = 0.5 * (
        texture(u_texture, p + dir * (1.0 / 3.0 - 0.5)).xyz +
        texture(u_texture, p + dir * (2.0 / 3.0 - 0.5)).xyz);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        texture(u_texture, p + dir * -0.5).xyz +
        texture(u_texture, p + dir *  0.5).xyz);
    float lumaB = dot(rgbB, luma);
    if (lumaB < lumaMin || lumaB > lumaMax) {
        fragColor = vec4(rgbA, color.w);
    } else {
        fragColor = vec4(rgbB, color.w);
    }
}
