#version 120

uniform vec2 u_size;
uniform float u_radius;
uniform vec4 u_color;

float roundSDF(vec2 p, vec2 b, float r) {
    return length(max(abs(p) - b, 0.0)) - r;
}

void main() {
    // 计算纹理坐标相对于中心的位置
    vec2 center = gl_TexCoord[0].st * u_size - u_size * 0.5;
    // 计算边缘距离
    float distance = length(max(abs(center) - (u_size * 0.5 - u_radius - 1.0), 0.0)) - u_radius;
    // 使用smoothstep进行抗锯齿
    float alpha = 1.0 - smoothstep(0.0, 1.0, distance);
    // 设置片元颜色
    gl_FragColor = vec4(u_color.rgb, u_color.a * alpha);
}
