#version 150

in vec2 fragTexCoord;
uniform sampler2D u_diffuse_sampler;
uniform float u_alpha;
out vec4 fragColor;

void main(void)
{
    vec2 tex_coord = fragTexCoord;
    vec4 pixel_color = texture(u_diffuse_sampler, tex_coord);
    if (pixel_color.a == 0.0) {
        discard;
    }

    fragColor = vec4(pixel_color.rgb, u_alpha);
}
