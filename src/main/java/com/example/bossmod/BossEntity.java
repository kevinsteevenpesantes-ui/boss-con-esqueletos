package com.example.bossmod;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
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
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BossEntity extends HostileEntity {

	// =====================================================================
	//  CONFIGURACION (cambia estos numeros para balancear el boss)
	// =====================================================================
	/** Corazones por fase. 1 corazon = 2 de vida. */
	private static final double[] PHASE_HEARTS = {1000, 1700, 2500};
	/** Multiplicador de dano por fase. */
	private static final double[] DAMAGE_MULT = {1.0, 1.25, 1.6};
	/** Velocidad de movimiento por fase. */
	private static final double[] SPEED = {0.26, 0.30, 0.34};
	/** Ticks de espera entre ataques por fase (20 ticks = 1 segundo). */
	private static final int[] COOLDOWN = {50, 35, 20};
	/** Por debajo de este % de vida (de la fase actual) es inmune a flechas. */
	private static final double ARROW_IMMUNE_BELOW = 0.5;
	/** Ticks que se desactiva un escudo al ser golpeado (100 = 5 segundos). */
	private static final int SHIELD_DISABLE_TICKS = 100;

	// =====================================================================
	private static final TrackedData<Integer> ANIM =
			DataTracker.registerData(BossEntity.class, TrackedDataHandlerRegistry.INTEGER);

	private enum Attack { NONE, THRUST, LEAP, FLURRY, PILLARS, WHIRL, SUMMON }

	private final ServerBossBar bossBar = new ServerBossBar(
			Text.literal("Coloso Ancestral"), BossBar.Color.YELLOW, BossBar.Style.PROGRESS);

	private int phase = 1;
	private double hp;
	private double maxHp;
	private int iFrames;
	private int transition;
	private int cooldown = 60;

	private Attack attack = Attack.NONE;
	private Attack lastAttack = Attack.NONE;
	private int attackTick;
	private Vec3d dir = Vec3d.ZERO;
	private final Set<UUID> hitSet = new HashSet<>();

	// salto
	private int leapStage;
	private int leapsLeft;
	private int shockTick;
	private Vec3d leapStart = Vec3d.ZERO;
	private Vec3d leapEnd = Vec3d.ZERO;
	private double leapHeight;

	// pilares
	private final List<Vec3d> pillars = new ArrayList<>();
	private int wavesLeft;

	// esbirros
	private boolean forceSummon;
	/** Maximo de esbirros vivos a la vez: fase 2 / fase 3. */
	private static final int[] MINION_CAP = {0, 6, 10};
	/** Esbirros por invocacion: fase 2 / fase 3. */
	private static final int[] MINION_BATCH = {0, 3, 5};

	public BossEntity(EntityType<? extends HostileEntity> type, World world) {
		super(type, world);
		this.maxHp = PHASE_HEARTS[0] * 2;
		this.hp = this.maxHp;
		this.experiencePoints = 500;
		this.setPersistent();
	}

	public static DefaultAttributeContainer.Builder createBossAttributes() {
		// La vida real del boss se lleva en el campo "hp" (el atributo de vida de
		// Minecraft tiene un tope de 1024, menos de lo que necesitamos).
		return HostileEntity.createHostileAttributes()
				.add(EntityAttributes.MAX_HEALTH, 1000.0)
				.add(EntityAttributes.MOVEMENT_SPEED, SPEED[0])
				.add(EntityAttributes.ATTACK_DAMAGE, 12.0)
				.add(EntityAttributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(EntityAttributes.FOLLOW_RANGE, 48.0);
	}

	@Override
	protected void initDataTracker(DataTracker.Builder builder) {
		super.initDataTracker(builder);
		builder.add(ANIM, 0);
	}

	/** Lo usa el renderer: brazos arriba durante ataques. */
	public boolean isRaised() {
		return this.dataTracker.get(ANIM) != 0;
	}

	private void setRaised(boolean raised) {
		this.dataTracker.set(ANIM, raised ? 1 : 0);
	}

	@Override
	protected void initGoals() {
		this.goalSelector.add(0, new SwimGoal(this));
		this.goalSelector.add(1, new MeleeAttackGoal(this, 1.0, true) {
			@Override
			public boolean canStart() {
				return BossEntity.this.attack == Attack.NONE && BossEntity.this.transition == 0 && super.canStart();
			}

			@Override
			public boolean shouldContinue() {
				return BossEntity.this.attack == Attack.NONE && BossEntity.this.transition == 0 && super.shouldContinue();
			}
		});
		this.goalSelector.add(5, new WanderAroundFarGoal(this, 0.8));
		this.goalSelector.add(6, new LookAtEntityGoal(this, PlayerEntity.class, 32.0f));
		this.goalSelector.add(7, new LookAroundGoal(this));
		this.targetSelector.add(1, new RevengeGoal(this));
		this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
	}

	// =====================================================================
	//  BARRA DE JEFE
	// =====================================================================
	@Override
	public void onStartedTrackingBy(ServerPlayerEntity player) {
		super.onStartedTrackingBy(player);
		this.bossBar.addPlayer(player);
	}

	@Override
	public void onStoppedTrackingBy(ServerPlayerEntity player) {
		super.onStoppedTrackingBy(player);
		this.bossBar.removePlayer(player);
	}

	@Override
	public void remove(RemovalReason reason) {
		this.bossBar.clearPlayers();
		super.remove(reason);
	}

	private void updateBar() {
		this.bossBar.setPercent((float) MathHelper.clamp(hp / maxHp, 0.0, 1.0));
		long hearts = (long) Math.ceil(Math.max(hp, 0) / 2.0);
		this.bossBar.setName(Text.literal("Coloso Ancestral - Fase " + phase + "/3   \u2665 " + hearts));
		this.bossBar.setColor(phase == 1 ? BossBar.Color.YELLOW : phase == 2 ? BossBar.Color.PURPLE : BossBar.Color.RED);
		// Barra con marcas cuando esta en la mitad de la vida (inmune a flechas)
		this.bossBar.setStyle(arrowImmune() ? BossBar.Style.NOTCHED_6 : BossBar.Style.PROGRESS);
	}

	private boolean arrowImmune() {
		return hp <= maxHp * ARROW_IMMUNE_BELOW;
	}

	// =====================================================================
	//  DANO Y FASES
	// =====================================================================
	@Override
	public boolean damage(ServerWorld world, DamageSource source, float amount) {
		// /kill y vacio siempre funcionan
		if (source.isIn(net.minecraft.registry.tag.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return super.damage(world, source, amount);
		}
		if (transition > 0 || iFrames > 0 || isRemoved()) {
			return false;
		}
		// Inmune a flechas por debajo de la mitad de la vida
		if (source.getSource() instanceof PersistentProjectileEntity proj
				&& !(proj instanceof TridentEntity) && arrowImmune()) {
			world.spawnParticles(ParticleTypes.ENCHANTED_HIT, proj.getX(), proj.getY(), proj.getZ(), 8, 0.2, 0.2, 0.2, 0.2);
			snd(world, SoundEvents.ITEM_SHIELD_BLOCK, 1.0f, 0.8f);
			return false;
		}

		hp -= amount;
		iFrames = 10;
		world.sendEntityStatus(this, (byte) 2); // animacion de golpe
		if (getTarget() == null && source.getAttacker() instanceof LivingEntity attacker) {
			setTarget(attacker);
		}

		if (hp <= 0) {
			if (phase < 3) {
				startTransition(world);
			} else {
				hp = 0;
				updateBar();
				this.setInvulnerable(false);
				return super.damage(world, source, Float.MAX_VALUE);
			}
		}
		return true;
	}

	private void startTransition(ServerWorld w) {
		endAttack();
		phase++;
		maxHp = PHASE_HEARTS[phase - 1] * 2;
		hp = 0;
		transition = 80;
		setRaised(true);
		getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).setBaseValue(SPEED[phase - 1]);
		snd(w, SoundEvents.ENTITY_ENDER_DRAGON_GROWL, 2.0f, 0.6f);
		snd(w, SoundEvents.ENTITY_WITHER_SPAWN, 1.0f, 1.2f);
		// Onda de rugido: aleja a los jugadores cercanos (sin dano)
		for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(14), this::validTarget)) {
			p.takeKnockback(2.0, getX() - p.getX(), getZ() - p.getZ());
			p.addVelocity(0, 0.5, 0);
			p.velocityModified = true;
		}
	}

	private void tickTransition(ServerWorld w) {
		transition--;
		getNavigation().stop();
		setVelocity(0, getVelocity().y, 0);
		// La vida se rellena poco a poco con la nueva barra
		hp = maxHp * (1.0 - transition / 80.0);
		double r = (80 - transition) * 0.25;
		ringParticles(w, phase == 2 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.FLAME, getPos(), r, 28, 0.1);
		w.spawnParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.5, getZ(), 6, 0.6, 1.2, 0.6, 0.02);
		if (transition % 20 == 0) {
			snd(w, SoundEvents.ENTITY_RAVAGER_ROAR, 1.5f, 0.7f);
		}
		if (transition == 0) {
			setRaised(false);
			cooldown = 30;
			forceSummon = true; // al empezar cada fase nueva invoca esbirros
			snd(w, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f);
			w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
		}
	}

	// =====================================================================
	//  TICK PRINCIPAL
	// =====================================================================
	@Override
	public void tick() {
		super.tick();
		if (!(getWorld() instanceof ServerWorld w)) {
			return;
		}
		if (iFrames > 0) iFrames--;
		updateBar();

		// Aura segun la fase
		if (age % 3 == 0 && phase >= 2) {
			w.spawnParticles(phase == 2 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.FLAME,
					getX(), getY() + 1.8, getZ(), 3, 0.5, 1.0, 0.5, 0.01);
		}
		if (age % 4 == 0 && arrowImmune()) {
			w.spawnParticles(ParticleTypes.ENCHANT, getX(), getY() + 1.8, getZ(), 4, 0.7, 1.2, 0.7, 0.3);
		}

		if (transition > 0) {
			tickTransition(w);
			return;
		}

		if (attack != Attack.NONE) {
			runAttack(w);
		} else if (cooldown > 0) {
			cooldown--;
		} else if (getTarget() != null && getTarget().isAlive()) {
			startAttack(w);
		}
	}

	private void startAttack(ServerWorld w) {
		LivingEntity t = getTarget();
		double d = distanceTo(t);

		List<Attack> pool = new ArrayList<>();
		if (d <= 5) { pool.add(Attack.FLURRY); pool.add(Attack.FLURRY); }
		if (d >= 3 && d <= 16) pool.add(Attack.THRUST);
		if (d >= 4 && d <= 26) pool.add(Attack.LEAP);
		if (phase >= 2 && d <= 34) pool.add(Attack.PILLARS);
		if (phase >= 3 && d <= 8) pool.add(Attack.WHIRL);
		boolean canSummon = phase >= 2 && d <= 40 && countMinions(w) < MINION_CAP[phase - 1];
		if (canSummon) pool.add(Attack.SUMMON);
		if (pool.isEmpty()) {
			cooldown = 10; // muy lejos: que lo persiga el goal normal
			return;
		}
		Attack pick = pool.get(random.nextInt(pool.size()));
		if (pick == lastAttack && pool.size() > 1) {
			pick = pool.get(random.nextInt(pool.size())); // evita repetir tanto
		}
		if (forceSummon && canSummon) { // al empezar una fase nueva siempre invoca primero
			pick = Attack.SUMMON;
			forceSummon = false;
		}

		attack = pick;
		attackTick = 0;
		hitSet.clear();
		setRaised(true);
		switch (pick) {
			case LEAP -> { leapsLeft = phase; leapStage = 0; } // fase 1: 1 salto, fase 2: 2, fase 3: 3 seguidos
			case PILLARS -> wavesLeft = phase == 3 ? 2 : 1;
			default -> { }
		}
	}

	private void runAttack(ServerWorld w) {
		getNavigation().stop();
		switch (attack) {
			case THRUST -> thrust(w);
			case LEAP -> leap(w);
			case FLURRY -> flurry(w);
			case PILLARS -> pillars(w);
			case WHIRL -> whirl(w);
			case SUMMON -> summon(w);
			default -> endAttack();
		}
		if (attack != Attack.NONE) {
			attackTick++;
		}
	}

	private void endAttack() {
		if (attack != Attack.NONE) lastAttack = attack;
		attack = Attack.NONE;
		attackTick = 0;
		setRaised(false);
		int base = COOLDOWN[phase - 1] + random.nextInt(10);
		// Ultimo 25% de la fase 3: furia (ataca mas rapido)
		if (phase == 3 && hp < maxHp * 0.25) base = (int) (base * 0.6);
		cooldown = base;
	}

	// =====================================================================
	//  ATAQUE 1: ESTOCADA  (telegrafia con linea de particulas y embestida)
	// =====================================================================
	private void thrust(ServerWorld w) {
		LivingEntity t = getTarget();
		if (attackTick < 14 && t != null) {
			dir = flat(t.getPos().subtract(getPos())).normalize();
			faceDir(dir);
		}
		if (attackTick < 20) {
			if (attackTick == 0) snd(w, SoundEvents.ENTITY_RAVAGER_ROAR, 1.0f, 1.3f);
			if (attackTick % 2 == 0) {
				for (int i = 1; i <= 10; i++) {
					Vec3d p = getPos().add(dir.multiply(i));
					w.spawnParticles(ParticleTypes.CRIT, p.x, p.y + 0.15, p.z, 1, 0.1, 0, 0.1, 0);
				}
			}
			w.spawnParticles(ParticleTypes.CLOUD, getX() - dir.x, getY() + 0.2, getZ() - dir.z, 2, 0.3, 0.1, 0.3, 0.02);
		} else if (attackTick < 30) {
			if (attackTick == 20) snd(w, SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1.5f, 0.6f);
			setVelocity(dir.x * 1.5, getVelocity().y, dir.z * 1.5);
			velocityModified = true;
			w.spawnParticles(ParticleTypes.SWEEP_ATTACK, getX() + dir.x, getY() + 1.4, getZ() + dir.z, 1, 0, 0, 0, 0);
			w.spawnParticles(ParticleTypes.CLOUD, getX(), getY() + 0.2, getZ(), 3, 0.4, 0.1, 0.4, 0.02);
			for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(1.2, 0.4, 1.2),
					pl -> validTarget(pl) && !hitSet.contains(pl.getUuid()))) {
				hitSet.add(p.getUuid());
				hitPlayer(w, p, 1.6, 1.4, 0.3);
			}
		} else {
			setVelocity(0, getVelocity().y, 0);
			endAttack();
		}
	}

	// =====================================================================
	//  ATAQUE 2: SALTO + ONDA EXPANSIVA (en fase 2 y 3 encadena 2 y 3 saltos)
	// =====================================================================
	private void leap(ServerWorld w) {
		LivingEntity t = getTarget();
		if (leapStage == 0) { // agacharse y apuntar
			if (t == null || !t.isAlive()) { endAttack(); return; }
			int duration = (leapsLeft == phase) ? 16 : 6;
			faceDir(flat(t.getPos().subtract(getPos())).normalize());
			if (attackTick == 0) snd(w, SoundEvents.ENTITY_RAVAGER_STEP, 1.5f, 0.5f);
			w.spawnParticles(ParticleTypes.CLOUD, getX(), getY() + 0.1, getZ(), 3, 0.7, 0.05, 0.7, 0.03);
			if (attackTick >= duration) {
				leapStart = getPos();
				leapEnd = new Vec3d(t.getX(), t.getY(), t.getZ());
				leapHeight = 6.0 + leapStart.distanceTo(leapEnd) * 0.15;
				leapStage = 1;
				attackTick = -1;
				snd(w, SoundEvents.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.5f);
			}
			return;
		}
		if (leapStage == 1) { // vuelo en arco
			final int total = 26;
			double f = (attackTick + 1) / (double) total;
			double x = MathHelper.lerp(f, leapStart.x, leapEnd.x);
			double z = MathHelper.lerp(f, leapStart.z, leapEnd.z);
			double y = MathHelper.lerp(f, leapStart.y, leapEnd.y) + 4.0 * leapHeight * f * (1.0 - f);
			refreshPositionAndAngles(x, y, z, getYaw(), getPitch());
			setVelocity(Vec3d.ZERO);
			fallDistance = 0;
			w.spawnParticles(ParticleTypes.FLAME, x, y + 0.5, z, 4, 0.4, 0.4, 0.4, 0.02);
			w.spawnParticles(ParticleTypes.CLOUD, x, y, z, 2, 0.3, 0.1, 0.3, 0.01);
			if (attackTick + 1 >= total) {
				leapStage = 2;
				shockTick = 0;
				hitSet.clear();
				attackTick = -1;
				snd(w, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
				snd(w, SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.2f);
				w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 0.5, z, 1, 0, 0, 0, 0);
			}
			return;
		}
		// Onda expansiva: anillo que crece; se esquiva SALTANDO justo cuando llega
		double r = 1.5 + shockTick * 1.3;
		BlockState below = w.getBlockState(getBlockPos().down());
		if (below.isAir()) below = Blocks.STONE.getDefaultState();
		ringParticles(w, new BlockStateParticleEffect(ParticleTypes.BLOCK, below), getPos().add(0, 0.15, 0), r, (int) (r * 8), 0.0);
		ringParticles(w, ParticleTypes.CLOUD, getPos().add(0, 0.3, 0), r, (int) (r * 3), 0.02);
		double gy = getY();
		for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(r + 2, 3, r + 2),
				pl -> validTarget(pl) && !hitSet.contains(pl.getUuid()))) {
			double dist = Math.hypot(p.getX() - getX(), p.getZ() - getZ());
			if (Math.abs(dist - r) <= 1.6 && p.getY() < gy + 1.4) {
				hitSet.add(p.getUuid());
				hitPlayer(w, p, 1.8, 1.8, 0.55);
			}
		}
		shockTick++;
		if (shockTick >= 14) {
			if (--leapsLeft > 0 && t != null && t.isAlive()) {
				leapStage = 0;
				attackTick = -1;
			} else {
				endAttack();
			}
		}
	}

	// =====================================================================
	//  ATAQUE 3: ATAQUE RAPIDO (6 golpes en abanico, pega a todos los cercanos)
	// =====================================================================
	private void flurry(ServerWorld w) {
		LivingEntity t = getTarget();
		if (t != null && t.isAlive()) {
			dir = flat(t.getPos().subtract(getPos())).normalize();
			faceDir(dir);
		}
		if (attackTick < 6) {
			if (attackTick == 0) snd(w, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.8f);
			w.spawnParticles(ParticleTypes.CRIT, getX() + dir.x, getY() + 1.6, getZ() + dir.z, 3, 0.3, 0.3, 0.3, 0.1);
			return;
		}
		int n = attackTick - 6;
		if (n % 4 == 0 && n / 4 < 6) {
			swingHand(Hand.MAIN_HAND);
			snd(w, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.9f + 0.05f * (n / 4));
			w.spawnParticles(ParticleTypes.SWEEP_ATTACK, getX() + dir.x * 1.8, getY() + 1.3, getZ() + dir.z * 1.8, 1, 0, 0, 0, 0);
			// pequeno avance hacia el objetivo en cada golpe
			if (t != null && distanceTo(t) > 2.5) {
				setVelocity(dir.x * 0.55, getVelocity().y, dir.z * 0.55);
				velocityModified = true;
			}
			for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(3.4, 0.6, 3.4), this::validTarget)) {
				Vec3d to = flat(p.getPos().subtract(getPos()));
				if (to.lengthSquared() < 0.0001 || to.normalize().dotProduct(dir) > -0.2) {
					hitPlayer(w, p, 0.55, 0.5, 0.15);
				}
			}
		}
		if (attackTick >= 34) endAttack();
	}

	// =====================================================================
	//  ATAQUE 4 (fase 2+): PILARES - marca la posicion de CADA jugador y explota
	// =====================================================================
	private void pillars(ServerWorld w) {
		LivingEntity t = getTarget();
		if (t != null) faceDir(flat(t.getPos().subtract(getPos())).normalize());

		if (attackTick == 0) {
			pillars.clear();
			snd(w, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.7f);
			for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(40), this::validTarget)) {
				pillars.add(groundPos(w, p.getPos()));
				if (phase >= 2) { // pilares extra alrededor para cortar la huida
					for (int i = 0; i < 2; i++) {
						Vec3d off = new Vec3d(random.nextDouble() * 10 - 5, 0, random.nextDouble() * 10 - 5);
						pillars.add(groundPos(w, p.getPos().add(off)));
					}
				}
			}
		}
		if (attackTick < 34) {
			if (attackTick % 2 == 0) {
				double growth = 0.4 + 2.0 * (attackTick / 34.0);
				for (Vec3d c : pillars) {
					ringParticles(w, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 0.1, 0), 2.5, 18, 0.0);
					w.spawnParticles(ParticleTypes.FLAME, c.x, c.y + 0.1, c.z, 3, growth * 0.3, 0.05, growth * 0.3, 0.01);
				}
			}
		} else if (attackTick == 34) {
			snd(w, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.2f, 0.9f);
			for (Vec3d c : pillars) {
				for (int h = 0; h < 9; h++) {
					w.spawnParticles(ParticleTypes.FLAME, c.x, c.y + h, c.z, 8, 0.5, 0.0, 0.5, 0.05);
				}
				w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 1, c.z, 2, 0.8, 0.5, 0.8, 0.0);
				for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, new Box(c.x - 2.6, c.y - 1, c.z - 2.6, c.x + 2.6, c.y + 3.5, c.z + 2.6), this::validTarget)) {
					if (Math.hypot(p.getX() - c.x, p.getZ() - c.z) <= 2.6) {
						hitPlayer(w, p, 1.7, 0.4, 0.9);
					}
				}
			}
		} else if (attackTick >= 44) {
			if (--wavesLeft > 0) {
				attackTick = -1; // segunda oleada (fase 3)
			} else {
				endAttack();
			}
		}
	}

	// =====================================================================
	//  ATAQUE 5 (fase 3): TORBELLINO - gira, atrae a todos y los lanza
	// =====================================================================
	private void whirl(ServerWorld w) {
		float yaw = getYaw() + 40f;
		setYaw(yaw); setBodyYaw(yaw); setHeadYaw(yaw);

		if (attackTick < 10) {
			if (attackTick == 0) snd(w, SoundEvents.ENTITY_RAVAGER_ROAR, 1.5f, 0.8f);
			ringParticles(w, ParticleTypes.CLOUD, getPos().add(0, 0.2, 0), 3 + attackTick * 0.3, 16, 0.02);
			return;
		}
		if (attackTick < 60) {
			double a = Math.toRadians(yaw);
			for (int i = 0; i < 3; i++) {
				double ang = a + i * (Math.PI * 2 / 3);
				w.spawnParticles(ParticleTypes.SWEEP_ATTACK, getX() + Math.cos(ang) * 3.5, getY() + 1.2, getZ() + Math.sin(ang) * 3.5, 1, 0, 0, 0, 0);
			}
			for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(12, 3, 12), this::validTarget)) {
				double dist = Math.hypot(p.getX() - getX(), p.getZ() - getZ());
				if (dist > 3.0) {
					Vec3d pull = flat(getPos().subtract(p.getPos())).normalize().multiply(0.12);
					p.addVelocity(pull.x, 0, pull.z);
					p.velocityModified = true;
				}
				if (attackTick % 4 == 0 && dist <= 4.4) {
					hitPlayer(w, p, 0.45, 0.3, 0.1);
				}
			}
			if (attackTick % 6 == 0) snd(w, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.7f);
		} else if (attackTick == 60) {
			snd(w, SoundEvents.ENTITY_GENERIC_EXPLODE, 1.6f, 0.6f);
			w.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, getX(), getY() + 1, getZ(), 1, 0, 0, 0, 0);
			ringParticles(w, ParticleTypes.FLAME, getPos().add(0, 0.3, 0), 6, 40, 0.1);
			for (PlayerEntity p : w.getEntitiesByClass(PlayerEntity.class, getBoundingBox().expand(10, 3, 10), this::validTarget)) {
				hitPlayer(w, p, 1.7, 2.0, 0.7);
			}
		} else if (attackTick >= 72) {
			endAttack();
		}
	}

	// =====================================================================
	//  ATAQUE 6 (fase 2+): INVOCAR ESBIRROS (esqueletos oscuro / consumido / fantasma)
	// =====================================================================
	private int countMinions(ServerWorld w) {
		return w.getEntitiesByClass(MinionEntity.class, getBoundingBox().expand(48), Entity::isAlive).size();
	}

	private void summon(ServerWorld w) {
		LivingEntity t = getTarget();
		if (t != null) faceDir(flat(t.getPos().subtract(getPos())).normalize());

		if (attackTick == 0) {
			pillars.clear();
			int amount = Math.min(MINION_BATCH[phase - 1], MINION_CAP[phase - 1] - countMinions(w));
			for (int i = 0; i < amount; i++) {
				double a = random.nextDouble() * Math.PI * 2;
				double r = 4 + random.nextDouble() * 3;
				pillars.add(groundPos(w, getPos().add(Math.cos(a) * r, 0, Math.sin(a) * r)));
			}
			snd(w, SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.6f);
		}
		if (attackTick < 40) {
			if (attackTick % 2 == 0) {
				for (Vec3d c : pillars) {
					ringParticles(w, ParticleTypes.SOUL_FIRE_FLAME, c.add(0, 0.1, 0), 1.2, 12, 0.0);
					w.spawnParticles(ParticleTypes.SOUL, c.x, c.y + 0.2 + attackTick * 0.04, c.z, 2, 0.2, 0.1, 0.2, 0.02);
				}
			}
		} else if (attackTick == 40) {
			snd(w, SoundEvents.ENTITY_WITHER_SPAWN, 0.6f, 1.6f);
			List<EntityType<MinionEntity>> kinds = new ArrayList<>();
			kinds.add(BossMod.MINION_OSCURO);
			kinds.add(BossMod.MINION_CONSUMIDO);
			if (phase >= 3) kinds.add(BossMod.MINION_FANTASMA);
			for (Vec3d c : pillars) {
				MinionEntity m = kinds.get(random.nextInt(kinds.size())).create(w, SpawnReason.MOB_SUMMONED);
				if (m == null) continue;
				m.refreshPositionAndAngles(c.x, c.y, c.z, random.nextFloat() * 360f, 0f);
				m.setBossId(getUuid());
				if (t != null) m.setTarget(t);
				w.spawnEntityAndPassengers(m);
				w.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y + 1, c.z, 20, 0.4, 0.8, 0.4, 0.05);
				w.spawnParticles(ParticleTypes.EXPLOSION, c.x, c.y + 0.5, c.z, 1, 0, 0, 0, 0);
			}
		} else if (attackTick >= 52) {
			endAttack();
		}
	}

	// =====================================================================
	//  GOLPE NORMAL (el que usa el goal de melee) 
	// =====================================================================
	@Override
	public boolean tryAttack(ServerWorld world, Entity target) {
		if (target instanceof PlayerEntity p) {
			swingHand(Hand.MAIN_HAND);
			hitPlayer(world, p, 1.0, 0.8, 0.25);
			return true;
		}
		return super.tryAttack(world, target);
	}

	/** Dano comun a todos los ataques. Si el jugador bloquea con escudo, se le desactiva 5 s. */
	private void hitPlayer(ServerWorld w, PlayerEntity p, double factor, double knockback, double up) {
		boolean blocking = p.isBlocking();
		ItemStack shield = p.getActiveItem();
		float dmg = (float) (getAttributeValue(EntityAttributes.ATTACK_DAMAGE) * DAMAGE_MULT[phase - 1] * factor);
		p.timeUntilRegen = 0; // el boss controla el ritmo: ignora los i-frames del jugador
		p.damage(w, w.getDamageSources().mobAttack(this), dmg);

		if (blocking && !shield.isEmpty()) {
			p.getItemCooldownManager().set(shield, SHIELD_DISABLE_TICKS);
			p.clearActiveItem();
			w.sendEntityStatus(p, (byte) 30); // sonido/animacion de escudo roto
		}
		p.takeKnockback(knockback, getX() - p.getX(), getZ() - p.getZ());
		p.addVelocity(0, up, 0);
		p.velocityModified = true;
	}

	// =====================================================================
	//  UTILIDADES
	// =====================================================================
	private boolean validTarget(PlayerEntity p) {
		return p.isAlive() && !p.isSpectator();
	}

	private static Vec3d flat(Vec3d v) {
		return new Vec3d(v.x, 0, v.z);
	}

	private void faceDir(Vec3d d) {
		if (d.lengthSquared() < 0.0001) return;
		float yaw = (float) (MathHelper.atan2(d.z, d.x) * 180.0 / Math.PI) - 90.0f;
		setYaw(yaw);
		setBodyYaw(yaw);
		setHeadYaw(yaw);
	}

	private Vec3d groundPos(ServerWorld w, Vec3d pos) {
		BlockPos.Mutable m = new BlockPos.Mutable(pos.x, pos.y, pos.z);
		int steps = 0;
		while (steps < 12 && m.getY() > w.getBottomY() && w.getBlockState(m).isAir()) {
			m.move(0, -1, 0);
			steps++;
		}
		double y = w.getBlockState(m).isAir() ? pos.y : m.getY() + 1.0;
		return new Vec3d(pos.x, y, pos.z);
	}

	private void ringParticles(ServerWorld w, ParticleEffect effect, Vec3d c, double r, int points, double speed) {
		points = Math.max(points, 8);
		for (int i = 0; i < points; i++) {
			double a = (Math.PI * 2 * i) / points;
			w.spawnParticles(effect, c.x + Math.cos(a) * r, c.y, c.z + Math.sin(a) * r, 1, 0, 0.15, 0, speed);
		}
	}

	private void snd(ServerWorld w, SoundEvent sound, float vol, float pitch) {
		w.playSound(null, getX(), getY(), getZ(), sound, SoundCategory.HOSTILE, vol, pitch);
	}

	private void snd(ServerWorld w, RegistryEntry<SoundEvent> sound, float vol, float pitch) {
		snd(w, sound.value(), vol, pitch);
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_IRON_GOLEM_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_IRON_GOLEM_DEATH;
	}

	@Override
	public boolean canImmediatelyDespawn(double distanceSquared) {
		return false;
	}

	// =====================================================================
	//  GUARDADO
	// =====================================================================
	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putInt("BossPhase", phase);
		nbt.putDouble("BossHp", hp);
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		phase = MathHelper.clamp(nbt.contains("BossPhase") ? nbt.getInt("BossPhase") : 1, 1, 3);
		maxHp = PHASE_HEARTS[phase - 1] * 2;
		hp = nbt.contains("BossHp") ? nbt.getDouble("BossHp") : maxHp;
		getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).setBaseValue(SPEED[phase - 1]);
		updateBar();
	}
}
