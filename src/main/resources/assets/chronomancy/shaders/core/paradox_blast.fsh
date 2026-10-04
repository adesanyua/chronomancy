#version 150

#moj_import <fog.glsl>

// Оболочки вспышки временного парадокса (золотая сфера поля, голубой куб зоны, белое ядро).
// Вершинный цвет — оттенок оболочки, альфа — её яркость. Узор идёт рывками: время то несётся,
// то вязнет; по оболочке вспыхивают «трещины». Смешивание аддитивное.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float PhaseTime;

in float vertexDistance;
in vec4 vertexColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, fract(texCoord0));
    if (tex.a < 0.1) {
        discard;
    }
    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float rim = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 1.4);

    float t = PhaseTime;
    // рывок: за треть секунды узор проскакивает вперёд и замирает
    float jerk = floor(t * 3.0) + smoothstep(0.0, 0.25, fract(t * 3.0));
    float bands = 0.5 + 0.5 * sin(texCoord0.y * 26.0 - jerk * 5.0 + texCoord0.x * 9.0);
    // искры-трещины: мелкие клетки, вспыхивающие вразнобой
    float crack = step(0.972, hash(floor(texCoord0 * 44.0) + vec2(floor(t * 9.0))));

    // у куба (его UV сдвинуты в [2..3]) светятся рёбра граней; у сферы рёбер нет
    float cubeFace = step(1.5, texCoord0.x);
    vec2 f = fract(texCoord0);
    float edgeDist = min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y));
    float edge = cubeFace * (1.0 - smoothstep(0.0, 0.07, edgeDist));

    float a = (0.08 + 0.85 * rim + 0.30 * bands * rim + 0.45 * crack + 0.80 * edge)
            * vertexColor.a * ColorModulator.a;
    vec3 col = vertexColor.rgb * (0.8 + 0.6 * bands) + vec3(0.55) * crack + vec3(0.35) * edge;
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, clamp(a, 0.0, 1.0));
}
