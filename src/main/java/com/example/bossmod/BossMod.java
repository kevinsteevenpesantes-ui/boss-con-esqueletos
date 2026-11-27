package com.example.bossmod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class BossMod implements ModInitializer {
	public static final String MOD_ID = "bossmod";

	public static final RegistryKey<EntityType<?>> BOSS_KEY =
			RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, "boss"));

	public static final EntityType<BossEntity> BOSS = Registry.register(
			Registries.ENTITY_TYPE, BOSS_KEY,
			EntityType.Builder.create(BossEntity::new, SpawnGroup.MONSTER)
					.dimensions(1.3f, 3.9f)
					.maxTrackingRange(12)
					.makeFireImmune()
					.build(BOSS_KEY));

	public static final EntityType<MinionEntity> MINION_OSCURO = registerMinion("esqueleto_oscuro");
	public static final EntityType<MinionEntity> MINION_CONSUMIDO = registerMinion("esqueleto_consumido");
	public static final EntityType<MinionEntity> MINION_FANTASMA = registerMinion("esqueleto_fantasma");

	private static EntityType<MinionEntity> registerMinion(String id) {
		RegistryKey<EntityType<?>> key = RegistryKey.of(RegistryKeys.ENTITY_TYPE, Identifier.of(MOD_ID, id));
		return Registry.register(Registries.ENTITY_TYPE, key,
				EntityType.Builder.create(MinionEntity::new, SpawnGroup.MONSTER)
						.dimensions(0.6f, 1.99f)
						.maxTrackingRange(8)
						.build(key));
	}

	public static final RegistryKey<Item> EGG_KEY =
			RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "boss_spawn_egg"));

	public static final Item BOSS_SPAWN_EGG = Registry.register(
			Registries.ITEM, EGG_KEY,
			new SpawnEggItem(BOSS, new Item.Settings().registryKey(EGG_KEY)));

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(BOSS, BossEntity.createBossAttributes());
		// vida, dano, velocidad
		FabricDefaultAttributeRegistry.register(MINION_OSCURO, MinionEntity.attributes(40, 6, 0.28));
		FabricDefaultAttributeRegistry.register(MINION_CONSUMIDO, MinionEntity.attributes(24, 4, 0.36));
		FabricDefaultAttributeRegistry.register(MINION_FANTASMA, MinionEntity.attributes(16, 5, 0.32));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS)
				.register(entries -> entries.add(BOSS_SPAWN_EGG));
	}
}
