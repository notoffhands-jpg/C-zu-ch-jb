package de.fiden.botsystem.bot;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class BlockLog {
    private final Deque<BlockLog.Placed> placed = new ArrayDeque<>();
    private final Deque<BlockLog.Broken> broken = new ArrayDeque<>();

    public int size() {
        return this.placed.size();
    }

    public Block block() {
        Identifier id = Identifier.tryParse(BotDefaults.get().buildBlock);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        return block != null && block != Blocks.AIR ? block : Blocks.COBBLESTONE;
    }

    public boolean place(ServerLevel world, BlockPos pos, BotPlayer builder, long tick) {
        if (builder != null) {
            builder.trailTainted = true;
        }

        BlockState current = world.getBlockState(pos);
        if (current.canBeReplaced() && (current.getFluidState().isEmpty() || current.isAir() || current.canBeReplaced())) {
            AABB box = new AABB(pos);
            if (builder.getBoundingBox().intersects(box)) {
                return false;
            }

            BlockState state = this.block().defaultBlockState();
            if (!world.setBlock(pos, state, 3)) {
                return false;
            }

            builder.swing(InteractionHand.MAIN_HAND, true);
            this.placed.addLast(new BlockLog.Placed(world, pos.immutable(), state.getBlock(), tick));
            int max = BotDefaults.get().buildMax;

            while (max > 0 && this.placed.size() > max) {
                this.remove(this.placed.pollFirst());
            }

            return true;
        } else {
            return false;
        }
    }

    public void tick(long tick) {
        if (tick % 20L == 0L) {
            this.restoreBroken(tick);
            int lifetime = BotDefaults.get().buildLifetime;
            if (lifetime > 0) {
                long limit = tick - lifetime * 20L;
                Iterator<BlockLog.Placed> it = this.placed.iterator();

                while (it.hasNext()) {
                    BlockLog.Placed entry = it.next();
                    if (entry.tick() > limit) {
                        break;
                    }

                    it.remove();
                    this.remove(entry);
                }
            }
        }
    }

    public void remember(ServerLevel world, BlockPos pos, long tick) {
        if (BotDefaults.get().digRestore > 0) {
            this.broken.addLast(new BlockLog.Broken(world, pos.immutable(), world.getBlockState(pos), tick));

            while (this.broken.size() > 20000) {
                this.broken.pollFirst();
            }
        }
    }

    private void restoreBroken(long tick) {
        int delay = BotDefaults.get().digRestore;
        if (delay <= 0) {
            this.broken.clear();
        } else {
            long limit = tick - delay * 20L;
            Iterator<BlockLog.Broken> it = this.broken.iterator();

            while (it.hasNext()) {
                BlockLog.Broken entry = it.next();
                if (entry.tick() > limit) {
                    break;
                }

                it.remove();
                if (entry.world().getBlockState(entry.pos()).isAir()) {
                    AABB box = new AABB(entry.pos());
                    if (entry.world().getEntitiesOfClass(LivingEntity.class, box, living -> true).isEmpty()) {
                        entry.world().setBlock(entry.pos(), entry.state(), 3);
                    }
                }
            }
        }
    }

    public int clear() {
        int count = this.placed.size();
        this.placed.forEach(this::remove);
        this.placed.clear();
        return count;
    }

    private boolean inUse(BlockLog.Placed entry) {
        AABB box = new AABB(
            entry.pos().getX() - 0.6,
            entry.pos().getY() + 0.1,
            entry.pos().getZ() - 0.6,
            entry.pos().getX() + 1.6,
            entry.pos().getY() + 3.2,
            entry.pos().getZ() + 1.6
        );
        return !entry.world().getEntitiesOfClass(BotPlayer.class, box, bot -> true).isEmpty();
    }

    private void remove(BlockLog.Placed entry) {
        if (entry != null && this.inUse(entry)) {
            this.placed.addLast(new BlockLog.Placed(entry.world(), entry.pos(), entry.block(), entry.tick() + 200L));
        } else {
            if (entry != null && entry.world().getBlockState(entry.pos()).is(entry.block())) {
                entry.world().setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private record Broken(ServerLevel world, BlockPos pos, BlockState state, long tick) {
    }

    private record Placed(ServerLevel world, BlockPos pos, Block block, long tick) {
    }
}
