package net.tunamods.customglint.module.loot;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.rule.GlintRules;

import java.util.Map;
import java.util.function.Supplier;

public class GlintLootModifier extends LootModifier {

    public static final Supplier<Codec<GlintLootModifier>> CODEC =
        Suppliers.memoize(() -> RecordCodecBuilder.create(inst ->
            codecStart(inst).apply(inst, GlintLootModifier::new)));

    protected GlintLootModifier(LootItemCondition[] conditions) {
        super(conditions);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> generatedLoot, LootContext context) {
        ResourceLocation tableId = context.getQueriedLootTableId();
        Map<Item, CustomGlint.Data> glints = CustomGlint.LOOT_GLINTS.get(tableId);
        if (glints != null) {
            for (ItemStack stack : generatedLoot) {
                CustomGlint.Data data = glints.get(stack.getItem());
                if (data != null)
                    CustomGlint.write(stack, data.layers());
            }
        }
        // Pack-author rules (/glint rule loot) run after the API registry and skip anything it already glinted.
        if (tableId != null) GlintRules.applyLoot(tableId, generatedLoot);
        return generatedLoot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
