#version 150

#moj_import <fog.glsl>

// Грань куба Accelerated Zone. Противоположность плавному «пузырю» поля: светящиеся рёбра,
// по граням вверх несутся росчерки времени, редкая разметка по высоте. Смешивание аддитивное.
// Вершинный цвет несёт не цвет: r,g — координаты внутри грани (0..1), a — общая яркость.
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

const vec3 DEEP  = vec3(0.10, 0.35, 1.00);
const vec3 SKY   = vec3(0.55, 0.90, 1.00);
const vec3 WHITE = vec3(0.92, 0.98, 1.00);

float hash1(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, fract(texCoord0));
    if (tex.a < 0.1) {
        discard;
    }
    vec2 face = vertexColor.rg;
    float edgeDist = min(min(face.x, 1.0 - face.x), min(face.y, 1.0 - face.y));
    float edge = 1.0 - smoothstep(0.0, 0.05, edgeDist);

    // texCoord0 — в блоках: x вдоль грани, y вверх. Дорожки по три на блок, у каждой своя скорость.
    float t = PhaseTime;
    float lanes = texCoord0.x * 3.0;
    float h = hash1(floor(lanes) + 17.0);
    float lane = 1.0 - smoothstep(0.05, 0.18, abs(fract(lanes) - 0.5));
    float y = fract(texCoord0.y * 0.35 - t * (0.9 + 1.8 * h) + h * 5.0);
    float comet = smoothstep(0.55, 0.96, y) * (1.0 - smoothstep(0.96, 1.0, y)) * lane * step(0.35, h);
    float head = smoothstep(0.88, 0.96, y) * (1.0 - smoothstep(0.96, 1.0, y)) * lane * step(0.35, h);
    float tick = (1.0 - smoothstep(0.0, 0.03, abs(fract(texCoord0.y) - 0.5))) * 0.10;

    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float graze = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 2.0);

    float a = (0.05 + 0.16 * graze + 0.75 * edge + 0.55 * comet + tick) * vertexColor.a * ColorModulator.a;
    vec3 col = mix(DEEP, SKY, clamp(edge + comet + 0.30, 0.0, 1.0));
    col = mix(col, WHITE, head);
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, clamp(a, 0.0, 1.0));
}
