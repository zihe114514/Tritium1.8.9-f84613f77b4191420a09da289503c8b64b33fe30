#version 120

uniform sampler2D u_diffuse_sampler;
uniform sampler2D u_other_sampler;
uniform vec2 u_texel_size;
uniform vec2 u_direction;
uniform float u_radius;
uniform float u_kernel[128];

void main(void)
{
    vec2 uv = gl_TexCoord[0].st;

    if (u_direction.x == 0.0) {
        float alpha = texture2D(u_other_sampler, uv).a;
        if (alpha > 0.0) discard;
    }

    vec4 pixel_color = texture2D(u_diffuse_sampler, uv);
    pixel_color.rgb *= pixel_color.a;

    pixel_color *= u_kernel[0];

    for (float f = 1.0; f <= u_radius; f++) {
        vec2 offset = f * u_texel_size * u_direction;
        vec4 smpl = texture2D(u_diffuse_sampler, uv + offset) + texture2D(u_diffuse_sampler, uv - offset);
        smpl.rgb *= smpl.a;
        pixel_color += smpl * u_kernel[int(f)];
    }

    gl_FragColor = vec4(pixel_color.rgb / pixel_color.a, pixel_color.a);
}