#version 150

#moj_import <fog.glsl>

// Медные копии: цвет текстуры заменяется медью по яркости (детали и тени сохраняются),
// плюс лёгкий металлический отблеск по краям. Вспышка урона/взрыва и освещение — как в ванили.
uniform sampler2D Sampler0;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float GameTime;
// Доля меди: 1 — медная копия целиком; меньше — цель, которую постепенно заносит песком времени.
uniform float CopperAmount;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewNormal;
in vec3 viewPos;

out vec4 fragColor;

const vec3 COPPER_DARK  = vec3(0.20, 0.08, 0.04);
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
    vec4 tex = texture(Sampler0, texCoord0);
    if (tex.a < 0.1) {
        discard;
    }
    vec4 color = tex * vertexColor * ColorModulator;

    // Яркость с подъёмом тёмных тонов: даже чёрные мобы остаются читаемо медными.
    float lum = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    lum = clamp(0.12 + 0.95 * pow(lum, 0.85), 0.0, 1.0);
    vec3 copper = copperRamp(lum);

    // Металлический отблеск: ярче на гранях, повёрнутых к камере под углом, и медленно «бегущий» блик.
    vec3 n = normalize(viewNormal);
    vec3 v = normalize(-viewPos);
    float rim = pow(1.0 - clamp(abs(dot(n, v)), 0.0, 1.0), 2.0);
    float sweep = 0.5 + 0.5 * sin(GameTime * 2400.0 + (viewPos.y + viewPos.x) * 1.7);
    copper += COPPER_SHINE * (0.22 * rim + 0.06 * sweep * lum);

    color.rgb = mix(color.rgb, min(copper, vec3(1.0)), clamp(CopperAmount, 0.0, 1.0));
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    color *= lightMapColor;
    fragColor = linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
