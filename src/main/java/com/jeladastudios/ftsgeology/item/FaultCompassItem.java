package com.jeladastudios.ftsgeology.item;

import com.jeladastudios.ftsgeology.tectonics.FaultType;
import com.jeladastudios.ftsgeology.tectonics.PlateSample;
import com.jeladastudios.ftsgeology.tectonics.TectonicMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Locale;

/**
 * A field compass for structure: which way the nearest plate boundary runs, and what it is doing.
 *
 * <h2>What it is modelled on</h2>
 * A geologist's Brunton compass measures the <b>strike</b> of a plane - the compass bearing of the
 * horizontal line lying in it. Strike is the first number written in any field notebook, because
 * everything else about a fault is described relative to it: a rupture runs along the strike, a
 * rift opens across it, a mountain belt grows parallel to it. This instrument reports the strike of
 * the nearest boundary, plus what the two plates on either side of it are actually doing to each
 * other - which a Brunton cannot tell you and a GPS network can.
 *
 * <p>Unlike the seismograph, giving a direction here is not cheating. A seismograph genuinely
 * cannot know which way an earthquake was; a geologist standing on a fault scarp can see which way
 * it runs. The instrument reads the ground it is standing on, so deep inside a plate it honestly
 * reports that there is nothing to measure.</p>
 */
public class FaultCompassItem extends Item {

    public FaultCompassItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(level instanceof ServerLevel server)) {
            return InteractionResultHolder.success(stack);
        }

        int x = player.blockPosition().getX();
        int z = player.blockPosition().getZ();
        PlateSample s = TectonicMap.sample(server, x, z);

        player.sendSystemMessage(Component.translatable("message.fts_geology.compass.plate",
                TectonicMap.plateCode(s.plateId()),
                Component.translatable(kindKey(s.plateKind())),
                String.format(Locale.ROOT, "%.1f", s.plateSpeed()),
                bearing(s.plateBearing())).withStyle(ChatFormatting.GOLD));

        if (!s.onFault()) {
            // Plate interior. Saying "no fault found" would be wrong - there is one, it is just
            // further away than this instrument can resolve, which is exactly the situation most
            // of the world's surface is in.
            player.sendSystemMessage(Component.translatable("message.fts_geology.compass.interior")
                    .withStyle(ChatFormatting.GRAY));
            ping(server, player, 0.6f);
            return InteractionResultHolder.consume(stack);
        }

        // Strike: the bearing of the boundary LINE. The normal points across it at the neighbour,
        // so the strike is the normal turned ninety degrees - which is what faultStrike* returns.
        double strike = compass(s.faultStrikeX(), s.faultStrikeZ());
        // A line has two ends and no preferred one, so strike is conventionally quoted in the
        // half-circle 000-180. Reporting 250 degrees where a geologist would write 070 would be a
        // small lie about what the instrument does.
        double quoted = strike % 180.0;

        player.sendSystemMessage(Component.translatable("message.fts_geology.compass.fault",
                Component.translatable(typeKey(s.faultType())),
                String.format(Locale.ROOT, "%03.0f", quoted),
                bearing(strike),
                String.valueOf(Math.round(s.faultDistance())),
                bearing(compass(s.faultNormalX(), s.faultNormalZ()))).withStyle(ChatFormatting.YELLOW));

        player.sendSystemMessage(Component.translatable("message.fts_geology.compass.motion",
                String.format(Locale.ROOT, "%.2f", Math.abs(s.convergence())),
                Component.translatable(s.convergence() >= 0
                        ? "message.fts_geology.compass.closing"
                        : "message.fts_geology.compass.opening"),
                String.format(Locale.ROOT, "%.2f", s.shear())).withStyle(ChatFormatting.WHITE));

        player.sendSystemMessage(Component.translatable("message.fts_geology.compass.stress",
                String.valueOf(Math.round(s.stress() * 100)),
                Component.translatable(stressKey(s.stress()))).withStyle(ChatFormatting.WHITE));

        ping(server, player, 1.0f + (float) s.stress());
        return InteractionResultHolder.consume(stack);
    }

    /** A needle settling. Pitch rises with how live the ground is, so the tool has a feel to it. */
    private static void ping(ServerLevel level, Player player, float pitch) {
        level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME,
                SoundSource.PLAYERS, 0.7f, Math.min(2.0f, pitch));
    }

    /** Compass bearing of a vector, degrees clockwise from north. Minecraft's north is -Z. */
    private static double compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return deg < 0 ? deg + 360.0 : deg;
    }

    /** The sixteen-point rose, as a translatable component so it survives into other languages. */
    private static Component bearing(double degrees) {
        String[] points = {"n", "nne", "ne", "ene", "e", "ese", "se", "sse",
                           "s", "ssw", "sw", "wsw", "w", "wnw", "nw", "nnw"};
        int i = (int) Math.floor(((degrees % 360.0) + 360.0) % 360.0 / 22.5 + 0.5) % 16;
        return Component.translatable("direction.fts_geology." + points[i]);
    }

    private static String kindKey(com.jeladastudios.ftsgeology.tectonics.PlateKind kind) {
        return "plate.fts_geology." + kind.name().toLowerCase(Locale.ROOT);
    }

    private static String typeKey(FaultType type) {
        return "fault.fts_geology." + type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the boundary is loaded or quiet.
     *
     * <p>A locked fault is not one that is safe; it is one that is storing the strain instead of
     * releasing it, and that is precisely the dangerous state. Worth having the instrument say so
     * in words rather than only as a number.</p>
     */
    private static String stressKey(double stress) {
        if (stress >= 0.75) return "message.fts_geology.compass.locked";
        if (stress >= 0.4) return "message.fts_geology.compass.strained";
        if (stress >= 0.15) return "message.fts_geology.compass.creeping";
        return "message.fts_geology.compass.quiet";
    }
}
