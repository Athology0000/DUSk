#version 150

in vec2 fragTexCoord;
uniform vec2 u_size;
uniform float u_radius;
uniform vec4 u_first_color;
uniform vec4 u_second_color;
uniform vec4 u_edges;
uniform int u_direction;
out vec4 fragColor;

void main(void)
{
    vec2 tex_coord = fragTexCoord;
    vec4 color = mix(u_first_color, u_second_color, u_direction > 0 ? tex_coord.y : tex_coord.x);

    if (tex_coord.x < 0.5 && tex_coord.y > 0.5 && u_edges.x == 0.0 ||
        tex_coord.x > 0.5 && tex_coord.y > 0.5 && u_edges.y == 0.0 ||
        tex_coord.x > 0.5 && tex_coord.y < 0.5 && u_edges.z == 0.0 ||
        tex_coord.x < 0.5 && tex_coord.y < 0.5 && u_edges.w == 0.0) {
        fragColor = color;
    } else {
        fragColor = vec4(color.rgb, color.a * smoothstep(1.0, 0.0, length(max((abs(tex_coord - 0.5) + 0.5) * u_size - u_size + u_radius, 0.0)) - u_radius + 0.5));
    }
}
