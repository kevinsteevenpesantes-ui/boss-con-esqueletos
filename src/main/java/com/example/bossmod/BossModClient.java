package com.example.bossmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class BossModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(BossMod.BOSS, BossRenderer::new);
		EntityRendererRegistry.register(BossMod.MINION_OSCURO, ctx -> new MinionRenderer(ctx, "esqueleto_oscuro"));
		EntityRendererRegistry.register(BossMod.MINION_CONSUMIDO, ctx -> new MinionRenderer(ctx, "esqueleto_consumido"));
		EntityRendererRegistry.register(BossMod.MINION_FANTASMA, ctx -> new MinionRenderer(ctx, "esqueleto_fantasma"));
	}
}
