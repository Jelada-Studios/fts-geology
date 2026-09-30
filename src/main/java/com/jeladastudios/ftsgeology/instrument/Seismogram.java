package com.jeladastudios.ftsgeology.instrument;

import com.jeladastudios.ftsgeology.tectonics.DepthScale;
import it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A station's long record: what a seismograph has drawn over the days, kept with it, and printed on paper as a book to
 * read -- the quakes day by day, the latest of them in full, and, where a volcano near it is restless, the watch kept
 * on it hour by hour: how far its ground has swelled and how thick its swarm of small quakes comes. An observatory in
 * the old sense: the warning is read off the record before the mountain gives it.
 */
public final class Seismogram {

    private Seismogram() {}

    /** Game days the day-by-day record keeps, the quakes kept in full, and the hours of a volcano's watch. */
    private static final int DAYS = 60, QUAKES = 64, HOURS = 72;
    private static final String BARS = new String(new int[]{0x2581, 0x2582, 0x2583, 0x2584, 0x2585, 0x2586, 0x2587, 0x2588}, 0, 8);

    /** A quake drawn: when, how big, how far (metres). */
    public record Quake(long at, double magnitude, double metres) {}

    /** A day's count: quakes and their largest, the volcanic swarm's tremors and their largest. */
    static final class Day {
        int quakes, tremors;
        double largest, largestTremor;
    }

    /** An hour's look at the nearest restless volcano: its summit's distance, the ground's swelling here, its unrest. */
    public record Watch(int hour, int metres, int swellCm, int unrest, int tremors) {}

    public static final class Record {
        final List<Quake> quakes = new ArrayList<>();
        final Int2ObjectAVLTreeMap<Day> days = new Int2ObjectAVLTreeMap<>();
        final List<Watch> watch = new ArrayList<>();
        long since = -1;
        int tremorsThisHour;

        private Day day(long at) {
            int d = (int) (at / 24000L);
            Day day = days.computeIfAbsent(d, k -> new Day());
            while (days.size() > DAYS) days.remove(days.firstIntKey());
            return day;
        }

        public void started(long now) {
            if (since < 0) since = now;
        }

        public void quake(long at, double magnitude, double metres) {
            quakes.add(0, new Quake(at, magnitude, metres));
            while (quakes.size() > QUAKES) quakes.remove(quakes.size() - 1);
            Day d = day(at);
            d.quakes++;
            d.largest = Math.max(d.largest, magnitude);
        }

        public void tremor(long at, double magnitude) {
            Day d = day(at);
            d.tremors++;
            d.largestTremor = Math.max(d.largestTremor, magnitude);
            tremorsThisHour++;
        }

        /** The hour's look at a restless volcano; none where no volcano near is restless. */
        public void watch(long now, int metres, int swellCm, double unrest) {
            watch.add(new Watch((int) (now / 1000L), metres, swellCm, (int) Math.round(unrest * 100), tremorsThisHour));
            while (watch.size() > HOURS) watch.remove(0);
            tremorsThisHour = 0;
        }

        public void hourQuiet() {
            tremorsThisHour = 0;
        }

        public void save(CompoundTag tag) {
            CompoundTag r = new CompoundTag();
            r.putLong("Since", since);
            long[] q = new long[quakes.size() * 3];
            for (int i = 0; i < quakes.size(); i++) {
                Quake k = quakes.get(i);
                q[i * 3] = k.at();
                q[i * 3 + 1] = Math.round(k.magnitude() * 100);
                q[i * 3 + 2] = Math.round(k.metres());
            }
            r.put("Quakes", new LongArrayTag(q));
            ListTag ds = new ListTag();
            for (var e : days.int2ObjectEntrySet()) {
                Day d = e.getValue();
                ds.add(new IntArrayTag(new int[]{e.getIntKey(), d.quakes, (int) Math.round(d.largest * 100), d.tremors,
                        (int) Math.round(d.largestTremor * 100)}));
            }
            r.put("Days", ds);
            ListTag ws = new ListTag();
            for (Watch w : watch) ws.add(new IntArrayTag(new int[]{w.hour(), w.metres(), w.swellCm(), w.unrest(), w.tremors()}));
            r.put("Watch", ws);
            r.putInt("TremorsThisHour", tremorsThisHour);
            tag.put("Record", r);
        }

        public void load(CompoundTag tag) {
            quakes.clear();
            days.clear();
            watch.clear();
            if (!tag.contains("Record")) return;
            CompoundTag r = tag.getCompound("Record");
            since = r.getLong("Since");
            long[] q = r.getLongArray("Quakes");
            for (int i = 0; i + 2 < q.length && quakes.size() < QUAKES; i += 3) quakes.add(new Quake(q[i], q[i + 1] / 100.0, q[i + 2]));
            for (Tag t : r.getList("Days", Tag.TAG_INT_ARRAY)) {
                int[] a = ((IntArrayTag) t).getAsIntArray();
                if (a.length < 5) continue;
                Day d = new Day();
                d.quakes = a[1];
                d.largest = a[2] / 100.0;
                d.tremors = a[3];
                d.largestTremor = a[4] / 100.0;
                days.put(a[0], d);
            }
            for (Tag t : r.getList("Watch", Tag.TAG_INT_ARRAY)) {
                int[] a = ((IntArrayTag) t).getAsIntArray();
                if (a.length >= 5) watch.add(new Watch(a[0], a[1], a[2], a[3], a[4]));
            }
            tremorsThisHour = r.getInt("TremorsThisHour");
        }
    }

    // === The printout ============================================================

