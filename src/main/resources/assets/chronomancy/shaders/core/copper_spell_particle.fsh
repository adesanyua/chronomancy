#version 150

#moj_import <minecraft:fog.glsl>

// Медь по яркости: цвет частицы (текстура * её собственный цвет) сводится к яркости, а яркость
// раскладывается по медной палитре — огонь, снег, яд и молнии становятся одного, медного, цвета,
// но сохраняют свой рисунок и прозрачность.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform float PhaseTime;

in float vertexDistance;
in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const vec3 COPPER_DARK  = vec3(0.26, 0.11, 0.05);
const vec3 COPPER_LOW   = vec3(0.50, 0.23, 0.11);
const vec3 COPPER_MID   = vec3(0.78, 0.44, 0.21);
const vec3 COPPER_HIGH  = vec3(0.96, 0.70, 0.42);
const vec3 COPPER_SHINE = vec3(1.00, 0.90, 0.70);

vec3 copperRamp(float t) {
    if (t < 0.25) return mix(COPPER_DARK, COPPER_LOW, t / 0.25);
    if (t < 0.55) return mix(COPPER_LOW, COPPER_MID, (t - 0.25) / 0.30);
    if (t < 0.85) return mix(COPPER_MID, COPPER_HIGH, (t - 0.55) / 0.30);
    return mix(COPPER_HIGH, COPPER_SHINE, (t - 0.85) / 0.15);
}

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < 0.1) {
        discard;
    }
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    // подъём тёмных тонов: тёмные частицы (дым, яд) остаются читаемо медными
    lum = clamp(0.22 + 0.85 * pow(lum, 0.8), 0.0, 1.0);
    vec3 copper = copperRamp(lum);

    // живой металлический отблеск, бегущий по пикселям спрайта
    vec2 px = floor(texCoord0 * vec2(textureSize(Sampler0, 0)));
    float sweep = 0.5 + 0.5 * sin(PhaseTime * 7.0 + (px.x + px.y) * 0.6);
    copper += COPPER_SHINE * 0.12 * sweep * lum;

    float fog = linear_fog_fade(vertexDistance, FogStart, FogEnd);
    fragColor = vec4(min(copper, vec3(1.0)), color.a * fog);
}
