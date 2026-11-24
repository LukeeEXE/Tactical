#version 330
#extension GL_ARB_separate_shader_objects : require

// Tactical: vanilla 26.3 position_tex_color.vsh plus marker-colour handling (ported from an admin-supplied shader).
// Sprites drawn with this shader (GUI textures) whose texture pixel at a quad corner is the marker colour
// #261A1C are moved 5 px left (see the fragment shader for what the markers do).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

uniform sampler2D Sampler0;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;

layout(location = 0) out vec2 texCoord0;
layout(location = 1) out vec4 vertexColor;

const vec2 corners[4] = vec2[4](vec2(1.0, 0.0), vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

vec4 cornerTexel(int vertexID, vec2 coords) {
    vec2 size = 1.0 / vec2(textureSize(Sampler0, 0));
    return textureLod(Sampler0, coords - (corners[vertexID % 4] - 0.5) * size, 0.0);
}

void main() {
    texCoord0 = UV0;
    vertexColor = Color;
    vec4 c = cornerTexel(gl_VertexID, UV0);
    ivec3 rgb = ivec3(round(c.rgb * 255.0));
    vec3 offset = vec3(0.0);
    if (rgb == ivec3(38, 26, 28) || rgb == ivec3(37, 40, 30)) offset = vec3(-5.0, 0.0, 0.0);
    gl_Position = ProjMat * ModelViewMat * vec4(Position + offset, 1.0);
}
