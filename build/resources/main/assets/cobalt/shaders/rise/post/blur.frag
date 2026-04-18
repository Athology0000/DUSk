#version 150

in vec2 fragTexCoord;
uniform sampler2D u_diffuse_sampler;
uniform sampler2D u_other_sampler;
uniform vec2 u_texel_size;
uniform vec2 u_direction;
uniform float u_radius;
uniform float u_kernel[128];
out vec4 fragColor;

void main()
{
    vec2 uv = fragTexCoord;

    float alpha = texture(u_other_sampler, uv).a;
    if (u_direction.x == 0.0 && alpha == 0.0) {
        discard;
    }

    vec4 pixel_color = texture(u_diffuse_sampler, uv) * u_kernel[0];

    for (float f = 1; f <= u_radius; f++) {
        vec2 offset = f * u_texel_size * u_direction;
        pixel_color += texture(u_diffuse_sampler, uv - offset) * u_kernel[int(f)];
        pixel_color += texture(u_diffuse_sampler, uv + offset) * u_kernel[int(f)];
    }

    fragColor = vec4(pixel_color.rgb, u_direction.x == 0.0 ? alpha : 1.0);
}
