#version 330
#extension GL_ARB_separate_shader_objects : require

// "Smoother Glowing" v1.5.1 (26.3 variant), made strictly GLSL 3.30 conformant for the Tactical pack:
// globals initialised from uniforms (only accepted by lenient drivers) were moved into main(), and all
// int/float mixing is explicit.

// radius of the glow
#define RADIUS 3.0

// the final alpha of the area covering the entity
#define FINAL_ALPHA 0.1

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
};

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 sampleStep = (1.0 / InSize) * BlurDir;
    float gradient = 1.0 / (RADIUS + 1.0);
    bool isFinal = sampleStep.y != 0.0;
    float finalAlpha = isFinal ? FINAL_ALPHA : 1.0;
    float additionalAlpha = isFinal ? gradient : 0.0;

    vec4 center = texture(InSampler, texCoord);
    if (center.a >= 1.0) {
        // opaque pixel: the entity itself
        fragColor = vec4(center.rgb, finalAlpha);
        return;
    }

    vec3 blurs = vec3(0.0);   // accumulated colours
    float totalColor = 0.0;   // number of pixels accumulated
    float alpha = center.a;   // output alpha
    if (alpha > 0.0) {
        totalColor = 1.0;
        blurs = center.rgb;
    }

    // from far to close
    for (int i = 3; i > 0; i--) {
        float r = float(i);
        vec2 stepValue = sampleStep * r;
        vec4 sampleL = texture(InSampler, texCoord - stepValue);
        vec4 sampleR = texture(InSampler, texCoord + stepValue);
        if (sampleL.a > 0.0) {
            totalColor += 1.0;
            blurs += sampleL.rgb;
            alpha = max(alpha, sampleL.a - r * gradient);
        }
        if (sampleR.a > 0.0) {
            totalColor += 1.0;
            blurs += sampleR.rgb;
            alpha = max(alpha, sampleR.a - r * gradient);
        }
    }

    fragColor = alpha > 0.0 ? vec4(blurs / totalColor, alpha + additionalAlpha) : vec4(0.0);
}
