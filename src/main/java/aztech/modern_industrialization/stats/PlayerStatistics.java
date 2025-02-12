/*
 * MIT License
 *
 * Copyright (c) 2020 Azercoco & Technici4n
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package aztech.modern_industrialization.stats;

import aztech.modern_industrialization.compat.ftbquests.FTBQuestsFacade;
import aztech.modern_industrialization.compat.ftbteams.FTBTeamsFacade;
import aztech.modern_industrialization.proxy.CommonProxy;
import com.google.common.primitives.Ints;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

public class PlayerStatistics {
    public static final PlayerStatistics DUMMY = new PlayerStatistics(null, null);

    private final PlayerStatisticsData data;
    @Nullable
    private final UUID uuid;
    private final Map<Item, StatisticValue> usedItems = new IdentityHashMap<>(), producedItems = new IdentityHashMap<>();
    private final Map<Fluid, StatisticValue> usedFluids = new IdentityHashMap<>(), producedFluids = new IdentityHashMap<>();

    // Items produced while the player was offline... this is used to award vanilla stats when the player comes back online.
    private final Reference2LongMap<Item> pendingCraftedStats = new Reference2LongOpenHashMap<>();

    PlayerStatistics(PlayerStatisticsData data, UUID uuid) {
        this.data = data;
        this.uuid = uuid;
    }

    PlayerStatistics(PlayerStatisticsData data, UUID uuid, CompoundTag nbt) {
        this(data, uuid);
        readNbt(Registry.ITEM, usedItems, nbt.getCompound("usedItems"));
        readNbt(Registry.ITEM, producedItems, nbt.getCompound("producedItems"));
        readNbt(Registry.FLUID, usedFluids, nbt.getCompound("usedFluids"));
        readNbt(Registry.FLUID, producedFluids, nbt.getCompound("producedFluids"));
        pendingReadNbt(pendingCraftedStats, nbt.getCompound("pendingCraftedStats"));
    }

    public CompoundTag toTag() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("usedItems", toNbt(Registry.ITEM, usedItems));
        nbt.put("producedItems", toNbt(Registry.ITEM, producedItems));
        nbt.put("usedFluids", toNbt(Registry.FLUID, usedFluids));
        nbt.put("producedFluids", toNbt(Registry.FLUID, producedFluids));
        nbt.put("pendingCraftedStats", pendingToNbt(pendingCraftedStats));
        return nbt;
    }

    public void addUsedItems(ItemLike what, long amount) {
        usedItems.computeIfAbsent(what.asItem(), i -> new StatisticValue()).add(amount);
    }

    public void addProducedItems(ItemLike what, long amount) {
        var item = what.asItem();
        producedItems.computeIfAbsent(item, i -> new StatisticValue()).add(amount);

        if (uuid != null) {
            FTBQuestsFacade.INSTANCE.addCompleted(uuid, item, amount);

            awardStat(what, amount);

            for (var otherTeamMember : FTBTeamsFacade.INSTANCE.getOtherPlayersInTeam(uuid)) {
                data.get(otherTeamMember).awardStat(what, amount);
            }
        }
    }

    public void addUsedFluids(Fluid what, long amount) {
        usedFluids.computeIfAbsent(what, i -> new StatisticValue()).add(amount);
    }

    public void addProducedFluids(Fluid what, long amount) {
        producedFluids.computeIfAbsent(what, i -> new StatisticValue()).add(amount);
    }

    private void awardStat(ItemLike what, long amount) {
        Objects.requireNonNull(uuid);

        var player = CommonProxy.getCurrentServer().getPlayerList().getPlayer(uuid);

        if (player != null) {
            player.awardStat(Stats.ITEM_CRAFTED.get(what.asItem()), Ints.saturatedCast(amount));
        } else {
            pendingCraftedStats.mergeLong(what.asItem(), amount, Long::sum);
        }
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (!pendingCraftedStats.isEmpty()) {
            for (var entry : pendingCraftedStats.reference2LongEntrySet()) {
                player.awardStat(Stats.ITEM_CRAFTED.get(entry.getKey()), Ints.saturatedCast(entry.getLongValue()));
            }

            pendingCraftedStats.clear();
        }
    }

    private static <T> void readNbt(Registry<T> registry, Map<T, StatisticValue> map, CompoundTag tag) {
        for (var key : tag.getAllKeys()) {
            try {
                var val = registry.get(new ResourceLocation(key));
                if (val != Items.AIR && val != Fluids.EMPTY) {
                    map.put(val, new StatisticValue(tag.getCompound(key)));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static <T> CompoundTag toNbt(Registry<T> registry, Map<T, StatisticValue> map) {
        CompoundTag tag = new CompoundTag();
        for (var entry : map.entrySet()) {
            tag.put(registry.getKey(entry.getKey()).toString(), entry.getValue().toNbt());
        }
        return tag;
    }

    private static void pendingReadNbt(Reference2LongMap<Item> map, CompoundTag tag) {
        for (var key : tag.getAllKeys()) {
            try {
                var val = Registry.ITEM.get(new ResourceLocation(key));
                if (val != Items.AIR) {
                    map.put(val, tag.getLong(key));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static CompoundTag pendingToNbt(Reference2LongMap<Item> map) {
        CompoundTag tag = new CompoundTag();
        for (var entry : map.reference2LongEntrySet()) {
            tag.putLong(Registry.ITEM.getKey(entry.getKey()).toString(), entry.getLongValue());
        }
        return tag;
    }
}
