package net.tunamods.customglint.module.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Fires with how many BASE designs the player has collected in their Glint Table library. Each collection
 * advancement sets either a {@code count} threshold (collect 5 / 10 / 20 / 50) or {@code all} (collect every
 * base design, whose total is resolved server-side so it can't drift out of sync with a hardcoded number).
 */
public class DesignsCollectedTrigger extends SimpleCriterionTrigger<DesignsCollectedTrigger.TriggerInstance> {

    public record TriggerInstance(Optional<Holder<LootItemCondition>> player, Optional<Integer> count, boolean all)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
                LootItemCondition.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player),
                Codec.INT.optionalFieldOf("count").forGetter(TriggerInstance::count),
                Codec.BOOL.optionalFieldOf("all", false).forGetter(TriggerInstance::all)
        ).apply(i, TriggerInstance::new));

        public boolean matches(int collected, int total) {
            if (all) return collected >= total;
            return count.map(c -> collected >= c).orElse(true);
        }
    }

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player, int collected, int total) {
        this.trigger(player, inst -> inst.matches(collected, total));
    }
}
