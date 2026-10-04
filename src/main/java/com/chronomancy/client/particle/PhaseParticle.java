package com.chronomancy.client.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Частицы хрономантии в стиле «фазирования сквозь время» — рисуются тем же языком, что и
 * оболочки/растворение сущностей (шейдер {@code chronomancy_particle}):
 * <ul>
 *   <li>{@link Kind#SHARD} — <b>осколки времени</b> (удары, попадания): обломки циферблата, шестерни,
 *       стрелки, руны; кружатся и в конце растворяются по пикселям;</li>
 *   <li>{@link Kind#DUST} — <b>пыль фазы с эхом</b> (фон, ауры, следы): светящиеся пылинки, за каждой
 *       тянутся 2–3 бледные эхо-копии на её прошлых позициях;</li>
 *   <li>{@link Kind#CLOCK} — <b>циферблаты</b> (каст, вспышки): мини-циферблат с бегущей стрелкой
 *       и расходящимся кольцом;</li>
 *   <li>{@link Kind#STREAK} — следы Rewind: вытянутая по движению полоска;</li>
 *   <li>{@link Kind#WAKE} — короткие засечки на пути иглы.</li>
 * </ul>
 * Цвет вершины кодирует параметры для шейдера: r — сдвиг оттенка переливания, g — растворение,
 * b — доля золота (0 — чистое переливание, 1 — золото), a — яркость.
 */
public class PhaseParticle extends TextureSheetParticle implements ChronoTemporalParticle {

    public enum Kind { SHARD, DUST, CLOCK, STREAK, WAKE }

    public enum Motion { FREE, ORBIT, SUSPENDED, IMPLODE }

    private final Kind kind;
    private final Motion motion;
    private final ChronoSprite sprite;
    private final float hue;
    private final float gold;
    private float spin;
    private float rollO;

    private int anchorId = -1;
    private double orbitRadius;
    private double orbitSpeed;
    private double yFrac;
    private double waveAmp;
    private final double phase;
    private double targetX, targetY, targetZ;

    /** Прошлые позиции для эха пыли (3 штуки, самая свежая — [0]). */
    private final double[] echo = new double[9];
    private int echoCount;
    private final Vector3f direction = new Vector3f(0.0F, 1.0F, 0.0F);
    private float streakLen = 2.0F;
    private float baseAlpha = 1.0F;
    private float dissolve;
    /** Медная пыль копий Rift: тот же шейдер, медная палитра (отдельный батч). */
    private boolean copper;
    /** Без эха и без ориентации — точка света (струи снарядов). */
    private boolean noEcho;
    /** Скорость стрелки циферблата (рад/тик); положительная — стрелка бежит назад (Rewind). */
    private float handSpeed = -0.35F;

    protected PhaseParticle(ClientLevel level, double x, double y, double z, Kind kind, Motion motion,
                            ChronoSprite sprite, float gold) {
        super(level, x, y, z);
        this.kind = kind;
        this.motion = motion;
        this.sprite = sprite;
        this.gold = Mth.clamp(gold, 0.0F, 1.0F);
        this.hue = this.random.nextFloat();
        this.phase = this.random.nextDouble() * Math.PI * 2.0;
        this.hasPhysics = false;
        this.gravity = 0.0F;
        this.friction = 0.94F;
        this.alpha = 0.0F;
        this.roll = this.oRoll = this.random.nextFloat() * Mth.TWO_PI;
        switch (kind) {
            case SHARD -> {
                this.quadSize = 0.07F + this.random.nextFloat() * 0.06F;
                this.lifetime = 16 + this.random.nextInt(12);
                this.spin = (this.random.nextFloat() - 0.5F) * 0.5F;
                this.gravity = 0.02F;
                this.friction = 0.9F;
            }
            case DUST -> {
                this.quadSize = 0.04F + this.random.nextFloat() * 0.07F;
                this.lifetime = 26 + this.random.nextInt(22);
            }
            case CLOCK -> {
                this.quadSize = 0.28F + this.random.nextFloat() * 0.12F;
                this.lifetime = 22 + this.random.nextInt(8);
                this.spin = (this.random.nextBoolean() ? 1 : -1) * 0.03F;
                this.friction = 0.85F;
            }
            case STREAK -> {
                this.quadSize = 0.05F + this.random.nextFloat() * 0.04F;
                this.lifetime = 13 + this.random.nextInt(9);
                this.streakLen = 1.6F + this.random.nextFloat() * 2.2F;
                this.friction = motion == Motion.IMPLODE ? 1.015F : 0.9F;
            }
            case WAKE -> {
                this.quadSize = 0.09F;
                this.lifetime = 6;
                this.friction = 0.0F;
            }
        }
        this.oRoll = this.roll;
    }

    // =========================================================
    // ФАБРИКИ
    // =========================================================

    /** Осколок времени: удар/попадание, разлетается и кружится. */
    public static PhaseParticle shard(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.SHARD, Motion.FREE,
                ChronoSprite.SHARDS[level.random.nextInt(ChronoSprite.SHARDS.length)], level.random.nextFloat() * 0.5F);
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Вспышка-звезда (короткая, яркая) — акцент к осколкам. */
    public static PhaseParticle star(ClientLevel level, double x, double y, double z) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.SHARD, Motion.FREE, ChronoSprite.STAR, 0.4F);
        p.quadSize = 0.22F;
        p.lifetime = 8;
        p.gravity = 0.0F;
        p.spin = 0.08F;
        p.setVelocity(0, 0, 0);
        return p;
    }

    /** Свободная пылинка фазы (с эхом). */
    public static PhaseParticle dust(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.DUST, Motion.FREE, ChronoSprite.DUST,
                level.random.nextFloat() * 0.6F);
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /**
     * Перекрасить в медь уже созданную частицу (до того, как движок частиц разложит её по слоям):
     * так красятся частицы заклинаний школы, сжатых Timeless Book.
     */
    public void makeCopper() {
        this.copper = true;
    }

    /** Медная пылинка копий Rift (тот же язык фазы, медная палитра — как их шейдер растворения). */
    public static PhaseParticle copper(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.DUST, Motion.FREE, ChronoSprite.DUST, 0.0F);
        p.copper = true;
        p.setVelocity(vx, vy == 0.0 && vx == 0.0 && vz == 0.0 ? 0.006 : vy, vz);
        return p;
    }

    /**
     * Огонёк струи снаряда: точка света без направления и без эха — рисунок (спираль, зигзаг,
     * волна) складывается из положения огоньков, а не из их ориентации.
     */
    public static PhaseParticle wisp(ClientLevel level, double x, double y, double z, double vx, double vy, double vz,
                                     float gold) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.DUST, Motion.FREE, ChronoSprite.DUST, gold);
        p.noEcho = true;
        p.quadSize = 0.05F + level.random.nextFloat() * 0.025F;
        p.lifetime = 14 + level.random.nextInt(8);
        p.friction = 0.88F;
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Крупный мягкий сгусток для «массивной» струи (Time Dilation Field). */
    public static PhaseParticle cloud(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.DUST, Motion.FREE, ChronoSprite.DUST,
                level.random.nextFloat() * 0.55F);
        p.noEcho = true;
        p.quadSize = 0.13F + level.random.nextFloat() * 0.1F;
        p.lifetime = 18 + level.random.nextInt(12);
        p.friction = 0.92F;
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Песчинка — мелкая, почти чистое золото. */
    public static PhaseParticle sand(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.DUST, Motion.FREE, ChronoSprite.SAND, 0.9F);
        p.quadSize = 0.035F + level.random.nextFloat() * 0.03F;
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Циферблат каста/вспышки: стрелка бежит, по краю расходится кольцо. */
    public static PhaseParticle clock(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.CLOCK, Motion.FREE, ChronoSprite.CLOCK_DIAL, 0.5F);
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Песочные часы (руна) — та же механика, что у циферблата, но без стрелки. */
    public static PhaseParticle hourglass(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.SHARD, Motion.FREE, ChronoSprite.HOURGLASS, 0.6F);
        p.quadSize = 0.12F;
        p.gravity = 0.0F;
        p.spin = 0.05F;
        p.setVelocity(vx, vy, vz);
        return p;
    }

    /** Пылинка ауры, кружащая вокруг сущности (стазис — «застывшее золото»). */
    public static PhaseParticle orbit(ClientLevel level, Entity anchor, float gold) {
        double radiusBase = Math.max(0.60, anchor.getBbWidth() * 0.80);
        PhaseParticle p = new PhaseParticle(level, anchor.getX(), anchor.getY() + anchor.getBbHeight() * 0.5,
                anchor.getZ(), Kind.DUST, Motion.ORBIT, ChronoSprite.DUST, gold);
        p.anchorId = anchor.getId();
        p.orbitRadius = radiusBase * (0.75 + level.random.nextDouble() * 0.55);
        p.orbitSpeed = (0.045 + level.random.nextDouble() * 0.055) * (level.random.nextBoolean() ? 1.0 : -1.0);
        p.yFrac = 0.10 + level.random.nextDouble() * 0.85;
        p.waveAmp = 0.05 + level.random.nextDouble() * 0.18;
        p.lifetime = 34 + level.random.nextInt(26);
        return p;
    }

    /** Подвешенная пылинка («время стоит»): медленно дышит по Y. */
    public static PhaseParticle suspended(ClientLevel level, Entity anchor, float gold) {
        double w = Math.max(0.5, anchor.getBbWidth());
        PhaseParticle p = new PhaseParticle(level,
                anchor.getX() + (level.random.nextDouble() - 0.5) * w * 1.3,
                anchor.getY() + level.random.nextDouble() * anchor.getBbHeight(),
                anchor.getZ() + (level.random.nextDouble() - 0.5) * w * 1.3,
                Kind.DUST, Motion.SUSPENDED, ChronoSprite.DUST, gold);
        p.yd = 0.002 + level.random.nextDouble() * 0.004;
        p.lifetime = 34 + level.random.nextInt(26);
        return p;
    }

    /** След Rewind: полоска летит вдоль траектории к точке отката. */
    public static PhaseParticle streak(ClientLevel level, Vec3 pos, Vec3 forward) {
        double speed = 0.24 + level.random.nextDouble() * 0.10;
        Vec3 dir = forward.lengthSqr() > 1.0e-6 ? forward.normalize() : new Vec3(0.0, 0.05, 0.0);
        double j = 0.018;
        PhaseParticle p = new PhaseParticle(level,
                pos.x + (level.random.nextDouble() - 0.5) * 0.22,
                pos.y + (level.random.nextDouble() - 0.5) * 0.30,
                pos.z + (level.random.nextDouble() - 0.5) * 0.22,
                Kind.STREAK, Motion.FREE, ChronoSprite.STREAK, 0.25F);
        p.setVelocity(dir.x * speed + (level.random.nextDouble() - 0.5) * j,
                dir.y * speed + (level.random.nextDouble() - 0.5) * j,
                dir.z * speed + (level.random.nextDouble() - 0.5) * j);
        p.aimAlongVelocity();
        return p;
    }

    /** Схлопывание: из точки сферы радиуса {@code radius} в центр {@code dest}. */
    public static PhaseParticle implode(ClientLevel level, Vec3 dest, double radius) {
        double a = level.random.nextDouble() * Math.PI * 2.0;
        double u = level.random.nextDouble() * 2.0 - 1.0;
        double s = Math.sqrt(Math.max(0.0, 1.0 - u * u));
        Vec3 from = dest.add(Math.cos(a) * s * radius, u * radius + 0.9, Math.sin(a) * s * radius);
        Vec3 target = dest.add(0, 0.9, 0);
        Vec3 v = target.subtract(from).normalize().scale(0.055 + level.random.nextDouble() * 0.03);
        PhaseParticle p = new PhaseParticle(level, from.x, from.y, from.z, Kind.STREAK, Motion.IMPLODE,
                ChronoSprite.STREAK, 0.3F);
        p.lifetime = 10 + level.random.nextInt(6);
        p.setVelocity(v.x, v.y, v.z);
        p.aimAlongVelocity();
        p.targetX = target.x;
        p.targetY = target.y;
        p.targetZ = target.z;
        return p;
    }

    /** Засечка на пути иглы. */
    public static PhaseParticle wake(ClientLevel level, double x, double y, double z) {
        PhaseParticle p = new PhaseParticle(level, x, y, z, Kind.WAKE, Motion.FREE, ChronoSprite.NOTCH, 0.7F);
        p.setVelocity(0, 0, 0);
        return p;
    }

    /** Полоска сразу смотрит по скорости (иначе первые кадры она торчит вверх — «веер» в разные стороны). */
    private void aimAlongVelocity() {
        double len = Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);
        if (len > 1.0e-6) {
            this.direction.set((float) (this.xd / len), (float) (this.yd / len), (float) (this.zd / len));
        }
    }

    private void setVelocity(double vx, double vy, double vz) {
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
    }

    public PhaseParticle scaled(float k) {
        this.quadSize *= k;
        return this;
    }

    /** Стрелка циферблата бежит назад — время откатывается. */
    public PhaseParticle reversed() {
        this.handSpeed = 0.5F;
        return this;
    }

    public PhaseParticle longer(float k) {
        this.lifetime = (int) (this.lifetime * k);
        return this;
    }

    // =========================================================
    // TICK
    // =========================================================

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;
        if (this.age++ >= this.lifetime) {
            this.remove();
            return;
        }
        // эхо: сдвигаем историю позиций
        if (this.kind == Kind.DUST && !this.noEcho) {
            System.arraycopy(this.echo, 0, this.echo, 3, 6);
            this.echo[0] = this.x;
            this.echo[1] = this.y;
            this.echo[2] = this.z;
            this.echoCount = Math.min(3, this.echoCount + 1);
        }

        Entity anchor = this.anchorId >= 0 ? this.level.getEntity(this.anchorId) : null;
        if (this.anchorId >= 0 && (anchor == null || !anchor.isAlive())) {
            this.anchorId = -1;
            this.lifetime = Math.min(this.lifetime, this.age + 8);
        }
        switch (this.motion) {
            case ORBIT -> {
                if (anchor != null) {
                    double angle = this.phase + this.age * this.orbitSpeed;
                    this.x = anchor.getX() + Mth.cos((float) angle) * this.orbitRadius;
                    this.z = anchor.getZ() + Mth.sin((float) angle) * this.orbitRadius;
                    this.y = anchor.getY() + anchor.getBbHeight() * this.yFrac
                            + Mth.sin((float) (this.age * 0.11 + this.phase)) * this.waveAmp;
                }
            }
            case SUSPENDED -> {
                this.y += this.yd + Mth.sin(this.age * 0.15F + (float) this.phase) * 0.0018;
            }
            case IMPLODE -> {
                double dx = this.targetX - this.x, dy = this.targetY - this.y, dz = this.targetZ - this.z;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 0.28) {
                    this.age = this.lifetime;
                }
                if (len > 1.0e-4) {
                    double accel = 0.024 / Math.max(len, 0.35);
                    this.xd += dx * accel;
                    this.yd += dy * accel;
                    this.zd += dz * accel;
                }
                this.x += this.xd;
                this.y += this.yd;
                this.z += this.zd;
                this.xd *= this.friction;
                this.yd *= this.friction;
                this.zd *= this.friction;
            }
            case FREE -> {
                this.yd -= this.gravity * 0.04;
                this.x += this.xd;
                this.y += this.yd;
                this.z += this.zd;
                this.xd *= this.friction;
                this.yd *= this.friction;
                this.zd *= this.friction;
            }
        }
        this.roll += this.spin;

        if (this.kind == Kind.STREAK) {
            double vLen = Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);
            if (vLen > 1.0e-4) {
                Vector3f now = new Vector3f((float) (this.xd / vLen), (float) (this.yd / vLen), (float) (this.zd / vLen));
                if (this.motion == Motion.IMPLODE) {
                    // схлопывание закручивает траекторию — направление догоняет плавно
                    this.direction.lerp(now, 0.55F).normalize();
                } else {
                    // след летит по прямой: все полоски строго вдоль своей скорости
                    this.direction.set(now);
                }
            }
        }

        float life = Mth.clamp(this.age / (float) this.lifetime, 0.0F, 1.0F);
        float fadeIn = Mth.clamp(life / 0.15F, 0.0F, 1.0F);
        float twinkle = 0.85F + 0.15F * Mth.sin(this.age * 0.4F + (float) this.phase * 5.0F);
        this.baseAlpha = fadeIn * (this.kind == Kind.DUST ? twinkle : 1.0F);
        // вторая половина жизни — растворение по пикселям (как у «выпадающих» частей тела)
        this.dissolve = this.kind == Kind.WAKE ? life : Mth.clamp((life - 0.5F) / 0.5F, 0.0F, 1.0F);
        this.alpha = this.baseAlpha;
    }

    // =========================================================
    // RENDER
    // =========================================================

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partialTicks) {
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partialTicks, this.xo, this.x) - cam.x());
        float py = (float) (Mth.lerp(partialTicks, this.yo, this.y) - cam.y());
        float pz = (float) (Mth.lerp(partialTicks, this.zo, this.z) - cam.z());
        float roll = Mth.lerp(partialTicks, this.oRoll, this.roll);
        Quaternionf camRot = camera.rotation();
        int light = LightTexture.FULL_BRIGHT;
        float a = this.alpha;
        switch (this.kind) {
            case SHARD, WAKE -> quad(buffer, camRot, px, py, pz, this.quadSize, roll, this.sprite, this.dissolve, a, light);
            case DUST -> {
                quad(buffer, camRot, px, py, pz, this.quadSize, roll, this.sprite, this.dissolve, a, light);
                // эхо-копии на прошлых позициях: бледнее и мельче
                float[] echoA = {0.45F, 0.25F, 0.12F};
                for (int k = 0; k < this.echoCount; k++) {
                    float ex = (float) (this.echo[k * 3] - cam.x());
                    float ey = (float) (this.echo[k * 3 + 1] - cam.y());
                    float ez = (float) (this.echo[k * 3 + 2] - cam.z());
                    if ((ex - px) * (ex - px) + (ey - py) * (ey - py) + (ez - pz) * (ez - pz) < 1.0e-4F) {
                        continue;
                    }
                    quad(buffer, camRot, ex, ey, ez, this.quadSize * (0.85F - k * 0.15F), roll, this.sprite,
                            Math.max(this.dissolve, 0.15F * (k + 1)), a * echoA[k], light);
                }
            }
            case CLOCK -> {
                float life = Mth.clamp((this.age + partialTicks) / this.lifetime, 0.0F, 1.0F);
                quad(buffer, camRot, px, py, pz, this.quadSize, roll, ChronoSprite.CLOCK_DIAL, this.dissolve, a, light);
                float handAngle = roll + (this.age + partialTicks) * this.handSpeed;
                quad(buffer, camRot, px, py, pz, this.quadSize * 0.95F, handAngle, ChronoSprite.CLOCK_HAND,
                        this.dissolve, a, light);
                // расходящееся кольцо
                quad(buffer, camRot, px, py, pz, this.quadSize * (1.0F + life * 1.6F), 0.0F, ChronoSprite.RING,
                        Math.max(this.dissolve, life * 0.6F), a * (1.0F - life), light);
            }
            case STREAK -> renderStreak(buffer, camera, px, py, pz, a, light);
        }
    }

    private void quad(VertexConsumer buffer, Quaternionf camRot, float x, float y, float z, float size, float roll,
                      ChronoSprite s, float dissolve, float alpha, int light) {
        if (alpha <= 0.01F || dissolve >= 0.999F) {
            return;
        }
        Quaternionf q = new Quaternionf(camRot).rotateZ(roll);
        // порядок вершин как у ванильных частиц (против часовой стрелки к камере): при обратном
        // порядке квад смотрит «спиной» и отсекается — частица невидима
        Vector3f[] c = {new Vector3f(1, -1, 0), new Vector3f(1, 1, 0), new Vector3f(-1, 1, 0), new Vector3f(-1, -1, 0)};
        float[][] uv = {{s.u1, s.v1}, {s.u1, s.v0}, {s.u0, s.v0}, {s.u0, s.v1}};
        for (int i = 0; i < 4; i++) {
            Vector3f v = c[i];
            v.rotate(q).mul(size).add(x, y, z);
            emit(buffer, v.x(), v.y(), v.z(), uv[i][0], uv[i][1], dissolve, alpha, light);
        }
    }

    private void renderStreak(VertexConsumer buffer, Camera camera, float rx, float ry, float rz, float a, int light) {
        Quaternionf camRot = camera.rotation();
        Quaternionf invCam = new Quaternionf(camRot).conjugate();
        Vector3f viewDir = invCam.transform(this.direction, new Vector3f());
        float theta = viewDir.x() * viewDir.x() + viewDir.y() * viewDir.y() > 1.0e-6F
                ? (float) Mth.atan2(-viewDir.x(), viewDir.y()) : 0.0F;
        Quaternionf q = new Quaternionf(camRot).rotateZ(theta);
        Vector3f right = q.transform(1.0F, 0.0F, 0.0F, new Vector3f());
        Vector3f up = q.transform(0.0F, 1.0F, 0.0F, new Vector3f());
        double vLen = Math.sqrt(this.xd * this.xd + this.yd * this.yd + this.zd * this.zd);
        float len = Mth.clamp((float) vLen * this.streakLen * 6.0F, 0.12F, 0.85F);
        float w = this.quadSize;
        float tailW = w * 0.45F;
        ChronoSprite s = ChronoSprite.STREAK;
        float bx = rx - up.x() * len, by = ry - up.y() * len, bz = rz - up.z() * len;
        vertex(buffer, rx + right.x() * w, ry + right.y() * w, rz + right.z() * w, s.u0, s.v1, a, light);
        vertex(buffer, rx - right.x() * w, ry - right.y() * w, rz - right.z() * w, s.u0, s.v0, a, light);
        vertex(buffer, bx - right.x() * tailW, by - right.y() * tailW, bz - right.z() * tailW, s.u1, s.v0, a * 0.4F, light);
        vertex(buffer, bx + right.x() * tailW, by + right.y() * tailW, bz + right.z() * tailW, s.u1, s.v1, a * 0.4F, light);
    }

    private void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, float a, int light) {
        emit(buffer, x, y, z, u, v, this.dissolve, a, light);
    }

    /**
     * Вершина: при живом шейдере цвет несёт параметры (оттенок, растворение, золото, яркость);
     * если шейдер не скомпилировался — честный цвет палитры синий/золото с растворением через альфу.
     */
    private void emit(VertexConsumer buffer, float x, float y, float z, float u, float v, float dissolve, float alpha,
                      int light) {
        buffer.addVertex(x, y, z).setUv(u, v);
        if (ChronoParticles.phaseShaderActive()) {
            buffer.setColor(this.hue, dissolve, this.gold, alpha);
        } else {
            float g = this.copper ? 0.5F : this.gold;
            float r = this.copper ? 0.85F : Mth.lerp(g, 0.35F, 1.0F);
            float gg = this.copper ? 0.48F : Mth.lerp(g, 0.7F, 0.8F);
            float b = this.copper ? 0.24F : Mth.lerp(g, 1.0F, 0.3F);
            buffer.setColor(r, gg, b, alpha * (1.0F - dissolve));
        }
        buffer.setLight(light);
    }

    /**
     * Частица двигается напрямую через x/y/z (без {@code move}), поэтому стандартный bounding box
     * остаётся в точке появления — отсечение по видимости проверяем по текущей позиции.
     */
    @Override
    public net.minecraft.world.phys.AABB getRenderBoundingBox(float partialTicks) {
        double r = Math.max(0.5, this.quadSize * 4.0);
        return new net.minecraft.world.phys.AABB(this.x - r, this.y - r, this.z - r, this.x + r, this.y + r, this.z + r);
    }

    @Override
    protected int getLightColor(float partialTick) {
        return LightTexture.FULL_BRIGHT;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return this.copper ? ChronoParticles.COPPER_SHEET : ChronoParticles.SHEET;
    }
}
