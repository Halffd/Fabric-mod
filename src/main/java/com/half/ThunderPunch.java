package com.half;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Blocks;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.OcelotEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThunderPunch implements ModInitializer {
    public static final String MOD_ID = "thunderpunch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Constants
    private static final double RAYCAST_RANGE = 500.0; // Blocks
    private static final float BASE_DAMAGE_MULTIPLIER = 3.0f;
    private static final float EXPLOSION_CHANCE = 0.13f;
    private static final float LAVA_SPAWN_CHANCE = 0.3f;
    private static final LocalDate MINECRAFT_RELEASE_DATE = LocalDate.of(2011, 7, 11);

    // Thread-safe components
    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(2);
    private static final AtomicBoolean CREEPER_SPAWNING_ENABLED = new AtomicBoolean(false);
    private static final Object SPAWN_LOCK = new Object();

    @Override
    public void onInitialize() {
        LOGGER.info("ThunderPunch v2.0 Enterprise Edition loaded!");

        registerEventHandlers();

        // Shutdown hook for cleanup
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    private void registerEventHandlers() {
        // Empty hand right-click trigger
        UseItemCallback.EVENT.register(this::handleEmptyHandUse);

        // Block right-click trigger
        UseBlockCallback.EVENT.register(this::handleBlockUse);

        // Attack entity trigger
        AttackEntityCallback.EVENT.register(this::handleEntityAttack);
    }

    private ActionResult handleEmptyHandUse(PlayerEntity player, World world, net.minecraft.util.Hand hand) {
        if (world.isClient || !player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }

        performExplosiveRaycast(world, player);
        return ActionResult.SUCCESS;
    }

    private ActionResult handleBlockUse(PlayerEntity player, World world, net.minecraft.util.Hand hand, BlockHitResult hitResult) {
        if (world.isClient || !player.getStackInHand(hand).isEmpty()) {
            return ActionResult.PASS;
        }

        if (player.isSneaking()) {
            performExplosiveRaycast(world, player);
            return ActionResult.SUCCESS;
        }

        return ActionResult.PASS;
    }

    private ActionResult handleEntityAttack(PlayerEntity player, World world, net.minecraft.util.Hand hand, Entity target, net.minecraft.util.hit.EntityHitResult hitResult) {
        if (world.isClient || !(target instanceof LivingEntity livingTarget) || target == player) {
            return ActionResult.PASS;
        }

        try {
            processAttack(player, world, livingTarget);
            return ActionResult.SUCCESS;
        } catch (Exception e) {
            LOGGER.error("Error processing attack", e);
            return ActionResult.FAIL;
        }
    }

    private void processAttack(PlayerEntity player, World world, LivingEntity target) {
        // Apply defensive buffs
        applyDefensiveBuff(player);

        // Calculate and apply damage
        float damage = calculateDamage(player);
        applyDamage(world, player, target, damage);

        // Apply effects based on time and date
        applyTimeBasedEffects(world, player, target);

        // Spawn additional entities
        spawnAdditionalEntities(world, player);

        // Apply random effects
        applyRandomEffects(world, player);

        // Check for kill and trigger spawning
        if (target.getHealth() <= 0) {
            enableCreeperSpawning(world, player);
        }
    }

    private void applyDefensiveBuff(PlayerEntity player) {
        if (player.getArmor() == 0) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 600, 1));
        }
    }

    private float calculateDamage(PlayerEntity player) {
        double baseAttack = 1.0;
        var attr = player.getAttributeInstance(EntityAttributes.ATTACK_KNOCKBACK);
        if (attr != null) {
            baseAttack = attr.getValue();
        }
        return (float) (baseAttack * BASE_DAMAGE_MULTIPLIER);
    }

    private void applyDamage(World world, PlayerEntity player, LivingEntity target, float damage) {
        DamageSource source = world.getDamageSources().playerAttack(player);
        target.damage((ServerWorld) world, source, damage);

        // Apply knockback
        double knockback = 2.5;
        double yawRad = Math.toRadians(player.getYaw());
        target.takeKnockback(knockback, -Math.sin(yawRad), Math.cos(yawRad));

        // Extra damage for burning targets
        if (target.isOnFire()) {
            target.damage((ServerWorld) world, source, damage * 2.0f);
        }

        // Lightning strike on target
        spawnLightning(world, target.getX(), target.getY(), target.getZ());

        // Explosion chance
        if (ThreadLocalRandom.current().nextFloat() < EXPLOSION_CHANCE) {
            BlockPos pos = target.getBlockPos();
            world.createExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 2.0f, World.ExplosionSourceType.NONE);
        }
    }

    private void applyTimeBasedEffects(World world, PlayerEntity player, LivingEntity target) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate date = now.toLocalDate();
        LocalTime time = now.toLocalTime();

        // Time-based world effects
        applyTimeOfDayEffects(world, player, time);

        // Day-of-week effects
        applyDayOfWeekEffects(world, player, target, date.getDayOfWeek().getValue());

        // Special date effects
        applySpecialDateEffects(world, player, date);

        // Hunger management
        manageFoodLevel(world, player, time);
    }

    private void applyTimeOfDayEffects(World world, PlayerEntity player, LocalTime time) {
        int hour = time.getHour();
        String timeMessage;
        long worldTime;

        if (hour >= 6 && hour < 12) {
            timeMessage = "Good Morning! Time is " + time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            worldTime = 1000; // Dawn
        } else if (hour >= 12 && hour < 18) {
            timeMessage = "Good Afternoon! Time is " + time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            worldTime = 6000; // Noon
        } else if (hour >= 18 && hour < 22) {
            timeMessage = "Good Evening! Time is " + time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            worldTime = 12000; // Dusk
        } else {
            timeMessage = "Good Night! Time is " + time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            worldTime = 18000; // Midnight
        }

        sendMessage(player, timeMessage);
        if (world instanceof ServerWorld) {
            ((ServerWorld) world).setTimeOfDay(worldTime);
        }
    }

    private void applyDayOfWeekEffects(World world, PlayerEntity player, LivingEntity target, int dayOfWeek) {
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();

        switch (dayOfWeek) {
            case 1: // Monday - Tigers (Ocelots)
                spawnOcelot(world, x, y, z);
                break;
            case 2: // Tuesday - Spiders
                spawnSpider(world, x, y, z);
                break;
            case 3: // Wednesday - Zombie Villagers
                spawnZombieVillager(world, x, y, z);
                break;
            case 4: // Thursday - Blazes
                spawnBlaze(world, x, y, z);
                break;
            case 5: // Friday - Witches + Regeneration
                spawnWitch(world, x, y, z);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 600, 0));
                break;
            case 6: // Saturday - Wolves + Easy mode
            case 7: // Sunday - Wolves + Easy mode
                spawnWolf(world, x, y, z);
                player.setHealth(20.0f);
                break;
        }
    }

    private void applySpecialDateEffects(World world, PlayerEntity player, LocalDate date) {
        long secondsSinceRelease = ChronoUnit.SECONDS.between(MINECRAFT_RELEASE_DATE.atStartOfDay(), date.atStartOfDay());

        if (secondsSinceRelease % 2011 == 0) {
            enableCreeperSpawning(world, player);
        } else {
            spawnVillager(world, player);
        }
    }

    private void manageFoodLevel(World world, PlayerEntity player, LocalTime time) {
        if (world.getTime() % 30 == 0) {
            int second = time.getSecond();
            if (second % 2 == 0) {
                player.getHungerManager().add(1, 1.0f);
            } else {
                player.getHungerManager().add(0, 0.5f);
            }
        }
    }

    private void spawnAdditionalEntities(World world, PlayerEntity player) {
        // Spawn 1-2 zombies
        int zombieCount = 1 + ThreadLocalRandom.current().nextInt(2);
        for (int i = 0; i < zombieCount; i++) {
            spawnZombieNearPlayer(world, player);
        }

        // Spawn lava randomly
        if (ThreadLocalRandom.current().nextFloat() < LAVA_SPAWN_CHANCE) {
            spawnLavaNearPlayer(world, player);
        }
    }

    private void applyRandomEffects(World world, PlayerEntity player) {
        var random = ThreadLocalRandom.current();

        // 25% healing
        if (random.nextFloat() < 0.25f) {
            player.heal(random.nextFloat() * 2.0f + 0.5f);
        }

        // 5% speed boost
        if (random.nextFloat() < 0.05f) {
            player.addVelocity(0.0, 0.2, 0.0);
        }

        // 5% night vision
        if (random.nextFloat() < 0.05f) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, 200, 0));
        }

        // 5% regeneration
        if (random.nextFloat() < 0.05f) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.REGENERATION, 200, 0));
        }

        // 1% hunger
        if (random.nextFloat() < 0.01f) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 200, 0));
        }
    }

    private void performExplosiveRaycast(World world, PlayerEntity player) {
        if (world.isClient) return;

        try {
            RaycastResult result = performRaycast(world, player);
            executeExplosiveAttack(world, player, result);
        } catch (Exception e) {
            LOGGER.error("Error performing explosive raycast", e);
        }
    }

    private RaycastResult performRaycast(World world, PlayerEntity player) {
        Vec3d startPos = player.getEyePos();
        Vec3d lookDir = player.getRotationVector();
        Vec3d endPos = startPos.add(lookDir.multiply(RAYCAST_RANGE));
        BlockPos blockPos = player.getBlockPos();
        Direction direction = player.getHorizontalFacing();
        BlockPos endBlockPos = blockPos.offset(direction);
        // Entity raycast
        Box searchBox = Box.enclosing(blockPos, endBlockPos);
        EntityHitResult entityHit = ProjectileUtil.raycast(
                player, startPos, endPos, searchBox,
                entity -> entity != player && !entity.isSpectator(),
                RAYCAST_RANGE * RAYCAST_RANGE
        );

        if (entityHit != null) {
            return new RaycastResult(entityHit.getPos(), entityHit.getEntity(), RaycastResult.Type.ENTITY);
        }

        // Block raycast
        BlockHitResult blockHit = world.raycast(new RaycastContext(
                startPos, endPos,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (blockHit.getType() != HitResult.Type.MISS) {
            return new RaycastResult(blockHit.getPos(), null, RaycastResult.Type.BLOCK);
        }

        return new RaycastResult(endPos, null, RaycastResult.Type.MISS);
    }

    private void executeExplosiveAttack(World world, PlayerEntity player, RaycastResult result) {
        Vec3d hitPos = result.getHitPos();
        Entity hitEntity = result.getEntity();

        // Log the hit
        LOGGER.info("Explosive raycast hit {} at distance {}",
                result.getType().name(),
                player.getEyePos().distanceTo(hitPos));

        // Create explosion with random power
        float explosionPower = ThreadLocalRandom.current().nextFloat() * 15.0f + 5.0f;
        world.createExplosion(player, hitPos.x, hitPos.y, hitPos.z, explosionPower, World.ExplosionSourceType.TNT);

        // Handle entity damage
        if (hitEntity instanceof LivingEntity livingEntity) {
            applyRaycastDamage(world, player, livingEntity, hitPos);
        }

        // Visual and audio effects
        createExplosionEffects(world, player.getEyePos(), hitPos);
    }

    private void applyRaycastDamage(World world, PlayerEntity player, LivingEntity target, Vec3d hitPos) {
        DamageSource source = world.getDamageSources().playerAttack(player);
        target.damage((ServerWorld) world, source, 100.0f);

        // Epic knockback
        Vec3d knockbackDir = hitPos.subtract(player.getEyePos()).normalize();
        float knockbackStrength = ThreadLocalRandom.current().nextFloat() * 10.0f + 5.0f;
        target.takeKnockback(knockbackStrength, -knockbackDir.x, -knockbackDir.z);

        // Lightning strike
        spawnLightning(world, target.getX(), target.getY(), target.getZ());
    }

    private void createExplosionEffects(World world, Vec3d startPos, Vec3d hitPos) {
        // Lightning at impact
        spawnLightning(world, hitPos.x, hitPos.y, hitPos.z);

        // Sound effects
        world.playSound(null, hitPos.x, hitPos.y, hitPos.z,
                SoundEvents.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 3.0f, 0.3f);

        world.playSound(null, hitPos.x, hitPos.y, hitPos.z,
                SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 2.0f, 1.0f);

        // Particle trail
        createParticleTrail(world, startPos, hitPos);
    }

    private void createParticleTrail(World world, Vec3d start, Vec3d end) {
        if (world.isClient) return;

        Vec3d direction = end.subtract(start).normalize();
        double distance = start.distanceTo(end);
        double step = Math.max(1.0, distance / 100.0); // Optimize particle count

        for (double i = 0; i < distance; i += step) {
            Vec3d particlePos = start.add(direction.multiply(i));

            ((ServerWorld) world).spawnParticles(
                    ParticleTypes.EXPLOSION,
                    particlePos.x, particlePos.y, particlePos.z,
                    2, 0.1, 0.1, 0.1, 0.01
            );

            // Add some flame particles for extra effect
            if (i % (step * 3) == 0) {
                ((ServerWorld) world).spawnParticles(
                        ParticleTypes.FLAME,
                        particlePos.x, particlePos.y, particlePos.z,
                        1, 0.05, 0.05, 0.05, 0.01
                );
            }
        }
    }

    // Creeper spawning system
    private void enableCreeperSpawning(World world, PlayerEntity player) {
        synchronized (SPAWN_LOCK) {
            if (CREEPER_SPAWNING_ENABLED.compareAndSet(false, true)) {
                scheduleCreeperSpawn(world, player);
            }
        }
    }

    private void scheduleCreeperSpawn(World world, PlayerEntity player) {
        int delaySeconds = ThreadLocalRandom.current().nextInt(40, 191); // 40-190 seconds

        SCHEDULER.schedule(() -> {
            try {
                if (CREEPER_SPAWNING_ENABLED.get() && !world.isClient) {
                    spawnCreeper(world, player);
                }
            } catch (Exception e) {
                LOGGER.error("Error spawning creeper", e);
            } finally {
                CREEPER_SPAWNING_ENABLED.set(false);
            }
        }, delaySeconds, TimeUnit.SECONDS);

        LOGGER.info("Creeper scheduled to spawn in {} seconds", delaySeconds);
    }

    // Entity spawning methods
    private void spawnCreeper(World world, PlayerEntity player) {
        Vec3d spawnPos = calculateSafeSpawnPosition(world, player, 8.0, 20.0);

        CreeperEntity creeper = new CreeperEntity(EntityType.CREEPER, world);
        creeper.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z,
                ThreadLocalRandom.current().nextFloat() * 360f, 0);

        world.spawnEntity(creeper);

        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_CREEPER_PRIMED, SoundCategory.HOSTILE, 1.0f, 1.0f);

        LOGGER.info("Creeper spawned at {}", spawnPos);
    }

    private void spawnZombieNearPlayer(World world, PlayerEntity player) {
        Vec3d spawnPos = calculateSafeSpawnPosition(world, player, 2.0, 6.0);

        ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
        zombie.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z,
                ThreadLocalRandom.current().nextFloat() * 360f, 0);

        world.spawnEntity(zombie);
    }

    private void spawnLavaNearPlayer(World world, PlayerEntity player) {
        Vec3d playerPos = player.getPos();
        var random = ThreadLocalRandom.current();

        double dx = (random.nextDouble() - 0.5) * 6;
        double dz = (random.nextDouble() - 0.5) * 6;

        BlockPos lavaPos = new BlockPos((int)(playerPos.x + dx), (int)playerPos.y, (int)(playerPos.z + dz));

        if (world.getBlockState(lavaPos).isAir()) {
            world.setBlockState(lavaPos, Blocks.LAVA.getDefaultState());
        }
    }

    private Vec3d calculateSafeSpawnPosition(World world, PlayerEntity player, double minDistance, double maxDistance) {
        var random = ThreadLocalRandom.current();
        double distance = minDistance + random.nextDouble() * (maxDistance - minDistance);
        double angle = random.nextDouble() * 2 * Math.PI;

        double x = player.getX() + Math.cos(angle) * distance;
        double z = player.getZ() + Math.sin(angle) * distance;
        double y = world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, (int)x, (int)z);

        return new Vec3d(x, y, z);
    }

    // Mob spawning methods
    private void spawnVillager(World world, PlayerEntity player) {
        Vec3d spawnPos = calculateSafeSpawnPosition(world, player, 3.0, 8.0);

        VillagerEntity villager = new VillagerEntity(EntityType.VILLAGER, world);
        villager.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, 0, 0);
        world.spawnEntity(villager);
    }

    private void spawnWolf(World world, double x, double y, double z) {
        WolfEntity wolf = new WolfEntity(EntityType.WOLF, world);
        wolf.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(wolf);
    }

    private void spawnOcelot(World world, double x, double y, double z) {
        OcelotEntity ocelot = new OcelotEntity(EntityType.OCELOT, world);
        ocelot.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(ocelot);
    }

    private void spawnSpider(World world, double x, double y, double z) {
        SpiderEntity spider = new SpiderEntity(EntityType.SPIDER, world);
        spider.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(spider);
    }

    private void spawnZombieVillager(World world, double x, double y, double z) {
        ZombieVillagerEntity zombieVillager = new ZombieVillagerEntity(EntityType.ZOMBIE_VILLAGER, world);
        zombieVillager.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(zombieVillager);
    }

    private void spawnBlaze(World world, double x, double y, double z) {
        BlazeEntity blaze = new BlazeEntity(EntityType.BLAZE, world);
        blaze.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(blaze);
    }

    private void spawnWitch(World world, double x, double y, double z) {
        WitchEntity witch = new WitchEntity(EntityType.WITCH, world);
        witch.refreshPositionAndAngles(x, y, z, 0, 0);
        world.spawnEntity(witch);
    }

    private void spawnLightning(World world, double x, double y, double z) {
        LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        lightning.setPosition(x, y, z);
        world.spawnEntity(lightning);
    }

    // Utility methods
    private void sendMessage(PlayerEntity player, String message) {
        if (player instanceof ServerPlayerEntity serverPlayer) {
            serverPlayer.sendMessage(Text.literal(message), false);
        }
    }

    private void shutdown() {
        LOGGER.info("Shutting down ThunderPunch...");
        SCHEDULER.shutdown();
        try {
            if (!SCHEDULER.awaitTermination(5, TimeUnit.SECONDS)) {
                SCHEDULER.shutdownNow();
                LOGGER.warn("Forced shutdown of scheduler");
            }
        } catch (InterruptedException e) {
            SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("ThunderPunch shutdown complete");
    }

    // Inner classes
    private static class RaycastResult {
        public enum Type { ENTITY, BLOCK, MISS }

        private final Vec3d hitPos;
        private final Entity entity;
        private final Type type;

        public RaycastResult(Vec3d hitPos, Entity entity, Type type) {
            this.hitPos = hitPos;
            this.entity = entity;
            this.type = type;
        }

        public Vec3d getHitPos() { return hitPos; }
        public Entity getEntity() { return entity; }
        public Type getType() { return type; }
    }
}