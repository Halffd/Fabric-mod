package com.half;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

public class ThunderPunch implements ModInitializer {
    public static final String MOD_ID = "modid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Random RNG = new Random();

    @Override
    public void onInitialize() {
        LOGGER.info("ThunderPunch loaded!");

        AttackEntityCallback.EVENT.register((player, world, hand, target, hitResult) -> {
            if (world.isClient || !(target instanceof LivingEntity livingTarget)) return ActionResult.PASS;

            // Safely get base attack damage
            double base = 1.0;
            var attr = player.getAttributeInstance(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            if (attr != null) base = attr.getValue();

            float totalDamage = (float) (base * 3.0);
            RegistryEntry<DamageType> damageType = world.getRegistryManager()
                    .get(Registries.DAMAGE_TYPE)
                    .getEntry(DamageTypes.PLAYER_ATTACK)
                    .orElseThrow();

            DamageSource source = new DamageSource(damageType, player);
            livingTarget.damage((ServerWorld) world, source, totalDamage);

            // Knockback
            double knockback = 2.5;
            double yawRad = Math.toRadians(player.getYaw());
            livingTarget.takeKnockback(knockback, -Math.sin(yawRad), Math.cos(yawRad));

            // 3% explosion chance
            if (RNG.nextFloat() < 0.03f) {
                BlockPos pos = player.getBlockPos();
                world.createExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 2.0f, Explosion.DestructionType.BREAK);
            }

            // Lightning bolt on hit
            spawnLightning(world, player.getX(), player.getY(), player.getZ());

            // Spawn 1-2 zombies around player
            int count = 1 + RNG.nextInt(2);
            for (int i = 0; i < count; i++) {
                double dx = (RNG.nextDouble() - 0.5) * 6;
                double dz = (RNG.nextDouble() - 0.5) * 6;
                ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
                zombie.refreshPositionAndAngles(player.getX() + dx, player.getY(), player.getZ() + dz, RNG.nextFloat() * 360f, 0);
                world.spawnEntity(zombie);
            }

            // Punch sound
            world.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.ENTITY_PLAYER_ATTACK_STRONG,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);

            // Extra damage if target is burning
            if (livingTarget.isOnFire()) {
                livingTarget.damage(source, totalDamage * 2.0f);
            }

            // Lightning on nearby burning zombies
            Box area = new Box(player.getBlockPos()).expand(3.0);
            List<ZombieEntity> flamingZombies = world.getEntitiesByClass(ZombieEntity.class, area, ZombieEntity::isOnFire);
            for (ZombieEntity zombie : flamingZombies) {
                spawnLightning(world, zombie.getX(), zombie.getY(), zombie.getZ());
            }

            return ActionResult.FAIL; // cancel vanilla attack
        });
    }

    private void spawnLightning(World world, double x, double y, double z) {
        LightningEntity lightning = new LightningEntity(EntityType.LIGHTNING_BOLT, world);
        lightning.setPosition(x, y, z);
        world.spawnEntity(lightning);
    }
}
