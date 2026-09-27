package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.GeysersMod;
import com.jeladastudios.ftsgeology.advancement.GeologyTrigger;
import com.jeladastudios.ftsgeology.instrument.RockTypes;
import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
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
    /** Two blocks to a pixel: a map covers 256 blocks a side. */
    private static final byte SCALE = 1;
    /** How far round its holder a map fills in, in blocks, and how many pixels at a time. */
    private static final int REACH = 48, PER_UPDATE = 64;

    public GeologyMapItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack blank = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(blank);
        ItemStack map = MapItem.create(level, player.getBlockX(), player.getBlockZ(), SCALE, true, false);
        MapItem.lockMap(level, map);
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

    /** Paints the unpainted pixels nearest the holder, a few at a time. */
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
            data.updateColor(px, pz, colour(level, wx, wz, scale));
            if (++painted >= PER_UPDATE) break;
        }
    }

    /** The colour of the ground at one point: a boundary across it, water over it, or the rock under its soil. */
    public static byte colour(ServerLevel level, int x, int z, int scale) {
        PlateSample s = TectonicMap.sampleCached(level, x, z);
        if (s.faultType() != FaultType.INTERIOR && s.faultDistance() <= Math.max(1.5, scale * 0.75)) {
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
        int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z - scale) - 1;
        MapColor.Brightness shade = top > north + 1 ? MapColor.Brightness.HIGH
                : top < north - 1 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        int g = TerrainProbe.groundY(level, x, z);
        if (g == Integer.MIN_VALUE) g = top;
        RockTypes.Rock rock = RockTypes.Rock.SOIL;
        for (int y = g; y > g - 10 && y > level.getMinBuildHeight(); y--) {
            BlockState b = level.getBlockState(m.setY(y));
            RockTypes.Rock r = RockTypes.classify(b);
            if (r != RockTypes.Rock.SOIL && r != RockTypes.Rock.OTHER) {
                rock = r;
                break;
            }
        }
        return colourOf(rock).getPackedId(shade);
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
}
