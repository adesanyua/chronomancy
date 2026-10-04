#version 150

#moj_import <fog.glsl>

// Temporal Stasis: «застывшее золото». Цвет текстуры заменяется янтарно-золотой гаммой по яркости
// (детали и тени сохраняются), по краям — холодное голубое сияние остановленного времени, по телу
// медленно ползут тонкие голубые «часовые» полосы и мерцают редкие пиксельные искры.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float PhaseTime;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

const vec3 AMBER_DARK = vec3(0.24, 0.13, 0.04);
const vec3 AMBER      = vec3(0.62, 0.40, 0.12);
const vec3 GOLD       = vec3(0.93, 0.74, 0.32);
const vec3 PALE       = vec3(1.00, 0.95, 0.76);
const vec3 TIME_BLUE  = vec3(0.35, 0.68, 1.00);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 goldRamp(float t) {
    if (t < 0.3) return mix(AMBER_DARK, AMBER, t / 0.3);
    if (t < 0.7) return mix(AMBER, GOLD, (t - 0.3) / 0.4);
    return mix(GOLD, PALE, (t - 0.7) / 0.3);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec4 color = tex * vertexColor * ColorModulator;
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    lum = clamp(0.10 + 0.95 * pow(lum, 0.8), 0.0, 1.0);
    vec3 gold = goldRamp(lum);

    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float rim = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 2.2);
    vec2 ts = vec2(textureSize(Sampler0, 0));
    float lines = smoothstep(0.93, 1.0, sin(texCoord0.y * ts.y * 0.55 - PhaseTime * 1.2));
    float spark = step(0.992, hash(floor(texCoord0 * ts) + floor(PhaseTime * 3.0)));

    gold += TIME_BLUE * (0.40 * rim + 0.22 * lines) + vec3(0.6, 0.85, 1.0) * spark * 0.6;
    color.rgb = min(gold, vec3(1.0));
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
