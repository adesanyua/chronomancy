#version 150

#moj_import <fog.glsl>

// Стена островка времени — цилиндр, родственный граням Accelerated Zone: светящаяся кромка у земли,
// по стене вверх несутся росчерки, редкая разметка по высоте. Отличия: это «колодец» — свет идёт от
// земли и тает к верхнему краю, по стене одно за другим поднимаются кольца, а палитра мятно-бирюзовая.
// Смешивание аддитивное.
//
// texCoord0 — в блоках: x вдоль окружности, y вверх от земли.
// Вершинный цвет несёт не цвет: g — высота в долях цилиндра (0 у земли .. 1 у верхнего края),
// b — доля оставшегося срока островка (1 .. 0), a — общая яркость.
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

const vec3 DEEP  = vec3(0.06, 0.60, 0.55);
const vec3 MINT  = vec3(0.48, 1.00, 0.82);
const vec3 WHITE = vec3(0.90, 1.00, 0.96);

float hash1(float n) {
    return fract(sin(n * 127.1) * 43758.5453);
}

void main() {
    vec4 tex = texture(Sampler0, vec2(0.5));
    if (tex.a < 0.1) {
        discard;
    }
    float height = vertexColor.g;
    float left = vertexColor.b;
    float t = PhaseTime;

    // кромки: яркая у земли, тонкая у верхнего края
    float foot = 1.0 - smoothstep(0.0, 0.09, texCoord0.y);
    float brim = 1.0 - smoothstep(0.0, 0.04, 1.0 - height);
    // колодец: свет идёт снизу и тает кверху
    float well = pow(1.0 - height, 1.6);
    float fade = 1.0 - smoothstep(0.55, 1.0, height);

    // росчерки по дорожкам, три на блок окружности, у каждой своя скорость
    float lanes = texCoord0.x * 3.0;
    float h = hash1(floor(lanes) + 31.0);
    float lane = 1.0 - smoothstep(0.05, 0.18, abs(fract(lanes) - 0.5));
    float y = fract(texCoord0.y * 0.40 - t * (0.55 + 1.1 * h) + h * 5.0);
    float comet = smoothstep(0.50, 0.96, y) * (1.0 - smoothstep(0.96, 1.0, y)) * lane * step(0.30, h);
    float head = smoothstep(0.88, 0.96, y) * (1.0 - smoothstep(0.96, 1.0, y)) * lane * step(0.30, h);

    // кольца, поднимающиеся по стене одно за другим
    float wave = fract(texCoord0.y * 0.75 - t * 0.45);
    float ring = (1.0 - smoothstep(0.0, 0.07, abs(wave - 0.5))) * 0.55;
    float tick = (1.0 - smoothstep(0.0, 0.03, abs(fract(texCoord0.y) - 0.5))) * 0.08;

    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float graze = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 2.0);

    // последние полторы секунды срока стена мигает: островок вот-вот закроется
    float ending = 1.0 - smoothstep(0.0, 0.05, left);
    float blink = mix(1.0, 0.45 + 0.55 * sin(t * 18.0), ending);

    float a = (0.04 + 0.20 * well + 0.14 * graze + (0.50 * comet + ring + tick) * fade + 0.85 * foot + 0.35 * brim)
            * blink * vertexColor.a * ColorModulator.a;
    vec3 col = mix(DEEP, MINT, clamp(foot + comet + ring + 0.30 * well + 0.25, 0.0, 1.0));
    col = mix(col, WHITE, clamp(head + foot * 0.5, 0.0, 1.0));
    a *= linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(col, clamp(a, 0.0, 1.0));
}
