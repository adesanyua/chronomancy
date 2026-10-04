package com.chronomancy.entity;

import com.chronomancy.registry.ChronoParticleRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * Остаточный образ Rewind — «образ, застрявший в отменённой временной линии».
 *
 * <p>Когда игрока перематывает назад, на точке каста остаётся золотисто-медный
 * силуэт персонажа (отрисовка — {@code RewindAfterimageRenderer}): тело ушло,
 * а «картинка» нескольких секунд назад ещё растворяется. Это не копия игрока и
 * не призрак — полностью декоративная сущность: без физики, без коллизии,
 * не целиется лучом, живёт ровно {@value #LIFETIME_TICKS} тиков и гаснет.
 *
 * <p>Синхронизация — штатным ванильным entity-tracking'ом (позиция, поворот,
 * спавн-пакет), поэтому отдельный сетевой пакет не нужен — тот же приём, что
 * у {@link TimeDilationFieldEntity}. Клиентская часть — только растворяющиеся
 * золотые пылинки внутри силуэта.
 */
public class RewindAfterimageEntity extends Entity {

    /** Время жизни образа (1.5 секунды, как у всех остаточных копий): последние 0.6 с он растворяется. */
    public static final int LIFETIME_TICKS = 30;

    public RewindAfterimageEntity(EntityType<? extends RewindAfterimageEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // нет синхронизируемых данных: образ полностью декоративен
    }

    /**
     * Позиция и поворот кастера на момент каста. Вызывать ДО {@code addFreshEntity},
     * чтобы значения попали в спавн-пакет (тот же приём, что у снаряда-клубка).
     */
    public void configure(double x, double y, double z, float yaw, float pitch) {
        this.setPos(x, y, z);
        this.setRot(yaw, pitch);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            spawnDissolveParticles();
        } else if (this.tickCount >= LIFETIME_TICKS) {
            this.discard();
        }
    }

    /**
     * Клиент: силуэт едва заметно «рассыпается» — изредка поднимающаяся
     * temporal mote. Раньше это был шквал (3 частицы/тик ≈ 120 за жизнь),
     * который полностью затенял сам afterimage; теперь — тихий намёк на
     * растворение, не перебивающий рендер силуэта.
     */
    private void spawnDissolveParticles() {
        if (this.tickCount % 2 != 0) {
            return;
        }
        this.level().addParticle(ChronoParticleRegistry.TEMPORAL_MOTE.get(),
                this.getX() + (this.random.nextDouble() - 0.5) * 0.55,
                this.getY() + this.random.nextDouble() * 1.8,
                this.getZ() + (this.random.nextDouble() - 0.5) * 0.35,
                0.0D, 0.035D, 0.0D);
    }

    // =========================================================
    // ПОЛНАЯ ДЕКОРАТИВНОСТЬ: ни коллизий, ни взаимодействия
    // =========================================================

    /** Не мешаться ни клику, ни трассировке луча (никакого «призрака» в прицеле). */
    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // не сохраняем: образ короткоживущий и переживает только текущий откат
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