    /** The record printed: a written book of the station's paper. */
    public static ItemStack print(Record r, BlockPos pos, long now) {
        List<Component> pages = pages(r, pos, now);
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = book.getOrCreateTag();
        tag.putString("title", "Seismogram " + pos.getX() + " " + pos.getZ());
        tag.putString("author", "Seismograph");
        tag.putBoolean("resolved", true);
        ListTag list = new ListTag();
        for (Component p : pages) list.add(StringTag.valueOf(Component.Serializer.toJson(p)));
        tag.put("pages", list);
        return book;
    }

    private static MutableComponent line(String key, Object... args) {
        return Component.translatable("book.fts_geology.seismogram." + key, args);
    }

    private static MutableComponent page(List<Component> lines) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append("\n");
            out.append(lines.get(i));
        }
        return out;
    }

    static List<Component> pages(Record r, BlockPos pos, long now) {
        List<Component> pages = new ArrayList<>();
        int today = (int) (now / 24000L);
        int from = r.since < 0 ? today : (int) (r.since / 24000L);
        int total = 0, swarmDays = 0;
        double largest = 0;
        double largestAt = 0;
        for (var e : r.days.int2ObjectEntrySet()) {
            total += e.getValue().quakes;
            if (e.getValue().tremors > 0) swarmDays++;
        }
        for (Quake q : r.quakes) {
            if (q.magnitude() > largest) {
                largest = q.magnitude();
                largestAt = q.metres();
            }
        }
        List<Component> first = new ArrayList<>();
        first.add(line("title").withStyle(ChatFormatting.BOLD));
        first.add(line("station", pos.getX(), pos.getZ()));
        first.add(Component.empty());
        first.add(line("span", today - from + 1));
        first.add(line("total", total));
        if (largest > 0) {
            first.add(line("largest", String.format(Locale.ROOT, "%.1f", largest), DepthScale.format(largestAt)));
        }
        if (swarmDays > 0) first.add(line("swarm_days", swarmDays).withStyle(ChatFormatting.DARK_RED));
        pages.add(page(first));

        // The last two weeks, a bar a day: how many quakes, and the largest.
        List<Component> daily = new ArrayList<>();
        daily.add(line("daily").withStyle(ChatFormatting.BOLD));
        StringBuilder bars = new StringBuilder();
        int most = 1;
        for (int d = today - 13; d <= today; d++) {
            Day day = r.days.get(d);
            if (day != null) most = Math.max(most, day.quakes + day.tremors);
        }
        for (int d = today - 13; d <= today; d++) {
            Day day = r.days.get(d);
            int n = day == null ? 0 : day.quakes + day.tremors;
            bars.append(n == 0 ? String.valueOf((char) 0xB7) : String.valueOf(BARS.charAt(Math.min(7, (int) Math.round(7.0 * n / most)))));
        }
        daily.add(Component.literal(bars.toString()).withStyle(ChatFormatting.DARK_BLUE));
        for (int d = today; d >= today - 6; d--) {
            Day day = r.days.get(d);
            if (day == null) continue;
            daily.add(day.tremors > 0
                    ? line("day_swarm", d, day.quakes, String.format(Locale.ROOT, "%.1f", day.largest), day.tremors)
                    : line("day", d, day.quakes, String.format(Locale.ROOT, "%.1f", day.largest)));
        }
        pages.add(page(daily));

        // The latest quakes in full, ten to a page.
        for (int i = 0; i < Math.min(20, r.quakes.size()); i += 10) {
            List<Component> list = new ArrayList<>();
            list.add(line("latest").withStyle(ChatFormatting.BOLD));
            for (int j = i; j < Math.min(i + 10, r.quakes.size()); j++) {
                Quake q = r.quakes.get(j);
                list.add(line("quake", String.format(Locale.ROOT, "%.1f", q.magnitude()), DepthScale.format(q.metres()),
                        ago(now - q.at())));
            }
            pages.add(page(list));
        }

        // A volcano's watch: the ground's swelling and the swarm, hour by hour.
        if (!r.watch.isEmpty()) {
            Watch last = r.watch.get(r.watch.size() - 1);
            List<Component> v = new ArrayList<>();
            v.add(line("volcano").withStyle(ChatFormatting.BOLD));
            v.add(line("volcano_at", DepthScale.format(last.metres())));
            StringBuilder swell = new StringBuilder();
            int top = 1;
            for (Watch w : r.watch) top = Math.max(top, w.swellCm());
            int shown = Math.min(18, r.watch.size());
            for (int i = r.watch.size() - shown; i < r.watch.size(); i++) {
                swell.append(BARS.charAt(Math.min(7, (int) Math.round(7.0 * r.watch.get(i).swellCm() / top))));
            }
            v.add(line("swell", last.swellCm()));
            v.add(Component.literal(swell.toString()).withStyle(ChatFormatting.DARK_RED));
            v.add(line("tremors", last.tremors()));
            int before = r.watch.size() >= 6 ? r.watch.get(r.watch.size() - 6).swellCm() : r.watch.get(0).swellCm();
            boolean rising = last.swellCm() > before + 2 || last.tremors() >= 6;
            boolean falling = last.swellCm() + 2 < before;
            v.add(line(rising ? "trend_rising" : falling ? "trend_falling" : "trend_steady")
                    .withStyle(rising ? ChatFormatting.RED : ChatFormatting.DARK_GREEN));
            if (rising && last.swellCm() >= 20) v.add(line("warning").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
            pages.add(page(v));
        }
        return pages;
    }

    private static String ago(long ticks) {
        long s = Math.max(0, ticks) / 20L;
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h " + ((s % 3600) / 60) + "m";
        return (s / 86400) + "d";
    }
}
