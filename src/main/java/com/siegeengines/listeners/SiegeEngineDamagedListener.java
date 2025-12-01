package com.siegeengines.listeners;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;

import com.siegeengines.SiegeEngines;
import com.siegeengines.SiegeEnginesLogger;
import com.siegeengines.config.Config;
import com.siegeengines.data.SiegeEnginesData;
import com.siegeengines.projectile.ExplosiveProjectile;
import com.siegeengines.util.GeneralUtil;
import com.siegeengines.util.SiegeEnginesUtil;

public class SiegeEngineDamagedListener implements Listener {
    
    @EventHandler(priority = EventPriority.HIGH)
    public void onSiegeEngineHitByProjectile(ProjectileHitEvent event) {
        // Primera parte: Daño a máquinas de asedio por flechas/proyectiles
        if ((event.getEntity() instanceof Projectile)) {
            for (Entity entity : event.getEntity().getNearbyEntities(2, 2, 2)) {
                if (entity instanceof ArmorStand) {
                    ArmorStand stand = (ArmorStand) entity;
                    if (event.isCancelled()) return;
                    if (SiegeEnginesUtil.isSiegeEngine(stand,false)) {
                        event.setCancelled(true);
                        SiegeEnginesLogger.debug("ARROW DAMAGE CANCELLED? : " + Config.arrowDamageToggle);
                        if (Config.arrowDamageToggle) {
                            continue;
                        }
                        SiegeEnginesLogger.debug("HEALTH BEFORE SHOT : " + stand.getHealth());
                        if (stand.getHealth()-2 > 0) {
                            stand.setHealth(stand.getHealth()-2);
                        } else {
                            var dmg_builder = DamageSource.builder(DamageType.ARROW);
                            EntityDeathEvent death = new EntityDeathEvent(stand, dmg_builder.build(), SiegeEnginesData.items, 0);
                            Bukkit.getServer().getPluginManager().callEvent(death);
                            if (!death.isCancelled()) stand.setHealth(0.0f);
                        }
                        SiegeEnginesLogger.debug("HEALTH AFTER SHOT : " + stand.getHealth());
                        if (stand.isDead()) {
                            PlayerHandler.siegeEngineEntityDied(stand);
                        }
                    }
                }
            }
        }
        
        // Segunda parte: Proyectiles explosivos del plugin
        if ((event.getEntity() instanceof Projectile)
                && SiegeEnginesData.projectiles.containsKey(event.getEntity().getUniqueId())) {
            
            ExplosiveProjectile proj = SiegeEnginesData.projectiles.get(event.getEntity().getUniqueId());
            Entity snowball = event.getEntity();
            org.bukkit.entity.Projectile ball = (org.bukkit.entity.Projectile) snowball;
            Entity player = (Entity) ball.getShooter();
            
            if (player instanceof Player) {
                player.sendMessage("§eDistance to impact: "
                        + String.format("%.2f", player.getLocation().distance(ball.getLocation())));
            }
            
            Location loc = snowball.getLocation();
            SiegeEnginesData.projectiles.remove(event.getEntity().getUniqueId());
            
            // SIMPLIFICADO: Siempre usamos explosión personalizada, sin comprobar WorldGuard
            if (proj.placeBlocks) {
                // Lógica para colocar bloques
                if (event.getHitBlock() != null) {
                    List<Block> Blocks = GeneralUtil.getSphere(event.getHitBlock().getLocation(), (int) proj.explodePower);
                    for (int i = 0; i < proj.blocksToPlaceAmount; i++) {
                        Block replace = (Block) GeneralUtil.getRandomElement(Blocks);
                        replace.setType(proj.blockToPlace);
                    }
                }
                // También efectos visuales al colocar bloques
                playExplosionEffects(loc, proj.explodePower, proj.soundType, proj.particleType, false);
            } else {
                // Explosión personalizada completa
                createCustomExplosion(loc, proj.explodePower, proj.soundType, proj.particleType);
            }
        }
    }
    
