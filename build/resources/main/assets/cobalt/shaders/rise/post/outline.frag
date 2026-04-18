#version 150

in vec2 fragTexCoord;
uniform sampler2D u_texture;
uniform vec2 u_texel_size;
uniform float u_radius;
uniform vec4 u_color;
out vec4 fragColor;

void main(void)
{
    vec4 i_color;
    vec2 tex_coord = fragTexCoord;
    vec4 p_color = texture(u_texture, tex_coord);

    if (p_color.a > 0.0) {
        discard;
    }

    for (float i = -u_radius; i <= u_radius; ++i) {
        for (float j = -u_radius; j <= u_radius; ++j) {
            i_color = texture(u_texture, tex_coord + vec2(i, j) * u_texel_size);
            if (i_color.a > 0.0) {
                fragColor = u_color;
                return;
            }
        }
    }

    discard;
}
