package com.jeladastudios.ftsgeology.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A fumarole chimney: a stack of mineral the escaping steam has built for itself.
 *
 * <h2>Three parts, narrowing upward</h2>
 * A vent used to be a single cube sitting flush in the ground, which reads as a block somebody put
 * there rather than as something the ground grew. A real fumarole builds a small tower out of what
 * its own steam deposits, widest where it meets the ground and tapering as it rises, with the pale
 * crust heaviest around the lip where the mineral finally comes out of the vapour.
 *
 * <p>So the block has a {@code part}: a full cube at the base, a narrower neck, and a narrower cap
 * with the opening in its top face. World generation stacks two or three of them.</p>
 *
 * <h2>The smoke is client-side on purpose</h2>
 * {@link #animateTick} runs on the client only, from the ordinary random block tick, and costs the
 * server nothing whatsoever. A block entity would have been the obvious reach and the wrong one:
 * a hotspot dome carries a lot of these, and paying a server tick each - plus NBT for every one of
 * them - to produce a wisp of steam would be a real cost for a purely visual effect.
 */
public class SteamVentBlock extends Block {

    public enum Part implements StringRepresentable {
        BASE("base"), NECK("neck"), CAP("cap");

        private final String name;

        Part(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);

    /** Matches the three models: a full cube, then 12 wide, then 8. */
    private static final VoxelShape BASE_SHAPE = Block.box(0, 0, 0, 16, 16, 16);
    private static final VoxelShape NECK_SHAPE = Block.box(2, 0, 2, 14, 16, 14);
    private static final VoxelShape CAP_SHAPE = Block.box(4, 0, 4, 12, 16, 12);

    public SteamVentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, Part.BASE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return switch (state.getValue(PART)) {
            case NECK -> NECK_SHAPE;
            case CAP -> CAP_SHAPE;
            default -> BASE_SHAPE;
        };
    }

    /**
     * A thread of steam off the top of the chimney.
     *
     * <p>Only from the cap, and only sometimes: a fumarole breathes rather than smokes, and a
     * constant plume from every vent in a field would read as a machine. The particles rise slowly
     * and drift, so a line of chimneys along a fissure shows up from a distance as a few wisps
     * catching the light rather than a wall of fog.</p>
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (state.getValue(PART) != Part.CAP) return;
        if (random.nextInt(3) != 0) return;

        double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 0.3;
        double y = pos.getY() + 1.0;
        double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 0.3;
        level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z,
                0.0, 0.02 + random.nextDouble() * 0.02, 0.0);

        // The occasional warmer puff, so it reads as hot rather than as a smoking chimney.
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x, y, z, 0.0, 0.03, 0.0);
        }
    }
}
