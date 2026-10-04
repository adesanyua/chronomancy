#version 150

// THE WORLD STOP — cinematic copper/sepia grade (Phase C: + polish).
//
// Намеренно НЕ «screenColor *= yellow»: это попиксельный remap по luminance,
// который сохраняет детали текстур/AO/тени, но перекрашивает мир в тон старой
// медно-золотой фотографии. Порядок кадра:
//   chromatic sample -> luma -> desaturation -> copper palette -> contrast
//   -> vignette -> stepped grain -> лерп с оригиналом по EffectStrength.
//
// ВСЕ эффекты масштабируются EffectStrength (через плавную smoothstep-кривую
// перехода); при strength = 0 кадр побайтово vanilla. Интенсивности отдельных
// слоёв — униформы VignetteAmount/ChromaticAmount/GrainAmount: клиентский код
// пишет их каждый кадр, будущий конфиг = просто 3 числа (архитектура не
// захардкожена).
//
// Зерно (`Time`-униформа PostChain) шагово квантовано до ~8 Гц: время СТОИТ,
// поэтому зерно не «дышит» плавно киношным шумом, а медленно перещёлкивается.
// Time приходит из реального рендер-времени клиента, а не из замороженного
// world gameTime.

uniform sampler2D DiffuseSampler;

uniform vec2 OutSize;
uniform float EffectStrength;
uniform float VignetteAmount;
uniform float ChromaticAmount;
uniform float GrainAmount;
uniform float Time;

in vec2 texCoord;

out vec4 fragColor;

// Палитра «старого медно-золотого снимка» (sRGB, §5 ТЗ):
//   shadow #342617 | dark copper #6E4725 | copper #8A5A2B
//   sand #B68B46   | gold #D0AD60        | highlight #E3D19A
const vec3 C_SHADOW    = vec3(0.204, 0.149, 0.090);
const vec3 C_DARK_COPP = vec3(0.431, 0.278, 0.145);
const vec3 C_SAND      = vec3(0.714, 0.545, 0.275);
const vec3 C_GOLD      = vec3(0.816, 0.678, 0.376);
const vec3 C_HIGHLIGHT = vec3(0.890, 0.820, 0.604);

// Ремап luminance в медную палитру градиентом (не плоской заливкой):
//   0.00-0.30 : shadow   -> dark copper   (тени остаются читаемо тёмными)
//   0.30-0.65 : dark copper -> sand       (средние тона — медь/песок)
//   0.65-1.00 : sand/brown -> pale gold   (хайлайты — старое золото)
vec3 temporalPalette(float l) {
    if (l < 0.30) {
        return mix(C_SHADOW, C_DARK_COPP, l / 0.30);
    } else if (l < 0.65) {
        return mix(C_DARK_COPP, C_SAND, (l - 0.30) / 0.35);
    } else {
        return mix(C_SAND * 0.85 + C_GOLD * 0.15, C_HIGHLIGHT, clamp((l - 0.65) / 0.35, 0.0, 1.0));
    }
}

// Детерминированный псевдослучайный шум по пиксельной ячейке (для зерна).
float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    // Плавная S-кривая включения: strength 0/0.5/1 -> 0/~0.5/1 без линейного
    // «щелчка» на старте перехода.
    float str = smoothstep(0.0, 1.0, clamp(EffectStrength, 0.0, 1.0));

    // --- Chromatic / temporal separation (§9) -----------------------------
    // Направление от центра экрана; величина растёт QUADRATIC'ом к краям,
    // поэтому центр остаётся абсолютно резким, а у краёв смещение до ~0.0015 UV.
    // ИТОГО семплов: 3 (R + базовый RGB с тем же адресом переиспользуется G,
    // B с противоположным смещением) — в пределах лимита 3–5.
    vec2 centerVec = texCoord - 0.5;
    float aspect = OutSize.x / max(OutSize.y, 1.0);
    // Радиал в нормированных единицах: 0 в центре, 1 в углу экрана.
    float radial = clamp(length(vec2(centerVec.x * aspect, centerVec.y))
                         / max(sqrt(aspect * aspect + 1.0), 0.0001), 0.0, 1.0);

    vec2 chromaOff = centerVec * (0.0015 * ChromaticAmount * radial * radial * str);

    vec3 color;
    color.r = texture(DiffuseSampler, texCoord + chromaOff).r;
    vec3 base = texture(DiffuseSampler, texCoord).rgb;
    color.g = base.g;
    color.b = texture(DiffuseSampler, texCoord - chromaOff).b;

    // --- Color grading (§5/§6/§7) ------------------------------------------
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));

    // Сильная, но НЕ полная десатурация: ~28% хроматики сохраняется.
    float desaturation = 0.72;
    vec3 desaturated = mix(color, vec3(luma), desaturation);

    // Медный grade поверх десатурированного оригинала — детали/AO выживают.
    vec3 graded = mix(desaturated, temporalPalette(luma), 0.75);

    // Небольшой кинематографичный контраст вокруг 0.5 (без crush чёрного).
    graded = (graded - 0.5) * 1.08 + 0.5;

    // --- Vignette (§8): углы темнеют максимум на ~17%, центр нетронут ------
    float vig = 1.0 - 0.17 * VignetteAmount * smoothstep(0.35, 1.0, radial) * str;
    graded *= vig;

    // --- Temporal grain (§10): очень слабое (~±1%), квантовано до ~8 Гц ----
    if (GrainAmount > 0.0 && str > 0.0) {
        float step_t = floor(Time * 8.0);
        vec2 cell = texCoord * OutSize + step_t * 137.0;
        float n = hash(cell) - 0.5;
        graded += n * 0.02 * GrainAmount * str;
    }

    // --- Итог: весь эффект — лерп к оригиналу по strength; 0 == vanilla ----
    vec3 result = mix(base, clamp(graded, 0.0, 1.0), str);

    fragColor = vec4(result, 1.0);
}
