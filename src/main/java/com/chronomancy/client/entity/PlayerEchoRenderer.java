package com.chronomancy.client.entity;

import com.chronomancy.entity.PlayerEchoEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Медный двойник игрока: модель игрока (широкая или тонкая — как у скина владельца) со скином
 * владельца и копией его оружия в руке. Медный цвет накладывает общий tint-миксин
 * ({@code RiftEchoClient}) — текстура и детали скина сохраняются.
 */
public final class PlayerEchoRenderer extends EntityRenderer<PlayerEchoEntity> {

    private final Inner wide;
    private final Inner slim;

    public PlayerEchoRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.wide = new Inner(context, false);
        this.slim = new Inner(context, true);
        this.shadowRadius = 0.5F;
    }

    private static PlayerSkin skinOf(PlayerEchoEntity entity) {
        UUID owner = entity.getSkinOwner();
        if (owner == null) {
            return DefaultPlayerSkin.get(entity.getUUID());
        }
        var connection = Minecraft.getInstance().getConnection();
        PlayerInfo info = connection == null ? null : connection.getPlayerInfo(owner);
        return info != null ? info.getSkin() : DefaultPlayerSkin.get(owner);
    }

    @Override
    public void render(PlayerEchoEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        boolean isSlim = skinOf(entity).model() == PlayerSkin.Model.SLIM;
        (isSlim ? slim : wide).render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(PlayerEchoEntity entity) {
        return skinOf(entity).texture();
    }

    private static final class Inner extends HumanoidMobRenderer<PlayerEchoEntity, PlayerModel<PlayerEchoEntity>> {
        Inner(EntityRendererProvider.Context context, boolean slim) {
            super(context, new PlayerModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim), 0.5F);
            this.addLayer(new HumanoidArmorLayer<>(this,
                    new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_INNER_ARMOR : ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidArmorModel<>(context.bakeLayer(slim ? ModelLayers.PLAYER_SLIM_OUTER_ARMOR : ModelLayers.PLAYER_OUTER_ARMOR)),
                    context.getModelManager()));
        }

        @Override
        public ResourceLocation getTextureLocation(PlayerEchoEntity entity) {
            return skinOf(entity).texture();
        }
    }
}
