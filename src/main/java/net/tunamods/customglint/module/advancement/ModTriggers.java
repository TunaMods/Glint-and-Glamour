package net.tunamods.customglint.module.advancement;

import net.minecraft.advancements.triggers.CriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

import static net.tunamods.customglint.CustomGlintMod.MOD_ID;

/** Full-jar (module) advancement criterion triggers. Standalone-only, like the rest of {@code module/}. */
public final class ModTriggers {
    private ModTriggers() {}

    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, MOD_ID);

    public static final Supplier<EightColorTrimTrigger> EIGHT_COLOR_TRIM =
            TRIGGERS.register("eight_color_trim", EightColorTrimTrigger::new);

    public static final Supplier<LayeredTrimTrigger> LAYERED_TRIM =
            TRIGGERS.register("layered_trim", LayeredTrimTrigger::new);

    public static final Supplier<EightLayerTrimTrigger> EIGHT_LAYER_TRIM =
            TRIGGERS.register("eight_layer_trim", EightLayerTrimTrigger::new);

    public static final Supplier<EightByEightTrimTrigger> EIGHT_BY_EIGHT_TRIM =
            TRIGGERS.register("eight_by_eight_trim", EightByEightTrimTrigger::new);

    public static final Supplier<DesignsCollectedTrigger> DESIGNS_COLLECTED =
            TRIGGERS.register("designs_collected", DesignsCollectedTrigger::new);

    public static void register(IEventBus modEventBus) {
        TRIGGERS.register(modEventBus);
    }
}
