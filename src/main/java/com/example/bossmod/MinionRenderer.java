package com.example.bossmod;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SkeletonEntityModel;
import net.minecraft.client.render.entity.state.SkeletonEntityRenderState;
import net.minecraft.util.Identifier;

/**
 * Tus modelos de Blockbench tienen exactamente la geometria del esqueleto vanilla
 * (cabeza, torso, brazos y piernas), asi que se usa ese modelo con tus texturas.
 */
public class MinionRenderer extends BipedEntityRenderer<MinionEntity, SkeletonEntityRenderState, SkeletonEntityModel<SkeletonEntityRenderState>> {
	private final Identifier texture;

	public MinionRenderer(EntityRendererFactory.Context ctx, String textureName) {
		super(ctx, new SkeletonEntityModel<>(ctx.getPart(EntityModelLayers.SKELETON)), 0.5f);
		this.texture = Identifier.of(BossMod.MOD_ID, "textures/entity/" + textureName + ".png");
	}

	@Override
	public SkeletonEntityRenderState createRenderState() {
		return new SkeletonEntityRenderState();
	}

	@Override
	public Identifier getTexture(SkeletonEntityRenderState state) {
		return texture;
	}

	@Override
	public void updateRenderState(MinionEntity entity, SkeletonEntityRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		state.attacking = entity.isAttacking();
	}
}
