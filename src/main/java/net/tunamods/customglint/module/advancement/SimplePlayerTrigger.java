package net.tunamods.customglint.module.advancement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.advancements.triggers.SimpleCriterionTrigger;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Base for the "player finished a trim shaped like X" criteria. The trim shape can't be expressed by a
 * data-only item predicate, so the trim-producing paths (Glint Table print, color/layer-adding crafts) test
 * the shape in Java and call {@link #trigger(ServerPlayer)} on the matching criterion. The instance carries
 * only the optional player predicate. Each subclass is registered under its own id in {@code ModTriggers}, so
 * they fire independently despite sharing this instance type.
 */
public abstract class SimplePlayerTrigger extends SimpleCriterionTrigger<SimplePlayerTrigger.TriggerInstance> {

    public record TriggerInstance(Optional<Holder<LootItemCondition>> player) implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<TriggerInstance> CODEC = RecordCodecBuilder.create(i -> i.group(
                LootItemCondition.CODEC.optionalFieldOf("player").forGetter(TriggerInstance::player)
        ).apply(i, TriggerInstance::new));
    }

    @Override
    public Codec<TriggerInstance> codec() {
        return TriggerInstance.CODEC;
    }

    public void trigger(ServerPlayer player) {
        this.trigger(player, instance -> true);
    }
}
