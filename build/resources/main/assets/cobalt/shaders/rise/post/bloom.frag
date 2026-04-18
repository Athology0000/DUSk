#version 150

in vec2 fragTexCoord;
uniform sampler2D u_diffuse_sampler;
uniform sampler2D u_other_sampler;
uniform vec2 u_texel_size;
uniform vec2 u_direction;
uniform float u_radius;
uniform float u_kernel[24];
out vec4 fragColor;

void main(void)
{
    vec2 uv = fragTexCoord;

    if (u_direction.x == 0.0) {
        if (texture(u_other_sampler, uv).a > 0.0) {
            discard;
        }
    }

    vec4 kernel = texture(u_diffuse_sampler, uv);
    vec4 pixel_color = kernel * u_kernel[0];

    pixel_color.rgb *= pixel_color.a;

    for (int f = 0; f <= int(u_radius); f++) {
        vec2 offset = (u_texel_size * u_direction) * f;

        vec4 left = texture(u_diffuse_sampler, uv - offset);
        vec4 right = texture(u_diffuse_sampler, uv + offset);

        left.rgb *= left.a;
        right.rgb *= right.a;

        pixel_color = pixel_color + (left + right) * u_kernel[f];
    }

    pixel_color.rgb /= pixel_color.a;

    fragColor = pixel_color;
}
