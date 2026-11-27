package com.example.bossmod;

import net.minecraft.client.render.entity.BipedEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.ZombieEntityModel;
import net.minecraft.client.render.entity.state.ZombieEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/**
 * Usa el modelo del zombi (x2 de tamano) con textura propia.
 * Para un modelo 100% custom con animaciones de Blockbench, ver el README (GeckoLib).
 */
public class BossRenderer extends BipedEntityRenderer<BossEntity, ZombieEntityRenderState, ZombieEntityModel<ZombieEntityRenderState>> {
	private static final Identifier TEXTURE = Identifier.of(BossMod.MOD_ID, "textures/entity/boss.png");

	public BossRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new ZombieEntityModel<>(ctx.getPart(EntityModelLayers.ZOMBIE)), 1.0f);
	}

	@Override
	public ZombieEntityRenderState createRenderState() {
		return new ZombieEntityRenderState();
	}

	@Override
	public Identifier getTexture(ZombieEntityRenderState state) {
		return TEXTURE;
	}

	@Override
	public void updateRenderState(BossEntity entity, ZombieEntityRenderState state, float tickDelta) {
		super.updateRenderState(entity, state, tickDelta);
		// Brazos arriba mientras prepara o ejecuta un ataque
		state.attacking = entity.isRaised();
	}

	@Override
	protected void scale(ZombieEntityRenderState state, MatrixStack matrices) {
		matrices.scale(2.0f, 2.0f, 2.0f);
	}
}
