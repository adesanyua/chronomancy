package com.chronomancy.client.particle;

/**
 * Пиксельный атлас частиц школы ({@code chrono_atlas.png}, 128x32, клетки 16x16, 8x2).
 * Спрайты в оттенках серого: цвет (переливание синий → голубой → золото) даёт шейдер частиц,
 * яркость пикселя — его форма и объём.
 */
public enum ChronoSprite {
    SHARD_DIAL(0), SHARD_GEAR(1), SHARD_HAND(2), SHARD_RUNE(3),
    DUST(4), SPARK(5), STREAK(6), RING(7),
    CLOCK_DIAL(8), CLOCK_HAND(9), STAR(10), NOTCH(11), SAND(12), HOURGLASS(13);

    public static final ChronoSprite[] SHARDS = {SHARD_DIAL, SHARD_GEAR, SHARD_HAND, SHARD_RUNE};

    public final float u0, v0, u1, v1;

    ChronoSprite(int cell) {
        int col = cell % 8, row = cell / 8;
        this.u0 = (col * 16 + 0.5f) / 128f;
        this.u1 = (col * 16 + 15.5f) / 128f;
        this.v0 = (row * 16 + 0.5f) / 32f;
        this.v1 = (row * 16 + 15.5f) / 32f;
    }
}