    @EventHandler(priority = EventPriority.NORMAL)
    public void onSiegeEngineDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ArmorStand) {
            ArmorStand stand = (ArmorStand)event.getEntity();
            // CORRECCIÓN: Solo procesar si ES una máquina de asedio
            if (!SiegeEnginesUtil.isSiegeEngine(stand,false)) return;
            if (event.isCancelled()) return;
            if (event.getDamager() instanceof Player) {
                PlayerHandler.releasePlayerSiegeEngine((Player)(event.getDamager()),event.getEntity());
            }
            SiegeEnginesLogger.debug("HEALTH BEFORE HIT : "+stand.getHealth());
            if (stand.getHealth()-2 > 0) {
                stand.setHealth(stand.getHealth()-2);
            } else {
                var dmg_builder = DamageSource.builder(DamageType.GENERIC);
                EntityDeathEvent death = new EntityDeathEvent(stand, dmg_builder.build(), SiegeEnginesData.items, 0);
                Bukkit.getServer().getPluginManager().callEvent(death);
                if (death.isCancelled()) return;
                stand.setHealth(0.0f);
                PlayerHandler.siegeEngineEntityDied(event.getEntity());
            }
            SiegeEnginesLogger.debug("HEALTH AFTER HIT : "+stand.getHealth());
        }
    }
    
    // ========== SISTEMA DE EXPLOSIÓN PERSONALIZADA ==========
    
    /**
     * Crea una explosión personalizada que simula TNT pero no usa la entidad TNTPrimed
     */
    private void createCustomExplosion(Location center, float power, Sound soundType, Particle particleType) {
        World world = center.getWorld();
        if (world == null) return;
        
        // 1. Efectos visuales y auditivos
        playExplosionEffects(center, power, soundType, particleType, true);
        
        // 2. Daño a entidades
        applyExplosionDamage(center, power);
        
        // 3. Destrucción de bloques
        applyExplosionBlockDamage(center, power);
    }
    
    /**
     * Aplica daño a entidades cercanas (similar a TNT)
     */
    private void applyExplosionDamage(Location center, float power) {
    World world = center.getWorld();
    if (world == null) return;
    
    // Radio de daño = ExplodePower × 2.5
    double damageRadius = power * 2.5;
    
    for (Entity entity : world.getNearbyEntities(center, damageRadius, damageRadius, damageRadius)) {
        if (entity instanceof org.bukkit.entity.Damageable && !(entity instanceof ArmorStand)) {
            double distance = entity.getLocation().distance(center);
            if (distance < damageRadius) {
                // Daño = ExplodePower × 5, reducido por distancia
                double damage = (power * 5) * (1.0 - (distance / damageRadius));
                if (damage > 0.5) {
                    ((org.bukkit.entity.Damageable) entity).damage(damage);
                    
                    // Empuje proporcional a ExplodePower
                    Vector direction = entity.getLocation().toVector().subtract(center.toVector()).normalize();
                    entity.setVelocity(direction.multiply(power * 0.5));
					}
				}
			}
		}
	}
		
    /**
     * Destruye bloques en un patrón aleatorio pero realista
     */
    private void applyExplosionBlockDamage(Location center, float power) {
    World world = center.getWorld();
    if (world == null) return;
    
    // Radio de destrucción = ExplodePower
    int blockRadius = (int) Math.ceil(power);
    if (blockRadius <= 0) return;
    
    // Número de bloques a destruir = ExplodePower × 15
    int blocksToDestroy = (int) (power * 15);
    blocksToDestroy = Math.min(blocksToDestroy, 100); // Límite máximo
    
    int blocksDestroyed = 0;
    
    for (int i = 0; i < blocksToDestroy * 2; i++) {
        if (blocksDestroyed >= blocksToDestroy) break;
        
        // Coordenadas aleatorias dentro del radio
        int dx = (int) ((Math.random() * 2 - 1) * blockRadius);
        int dy = (int) ((Math.random() * 2 - 1) * blockRadius);
        int dz = (int) ((Math.random() * 2 - 1) * blockRadius);
        
        int x = center.getBlockX() + dx;
        int y = center.getBlockY() + dy;
        int z = center.getBlockZ() + dz;
        
        y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, y));
        
        Block block = world.getBlockAt(x, y, z);
        
        if (canDestroyBlock(block)) {
            // Probabilidad basada en distancia
            double distance = center.distance(block.getLocation());
            if (Math.random() < (1.0 - (distance / (blockRadius * 2)))) {
                if (Math.random() > 0.2) {
                    block.breakNaturally();
                } else {
                    block.setType(Material.AIR);
                }
                blocksDestroyed++;
            }
        }
    }
    
    SiegeEnginesLogger.debug("Bloques destruidos: " + blocksDestroyed + " (power=" + power + ")");
	}
    
    /**
     * Reproduce efectos de explosión (sonido, partículas)
     */
    private void playExplosionEffects(Location center, float power, Sound soundType, Particle particleType, boolean fullEffects) {
        World world = center.getWorld();
        if (world == null) return;
        
        // Sonido de explosión (usar el definido en el proyectil o uno por defecto)
        Sound sound = (soundType != null) ? soundType : Sound.ENTITY_GENERIC_EXPLODE;
        world.playSound(center, sound, 4.0F, 
                       (1.0F + (SiegeEngines.random.nextFloat() - SiegeEngines.random.nextFloat()) * 0.2F) * 0.7F);
        
        // Partículas
        int particleCount = (int) (power * 15);
        
        // Partícula principal (usar la definida en el proyectil o una por defecto)
        Particle mainParticle = (particleType != null) ? particleType : Particle.EXPLOSION;
        world.spawnParticle(Particle.EXPLOSION, center, 1);
        world.spawnParticle(mainParticle, center, particleCount / 2, power, power, power, 0.1);
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, particleCount, power, power, power, 0.05);
        
        if (fullEffects) {
            // Efectos adicionales para explosión completa
            world.spawnParticle(Particle.FLAME, center, particleCount / 3, power, power, power, 0.02);
            
            // Destello breve de luz (opcional, para versiones que soportan el bloque LIGHT)
            try {
                if (world.getBlockAt(center).getType() == Material.AIR) {
                    world.getBlockAt(center).setType(Material.LIGHT);
                    // Eliminar la luz después de 2 ticks
                    Bukkit.getScheduler().runTaskLater(SiegeEngines.getInstance(), () -> {
                        if (world.getBlockAt(center).getType() == Material.LIGHT) {
                            world.getBlockAt(center).setType(Material.AIR);
                        }
                    }, 2L);
                }
            } catch (Exception e) {
                // El bloque LIGHT puede no estar disponible en algunas versiones
            }
        }
    }
    
    /**
     * Determina si un bloque puede ser destruido por la explosión
     */
    private boolean canDestroyBlock(Block block) {
    Material type = block.getType();
    
    // Usar lista de configuración
    if (Config.indestructibleMaterials.contains(type)) {
        return false;
    }
    
    // No destruir aire o líquidos
    if (type.isAir() || type == Material.WATER || type == Material.LAVA) {
        return false;
    }
    
    return true;
	}
}