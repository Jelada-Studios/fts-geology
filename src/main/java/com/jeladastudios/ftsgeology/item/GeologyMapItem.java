package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.advancement.GeologyTrigger;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.worldgen.TerrainProbe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A blank geological map. Opened, it becomes a map of the ground round where it was opened that fills in as its
 * holder walks, the way a field geologist's does: each patch coloured for the rock under its soil -- volcanic,
 * plutonic, metamorphic, sedimentary, loose sediment -- and the plate boundaries drawn across it in the colour of what
 * they are doing. A vanilla map underneath, locked so the game never paints its own terrain over it, and so it hangs
 * in a frame and copies at a cartography table like any other.
 */
@Mod.EventBusSubscriber(modid = GeysersMod.MODID)
public class GeologyMapItem extends Item {

    /** The mark a survey map carries. */
    public static final String TAG = "fts_geology_survey";
    /** Four blocks to a pixel: a map covers 512 blocks a side, enough to see a fault run across it. */
    private static final byte SCALE = 2;
    /** How far round its holder a map fills in, in blocks, and how many pixels at a time. */
    private static final int REACH = 96, PER_UPDATE = 96;
    /** How far apart along a boundary its motion arrows are drawn, in pixels. */
    private static final int ARROW_EVERY = 22;

    public GeologyMapItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack blank = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(blank);
        ItemStack map = MapItem.create(level, player.getBlockX(), player.getBlockZ(), SCALE, true, false);
        MapItem.lockMap(level, map);
        if (level instanceof ServerLevel server) mark(server, map);
        map.getOrCreateTag().putBoolean(TAG, true);
        map.setHoverName(Component.translatable("item.fts_geology.geology_map.filled").withStyle(style -> style.withItalic(false)));
        if (player instanceof ServerPlayer sp) GeologyTrigger.award(sp, "map_survey");
        level.playSound(null, player.blockPosition(), SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.0f);
        if (!player.getAbilities().instabuild) blank.shrink(1);
        if (blank.isEmpty()) return InteractionResultHolder.consume(map);
        if (!player.getInventory().add(map)) player.drop(map, false);
        return InteractionResultHolder.consume(blank);
    }

    @Override
    public void appendHoverText(ItemStack stack, @javax.annotation.Nullable Level level, List<Component> tooltip,
                                net.minecraft.world.item.TooltipFlag flag) {
        tooltip.add(Component.translatable("item.fts_geology.geology_map.tip").withStyle(ChatFormatting.GRAY));
    }

    // === Filling in ==========================================================

    /** Pixel offsets round the holder, nearest first, so a map fills outward from where its holder stands. */
    private static final List<int[]> RING = new ArrayList<>();
    static {
        int r = REACH / (1 << SCALE) + 1;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz <= r * r) RING.add(new int[]{dx, dz});
            }
        }
        RING.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (player.tickCount % 10 != 0) return;
        for (ItemStack stack : new ItemStack[]{player.getMainHandItem(), player.getOffhandItem()}) {
            if (stack.is(Items.FILLED_MAP) && stack.getTag() != null && stack.getTag().getBoolean(TAG)) {
                survey(player.serverLevel(), player, stack);
            }
        }
    }

    /**
     * The large volcanoes and the named mountains on the map's ground, marked: a red marker for a volcano, a target
     * for a mountain. Marked when the map is made, as an explorer map marks its structure.
     */
    private static void mark(ServerLevel level, ItemStack map) {
        MapItemSavedData data = MapItem.getSavedData(map, level);
        if (data == null) return;
        int half = 64 << data.scale;
        int x0 = data.centerX - half, z0 = data.centerZ - half, x1 = data.centerX + half, z1 = data.centerZ + half;
        int n = 0;
        for (var site : com.jeladastudios.ftsgeology.volcano.VolcanoField.sitesInBox(level, x0, z0, x1, z1)) {
            if (!site.chosen() || n++ >= 6) continue;
            MapItemSavedData.addTargetDecoration(map, new BlockPos(site.x(), 0, site.z()), "volcano" + n,
                    net.minecraft.world.level.saveddata.maps.MapDecoration.Type.RED_MARKER);
        }
        if (com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)
                && com.jeladastudios.ftsgeology.worldgen.terrain.DemLibrary.landmarksReady()) {
            for (var s : com.jeladastudios.ftsgeology.worldgen.terrain.LandmarkSites.all(
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed(),
                    com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params())) {
                if (s.x() < x0 || s.x() > x1 || s.z() < z0 || s.z() > z1) continue;
                MapItemSavedData.addTargetDecoration(map, new BlockPos(s.x(), 0, s.z()), "summit" + s.which(),
                        net.minecraft.world.level.saveddata.maps.MapDecoration.Type.TARGET_POINT);
            }
        }
    }

    /** Paints the unpainted pixels nearest the holder, a few at a time, and the arrows of the boundaries it crosses. */
    private static void survey(ServerLevel level, ServerPlayer player, ItemStack stack) {
        MapItemSavedData data = MapItem.getSavedData(stack, level);
        if (data == null || !data.dimension.equals(level.dimension())) return;
        int scale = 1 << data.scale;
        int cx = (int) Math.floor((player.getX() - data.centerX) / scale) + 64;
        int cz = (int) Math.floor((player.getZ() - data.centerZ) / scale) + 64;
        int painted = 0;
        for (int[] o : RING) {
            int px = cx + o[0], pz = cz + o[1];
            if (px < 0 || pz < 0 || px >= 128 || pz >= 128) continue;
            if (data.colors[px + pz * 128] != 0) continue;
            int wx = data.centerX + (px - 64) * scale, wz = data.centerZ + (pz - 64) * scale;
            if (!level.hasChunkAt(new BlockPos(wx, level.getSeaLevel(), wz))) continue;
            PlateSample s = com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sampleCached(level, wx, wz);
            data.updateColor(px, pz, colour(level, wx, wz, scale, s, px, pz));
            if (onLine(s, scale) && Math.floorMod((int) Math.round(s.along() / scale), ARROW_EVERY) == 0) {
                arrows(data, px, pz, s);
            }
            if (++painted >= PER_UPDATE) break;
        }
    }

    /** Whether a point lies on the boundary line itself, drawn two pixels wide. */
    private static boolean onLine(PlateSample s, int scale) {
        return s.faultType() != FaultType.INTERIOR && s.faultDistance() <= scale;
    }

    /** The colour of the ground at one point: its boundary line, water, or the rock under the soil, hatched in a fault zone. */
    public static byte colour(ServerLevel level, int x, int z, int scale) {
        return colour(level, x, z, scale, com.jeladastudios.ftsgeology.tectonics.LandmarkFaults.sampleCached(level, x, z),
                x / scale, z / scale);
    }

    static byte colour(ServerLevel level, int x, int z, int scale, PlateSample s, int px, int pz) {
        if (onLine(s, scale)) {
            MapColor line = switch (s.faultType()) {
                case CONVERGENT_COLLISION -> MapColor.COLOR_ORANGE;
                case CONVERGENT_SUBDUCTION -> MapColor.FIRE;
                case DIVERGENT -> MapColor.COLOR_CYAN;
                default -> MapColor.COLOR_MAGENTA;
            };
            return line.getPackedId(MapColor.Brightness.HIGH);
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, top, z);
        if (!level.getFluidState(m).isEmpty()) return MapColor.WATER.getPackedId(MapColor.Brightness.NORMAL);
        // The fault zone is hatched: a darker diagonal every few pixels.
        boolean hatch = s.faultType() != FaultType.INTERIOR && Math.floorMod(px + pz, 5) == 0;
        MapColor.Brightness shade = hatch ? MapColor.Brightness.LOWEST : MapColor.Brightness.NORMAL;
        return rock(level, x, z, top).getPackedId(shade);
    }

    /**
     * What a geological map shows at a point: the rock under the soil. On the mod's own world types that comes from
     * the rock model the ground was built from -- a clean map of the rocks, not of stray blocks; loose alluvium on a
     * floodplain is shown as such. Elsewhere the first rock under the soil is read off the ground.
     */
    private static MapColor rock(ServerLevel level, int x, int z, int top) {
        var biome = level.getBiome(new BlockPos(x, top, z));
        if (biome.is(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,
                new net.minecraft.resources.ResourceLocation(GeysersMod.MODID, "alluvial_plain")))) return MapColor.SAND;
        if (com.jeladastudios.ftsgeology.worldgen.terrain.GeologyWorld.isOwn(level)) {
            long seed = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.seed();
            var params = com.jeladastudios.ftsgeology.worldgen.terrain.TerrainContext.params();
            var col = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.column(seed, params, x, z);
            var r = com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.rockAt(seed, col, x, top - 3, z, top);
            MapColor c = colourOf(r);
            if (c != null) return c;
            return switch (col.setting()) {
                case PLATFORM, FORELAND -> MapColor.COLOR_YELLOW;
                case FOLD_BELT -> MapColor.COLOR_PURPLE;
                case ARC, HOTSPOT -> MapColor.COLOR_RED;
                case PRISM -> MapColor.COLOR_BROWN;
                case RIFT -> MapColor.COLOR_ORANGE;
                case SHEAR_ZONE -> MapColor.COLOR_GRAY;
                case OCEAN_FLOOR -> MapColor.COLOR_BLUE;
            };
        }
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) g = top;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = g; y > g - 10 && y > level.getMinBuildHeight(); y--) {
            RockTypes.Rock r = RockTypes.classify(level.getBlockState(m.set(x, y, z)));
            if (r != RockTypes.Rock.SOIL && r != RockTypes.Rock.OTHER) return colourOf(r);
        }
        return MapColor.DIRT;
    }

    private static MapColor colourOf(com.jeladastudios.ftsgeology.worldgen.lithology.Lithology.Rock r) {
        return switch (r) {
            case SANDSTONE, RED_BEDS -> MapColor.COLOR_YELLOW;
            case SHALE, CHERT -> MapColor.COLOR_LIGHT_GRAY;
            case CALCITE, MARBLE -> MapColor.COLOR_LIGHT_BLUE;
            case GRANITE, DIORITE -> MapColor.COLOR_PINK;
            case ANDESITE, TUFF, RHYOLITE, BASALT, SMOOTH_BASALT, BLACKSTONE -> MapColor.COLOR_RED;
            case GABBRO, PERIDOTITE, SERPENTINITE -> MapColor.COLOR_GREEN;
            case GNEISS, SCHIST, SLATE, QUARTZITE -> MapColor.COLOR_PURPLE;
            case STONE, KEEP -> null;
        };
    }

    private static MapColor colourOf(RockTypes.Rock rock) {
        return switch (rock) {
            case VOLCANIC -> MapColor.COLOR_RED;
            case PLUTONIC -> MapColor.COLOR_PINK;
            case METAMORPHIC -> MapColor.COLOR_PURPLE;
            case SEDIMENTARY -> MapColor.COLOR_YELLOW;
            case BIOGENIC -> MapColor.COLOR_LIGHT_BLUE;
            case SEDIMENT -> MapColor.SAND;
            case ORE -> MapColor.GOLD;
            case SOIL -> MapColor.DIRT;
            case OTHER -> MapColor.STONE;
        };
    }

    /**
     * The boundary's motion at one point of its line, in arrows either side: toward the line where the plates
     * converge, away from it where they part, and along it, one each way, where they slide past.
     */
    private static void arrows(MapItemSavedData data, int px, int pz, PlateSample s) {
        double nx = s.faultNormalX(), nz = s.faultNormalZ();
        double len = Math.hypot(nx, nz);
        if (len < 1e-6) return;
        nx /= len;
        nz /= len;
        double sx = -nz, sz = nx;          // along the strike
        byte ink = MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.NORMAL);
        for (int side = -1; side <= 1; side += 2) {
            // The arrow's tail, a few pixels out on this side.
            double ox = px + nx * side * 6, oz = pz + nz * side * 6;
            double dx, dz;
            switch (s.faultType()) {
                case CONVERGENT_COLLISION, CONVERGENT_SUBDUCTION -> {
                    dx = -nx * side;
                    dz = -nz * side;
                }
                case DIVERGENT -> {
                    dx = nx * side;
                    dz = nz * side;
                    ox = px + nx * side * 2;
                    oz = pz + nz * side * 2;
                }
                default -> {
                    dx = sx * side;
                    dz = sz * side;
                    ox = px + nx * side * 3 - dx * 2;
                    oz = pz + nz * side * 3 - dz * 2;
                }
            }
            arrow(data, ox, oz, dx, dz, ink);
        }
    }

    /** A four-pixel arrow from (ox, oz) along (dx, dz), with a two-pixel head. */
    private static void arrow(MapItemSavedData data, double ox, double oz, double dx, double dz, byte ink) {
        for (int i = 0; i <= 3; i++) dot(data, ox + dx * i, oz + dz * i, ink);
        double tx = ox + dx * 3, tz = oz + dz * 3;
        double c = Math.cos(Math.toRadians(135)), s = Math.sin(Math.toRadians(135));
        for (int sign = -1; sign <= 1; sign += 2) {
            double bx = dx * c - dz * s * sign, bz = dx * s * sign + dz * c;
            dot(data, tx + bx, tz + bz, ink);
            dot(data, tx + bx * 2, tz + bz * 2, ink);
        }
    }

    private static void dot(MapItemSavedData data, double x, double z, byte ink) {
        int px = (int) Math.round(x), pz = (int) Math.round(z);
        if (px < 0 || pz < 0 || px >= 128 || pz >= 128) return;
        data.updateColor(px, pz, ink);
    }
}
