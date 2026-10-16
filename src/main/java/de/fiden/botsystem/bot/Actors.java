package de.fiden.botsystem.bot;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

public final class Actors {
    private static final String ACTOR_CLASS = "mchorse.bbs_mod.entity.ActorEntity";
    private static ServerLevel cachedWorld;
    private static long cachedTick = -1L;
    private static List<LivingEntity> cached = List.of();

    private Actors() {
    }

    public static boolean enabled() {
        return BotDefaults.get().attackActors;
    }

    public static boolean isActor(Entity entity) {
        return entity instanceof LivingEntity && entity.getClass().getName().equals("mchorse.bbs_mod.entity.ActorEntity");
    }

    public static List<LivingEntity> all(ServerLevel world) {
        long tick = world.getServer().getTickCount();
        if (world != cachedWorld || tick != cachedTick) {
            List<LivingEntity> found = new ArrayList<>();

            for (Entity entity : world.getAllEntities()) {
                if (isActor(entity) && entity.isAlive()) {
                    found.add((LivingEntity)entity);
                }
            }

            cachedWorld = world;
            cachedTick = tick;
            cached = found;
        }

        return cached;
    }

    public static List<LivingEntity> near(ServerLevel world, Vec3 center, double radius) {
        List<LivingEntity> all = all(world);
        if (radius <= 0.0) {
            return all;
        }

        List<LivingEntity> result = new ArrayList<>();
        double limit = radius * radius;

        for (LivingEntity actor : all) {
            if (actor.position().distanceToSqr(center) <= limit) {
                result.add(actor);
            }
        }

        return result;
    }
}
