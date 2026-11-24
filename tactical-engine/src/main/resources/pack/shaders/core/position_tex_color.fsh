#version 330
#extension GL_ARB_separate_shader_objects : require

// Tactical: vanilla 26.3 position_tex_color.fsh plus marker colours (ported from an admin-supplied shader):
//   #25281E pixels are not drawn; #261A1C pixels become white at 12.5 % opacity.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec2 texCoord0;
layout(location = 1) in vec4 vertexColor;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
    if (color.a == 0.0) {
        discard;
    }
    ivec3 rgb = ivec3(round(color.rgb * 255.0));
    if (rgb == ivec3(37, 40, 30)) {
        discard;
    } else if (rgb == ivec3(38, 26, 28)) {
        color = vec4(1.0, 1.0, 1.0, 0.125);
    }
    fragColor = color * ColorModulator;
}
