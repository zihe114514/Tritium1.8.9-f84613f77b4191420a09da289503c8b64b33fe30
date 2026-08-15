#version 120

uniform vec2 u_size;
uniform float u_radius;
uniform float u_border_size;
uniform vec4 u_color_1;
uniform vec4 u_color_2;
uniform vec4 u_color_3;
uniform vec4 u_color_4;

#define NOISE .5/255.0

vec3 createGradient(vec2 coords, vec3 color1, vec3 color2, vec3 color3, vec3 color4){
    vec3 color = mix(mix(color1.rgb, color2.rgb, coords.y), mix(color3.rgb, color4.rgb, coords.y), coords.x);
    color += mix(NOISE, -NOISE, fract(sin(dot(coords.xy, vec2(12.9898, 78.233))) * 43758.5453));
    return color;
}

void main(void)
{
    float a = gl_TexCoord[0].s * 0.5 + gl_TexCoord[0].t * 0.5;
    float b = abs(1. - a * 2.);
    vec4 color = mix(u_color_1, u_color_2, b);

    vec2 position = (abs(gl_TexCoord[0].st - 0.5) + 0.5) * u_size;
    float distance = length(max(position - u_size + u_radius + u_border_size, 0.0)) - u_radius + 0.5;

    gl_FragColor = vec4(createGradient(gl_TexCoord[0].st, u_color_1.rgb, u_color_2.rgb, u_color_3.rgb, u_color_4.rgb), color.a * (smoothstep(0.0, 1.0, distance) - smoothstep(0.0, 1.0, distance - u_border_size)));
}