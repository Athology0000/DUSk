#version 150

in vec2 fragTexCoord;
uniform sampler2D u_texture;
uniform float u_alpha;
out vec4 fragColor;

void main()
{
    vec4 color = texture(u_texture, fragTexCoord);
    fragColor = vec4(color.rgb, color.a * u_alpha);
}
