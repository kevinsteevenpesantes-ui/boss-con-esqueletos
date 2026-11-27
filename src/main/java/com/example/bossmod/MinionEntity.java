package com.example.bossmod;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * Esbirro del boss. Hay 3 tipos (mismo codigo, distinto EntityType):
 *  - oscuro:    resistente, golpe fuerte con mucho empuje
 *  - consumido: rapido, su golpe aplica Wither
 *  - fantasma:  fragil, se teletransporta cerca de su objetivo y lo ralentiza
 */
public class MinionEntity extends HostileEntity {
	private UUID bossId;
	private int noBossSeconds;
	private int blinkCooldown = 60;

	public MinionEntity(EntityType<? extends HostileEntity> type, World world) {
		super(type, world);
		this.experiencePoints = 5;
	}

	public static DefaultAttributeContainer.Builder attributes(double health, double damage, double speed) {
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.MAX_HEALTH, health)
				.add(EntityAttributes.ATTACK_DAMAGE, damage)
				.add(EntityAttributes.MOVEMENT_SPEED, speed)
				.add(EntityAttributes.FOLLOW_RANGE, 40.0);
	}

	public void setBossId(UUID id) {
		this.bossId = id;
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(2, new MeleeAttackGoal(this, 1.1, false));
		this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.8));
		this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 16.0f));
		this.goalSelector.add(7, new LookAroundGoal(this));
		this.targetSelector.add(1, new RevengeGoal(this));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
	}

	@Override
	public void tick() {
		super.tick();
		if (!(getWorld() instanceof ServerWorld w)) {
			return;
		}

		// Si el boss muere o desaparece, los esbirros se desvanecen
		if (bossId != null && age % 20 == 0) {
			Entity boss = w.getEntity(bossId);
			boolean gone = (boss == null) ? ++noBossSeconds > 10 : !boss.isAlive();
			if (boss != null && boss.isAlive()) noBossSeconds = 0;
			if (gone) {
				w.spawnParticles(ParticleTypes.SOUL, getX(), getY() + 1, getZ(), 15, 0.4, 0.8, 0.4, 0.05);
				discard();
				return;
			}
		}

		// Fantasma: parpadeo hasta quedar junto a su objetivo
		if (getType() == BossMod.MINION_FANTASMA) {
			if (blinkCooldown > 0) {
				blinkCooldown--;
			} else {
				LivingEntity t = getTarget();
				if (t != null && t.isAlive()) {
					double d = distanceTo(t);
					if (d > 5 && d < 24) {
						tryBlink(w, t);
					} else {
						blinkCooldown = 20;
					}
				}
			}
		}
	}

	private void tryBlink(ServerWorld w, LivingEntity t) {
		for (int i = 0; i < 8; i++) {
			double a = random.nextDouble() * Math.PI * 2;
			double x = t.getX() + Math.cos(a) * 3.0;
			double z = t.getZ() + Math.sin(a) * 3.0;
			Box moved = getBoundingBox().offset(x - getX(), t.getY() - getY(), z - getZ());
			if (w.isSpaceEmpty(this, moved)) {
				w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 1, getZ(), 12, 0.3, 0.6, 0.3, 0.03);
				refreshPositionAndAngles(x, t.getY(), z, getYaw(), getPitch());
				getNavigation().stop();
				w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, x, t.getY() + 1, z, 12, 0.3, 0.6, 0.3, 0.03);
				w.playSound(null, x, t.getY(), z, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 0.8f, 1.4f);
				blinkCooldown = 100;
				return;
			}
		}
		blinkCooldown = 20;
	}

	@Override
	public boolean tryAttack(ServerWorld world, Entity target) {
		boolean hit = super.tryAttack(world, target);
		if (hit && target instanceof LivingEntity victim) {
			if (getType() == BossMod.MINION_CONSUMIDO) {
				victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, 0));
			} else if (getType() == BossMod.MINION_FANTASMA) {
				victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1));
			} else {
				victim.takeKnockback(0.9, getX() - victim.getX(), getZ() - victim.getZ());
			}
		}
		return hit;
	}

	@Override
	protected SoundEvent getAmbientSound() {
		return SoundEvents.ENTITY_SKELETON_AMBIENT;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_SKELETON_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_SKELETON_DEATH;
	}

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		if (bossId != null) nbt.putUuid("BossId", bossId);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.containsUuid("BossId")) bossId = nbt.getUuid("BossId");
	}
}
