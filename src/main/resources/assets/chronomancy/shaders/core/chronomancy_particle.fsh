#version 150

#moj_import <minecraft:fog.glsl>

// Тот же язык, что у оболочек и растворения сущностей («фазирование сквозь время»):
//  * переливание синий -> голубой -> золото, бегущее по времени и по пикселям спрайта;
//  * доля золота задаётся частицей (стазис — почти чистое золото, следы — почти чистая фаза);
//  * растворение по пикселям (шаг шума = пиксель атласа) со светящимся голубым краем;
//  * аддитивное свечение: яркость спрайта (серый канал атласа) = сила света.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float PhaseTime;
// 1 — медная пыль копий Rift (как их шейдер растворения), 0 — синий/золото.
uniform float Copper;

in float vertexDistance;
in vec2 texCoord0;
in vec4 params;

out vec4 fragColor;

const vec3 DEEP  = vec3(0.10, 0.28, 0.85);
const vec3 BLUE  = vec3(0.24, 0.55, 0.94);
const vec3 SKY   = vec3(0.66, 0.86, 1.00);
const vec3 GOLD  = vec3(1.00, 0.78, 0.30);
const vec3 GOLD_DARK = vec3(0.62, 0.36, 0.08);
const vec3 GOLD_SHINE = vec3(1.00, 0.95, 0.70);
const vec3 COPPER_LOW  = vec3(0.50, 0.23, 0.11);
const vec3 COPPER_MID  = vec3(0.78, 0.44, 0.21);
const vec3 COPPER_HIGH = vec3(1.00, 0.85, 0.60);

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

vec3 iridescent(float t) {
    t = fract(t);
    if (t < 0.25) return mix(DEEP, BLUE, t / 0.25);
    if (t < 0.50) return mix(BLUE, SKY, (t - 0.25) / 0.25);
    if (t < 0.75) return mix(SKY, GOLD, (t - 0.50) / 0.25);
    return mix(GOLD, DEEP, (t - 0.75) / 0.25);
}

void main() {
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    float hue = params.r;
    float dissolve = params.g;
    float gold = params.b;
    float alpha = params.a * ColorModulator.a;
    if (alpha < 0.004) {
        discard;
    }

    vec2 px = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    float n = hash(px + floor(hue * 97.0));
    if (n < dissolve) {
        discard;
    }

    float lum = tex.r;
    float shift = hue + PhaseTime * 0.35 + (px.x + px.y) * 0.035 + lum * 0.25;
    vec3 phase = iridescent(shift);
    vec3 goldCol = mix(GOLD_DARK, GOLD, smoothstep(0.2, 0.8, lum));
    goldCol = mix(goldCol, GOLD_SHINE, 0.25 + 0.25 * sin(PhaseTime * 6.0 + hue * 30.0 + px.x * 0.7));
    // золото ложится мозаикой по пикселям (а не смешением — синий + золото дали бы серый)
    float pick = hash(px * 1.7 + vec2(3.1, 7.9) + floor(hue * 53.0));
    vec3 color = mix(phase, goldCol, smoothstep(pick - 0.08, pick + 0.08, gold));
    if (Copper > 0.5) {
        vec3 cu = lum < 0.5 ? mix(COPPER_LOW, COPPER_MID, lum * 2.0) : mix(COPPER_MID, COPPER_HIGH, lum * 2.0 - 1.0);
        // лёгкий голубой отблеск фазы поверх меди
        color = mix(cu, phase, 0.18);
    }
    // ядро: самые яркие пиксели спрайта почти белые
    color = color * (0.45 + 0.75 * lum) + vec3(pow(lum, 4.0) * 0.35);

    // светящийся край растворения
    float edge = dissolve + 0.16;
    if (dissolve > 0.01 && n < edge) {
        float k = (edge - n) / 0.16;
        float flicker = 0.85 + 0.15 * sin(PhaseTime * 20.0 + n * 40.0);
        color = mix(vec3(0.25, 0.65, 1.0), vec3(0.85, 0.97, 1.0), k) * flicker;
    }

    float fog = linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(min(color, vec3(1.0)), alpha * tex.a * fog);
}
