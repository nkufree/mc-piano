package io.github.alab.mcpiano;

import com.mojang.math.Transformation;
import io.github.alab.mcpiano.mixin.BlockDisplayAccessor;
import io.github.alab.mcpiano.mixin.DisplayAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Builds the static Neon Piano Stage; animation remains client-only. */
public final class PianoBuilder {
    private static final int STAGE_PADDING = 4;
    private static final int BACKBOARD_TOTAL_WIDTH = PianoLayout.width() + 4;
    private static final int BACKBOARD_TOTAL_HEIGHT = Math.round(BACKBOARD_TOTAL_WIDTH * 9.0f / 16.0f);
    private static final int BACKBOARD_MIN_Y = -1;
    /** Top local Y of a 16:9 backboard; kept server-safe on purpose. */
    private static final int BACKBOARD_MAX_Y = BACKBOARD_MIN_Y + BACKBOARD_TOTAL_HEIGHT - 1;
    private static final String STATIC_BLACK_KEY_TAG = "mcpiano_static_black_key";
    private static final String STATIC_WHITE_TRIM_TAG = "mcpiano_static_white_trim";

    private PianoBuilder() { }

    public static void build(ServerLevel world, BlockPos origin) {
        buildStage(world, origin);
        buildBackboard(world, origin);
        buildPedalHousing(world, origin);
        clearLegacyBlackKeys(world, origin);
        clearStaticBlackKeyDisplays(world, origin);

        for (PianoLayout.Key key : PianoLayout.keys()) {
            if (key.black()) {
                buildBlackKey(world, origin, key);
                continue;
            }
            int depth = key.black() ? PianoLayout.BLACK_KEY_DEPTH : PianoLayout.WHITE_KEY_DEPTH;
            int startZ = key.black() ? 2 : 0;
            int y = origin.getY() + (key.black() ? 1 : 0);
            for (int dx = 0; dx < key.width(); dx++) {
                for (int dz = 0; dz < depth; dz++) {
                    world.setBlock(new BlockPos(origin.getX() + (int) key.x() + dx, y, origin.getZ() + startZ + dz),
                            (key.black() ? Blocks.CONCRETE.black() : Blocks.CONCRETE.white()).defaultBlockState(), 3);
                }
            }
            buildWhiteKeyTrim(world, origin, key);
        }

        // Low, dark casing around the keys.
        for (int x = -1; x <= PianoLayout.width(); x++) {
            world.setBlock(new BlockPos(origin.getX() + x, origin.getY() - 1, origin.getZ()), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
            world.setBlock(new BlockPos(origin.getX() + x, origin.getY() - 1,
                    origin.getZ() + PianoLayout.WHITE_KEY_DEPTH), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
        }
        for (int z = 0; z <= PianoLayout.WHITE_KEY_DEPTH; z++) {
            world.setBlock(new BlockPos(origin.getX() - 1, origin.getY() - 1, origin.getZ() + z), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
            world.setBlock(new BlockPos(origin.getX() + PianoLayout.width(), origin.getY() - 1, origin.getZ() + z), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), 3);
        }
    }

    private static void clearLegacyBlackKeys(ServerLevel world, BlockPos origin) {
        for (int x = 0; x < PianoLayout.width(); x++) {
            for (int z = 2; z < 2 + PianoLayout.BLACK_KEY_DEPTH; z++) {
                world.setBlock(new BlockPos(origin.getX() + x, origin.getY() + 1, origin.getZ() + z),
                        Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static void clearStaticBlackKeyDisplays(ServerLevel world, BlockPos origin) {
        AABB bounds = new AABB(origin.getX() - 1, origin.getY(), origin.getZ() - 1,
                origin.getX() + PianoLayout.width() + 1, origin.getY() + 3,
                origin.getZ() + PianoLayout.WHITE_KEY_DEPTH + 1);
        world.getEntities((net.minecraft.world.entity.Entity) null, bounds,
                        entity -> entity.entityTags().contains(STATIC_BLACK_KEY_TAG)
                                || entity.entityTags().contains(STATIC_WHITE_TRIM_TAG))
                .forEach(entity -> entity.discard());
    }

    private static void buildBlackKey(ServerLevel world, BlockPos origin, PianoLayout.Key key) {
        addStaticDisplay(world, Blocks.CONCRETE.black().defaultBlockState(),
                origin.getX() + key.x(), origin.getY() + 1, origin.getZ() + 2,
                key.width(), 1.0, PianoLayout.BLACK_KEY_DEPTH, STATIC_BLACK_KEY_TAG);
        // Only the two true black/white contact faces receive a seam.  Do not
        // extend this trim forward across an entire white-key column.
        double seam = 0.035;
        BlockState edge = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        addStaticDisplay(world, edge, origin.getX() + key.x() - seam / 2.0,
                origin.getY() + 1, origin.getZ() + 2, seam, 1.0,
                PianoLayout.BLACK_KEY_DEPTH, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, edge, origin.getX() + key.x() + key.width() - seam / 2.0,
                origin.getY() + 1, origin.getZ() + 2, seam, 1.0,
                PianoLayout.BLACK_KEY_DEPTH, STATIC_WHITE_TRIM_TAG);
    }

    private static void buildWhiteKeyTrim(ServerLevel world, BlockPos origin, PianoLayout.Key key) {
        double x = origin.getX() + key.x();
        double topY = origin.getY() + 1.002;
        double bottomY = origin.getY() + 0.002;
        double z = origin.getZ();
        double line = 0.055;
        double depth = PianoLayout.WHITE_KEY_DEPTH;
        BlockState black = Blocks.CONCRETE.black().defaultBlockState();
        // Top and bottom four edges.
        addStaticDisplay(world, black, x, topY, z, line, 0.035, depth, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x + key.width() - line, topY, z, line, 0.035, depth, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, topY, z, key.width(), 0.035, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, topY, z + depth - line, key.width(), 0.035, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, bottomY, z, line, 0.035, depth, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x + key.width() - line, bottomY, z, line, 0.035, depth, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, bottomY, z, key.width(), 0.035, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, bottomY, z + depth - line, key.width(), 0.035, line, STATIC_WHITE_TRIM_TAG);
        // Four vertical corner edges complete the white key's twelve-edge outline.
        addStaticDisplay(world, black, x, bottomY, z, line, 1.0, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x + key.width() - line, bottomY, z, line, 1.0, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x, bottomY, z + depth - line, line, 1.0, line, STATIC_WHITE_TRIM_TAG);
        addStaticDisplay(world, black, x + key.width() - line, bottomY, z + depth - line,
                line, 1.0, line, STATIC_WHITE_TRIM_TAG);

    }

    private static void addStaticDisplay(ServerLevel world, BlockState state, double x, double y, double z,
                                         double width, double height, double depth, String tag) {
        Display.BlockDisplay display = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, world);
        ((BlockDisplayAccessor) display).mcpiano$setBlockState(state);
        ((DisplayAccessor) display).mcpiano$setTransformation(new Transformation(
                new Vector3f(), new Quaternionf(),
                new Vector3f((float) width, (float) height, (float) depth), new Quaternionf()));
        display.setPos(x, y, z);
        display.addTag(tag);
        world.addFreshEntity(display);
    }

    private static void buildStage(ServerLevel world, BlockPos origin) {
        int minX = Math.min(-STAGE_PADDING, PianoLayout.PEDAL_HOUSING_START_X - 2);
        int maxX = PianoLayout.width() + 10;
        int minZ = -STAGE_PADDING;
        int maxZ = PianoLayout.WHITE_KEY_DEPTH + 2;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                world.setBlock(new BlockPos(origin.getX() + x, origin.getY() - 2, origin.getZ() + z),
                        (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES).defaultBlockState(), 3);
                if (edge) {
                    world.setBlock(new BlockPos(origin.getX() + x, origin.getY() - 1, origin.getZ() + z),
                            Blocks.SMOOTH_QUARTZ.defaultBlockState(), 3);
                }
            }
        }
    }

    private static void buildBackboard(ServerLevel world, BlockPos origin) {
        int minX = -2;
        int maxX = PianoLayout.width() + 1;
        int boardZ = PianoLayout.WHITE_KEY_DEPTH + 1;
        for (int x = minX; x <= maxX; x++) {
        for (int y = BACKBOARD_MIN_Y; y <= BACKBOARD_MAX_Y; y++) {
                boolean edge = x == minX || x == maxX || y == BACKBOARD_MIN_Y || y == BACKBOARD_MAX_Y;
                world.setBlock(new BlockPos(origin.getX() + x, origin.getY() + y, origin.getZ() + boardZ),
                        (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.CONCRETE.black()).defaultBlockState(), 3);
            }
        }
    }

    private static void buildPedalHousing(ServerLevel world, BlockPos origin) {
        int startX = PianoLayout.PEDAL_HOUSING_START_X;
        for (int x = startX; x <= startX + 6; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == startX || x == startX + 6 || z == 0 || z == 4;
                world.setBlock(new BlockPos(origin.getX() + x, origin.getY() - 1, origin.getZ() + z),
                        (edge ? Blocks.POLISHED_DEEPSLATE : Blocks.DEEPSLATE_TILES).defaultBlockState(), 3);
            }
        }
        // Three visible slots reserve Soft / Sostenuto / Sustain positions; the rightmost dynamic pedal is active.
        for (int slot = 0; slot < 3; slot++) {
            world.setBlock(new BlockPos(origin.getX() + startX + 1 + slot * 2, origin.getY(), origin.getZ() + 1),
                    Blocks.IRON_BLOCK.defaultBlockState(), 3);
        }
    }
}
