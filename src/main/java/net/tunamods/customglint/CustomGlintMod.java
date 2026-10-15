package net.tunamods.customglint;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.advancement.EightByEightTrimTrigger;
import net.tunamods.customglint.module.advancement.ModTriggers;
import net.tunamods.customglint.module.block.ModBlockEntities;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.client.GlintTableClientInit;
import net.tunamods.customglint.module.client.PhotonGlintNotice;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.compat.artifacts.ArtifactsCompat;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsCompat;
import net.tunamods.customglint.module.compat.firstperson.FirstPersonCompat;
import net.tunamods.customglint.module.compat.geckolib.GeckoLibArmorCompat;
import net.tunamods.customglint.module.compat.iceandfire.IceAndFireCompat;
import net.tunamods.customglint.module.compat.immersivearmors.ImmersiveArmorsCompat;
import net.tunamods.customglint.module.compat.mekanism.MekanismArmorCompat;
import net.tunamods.customglint.module.item.GlintBagItem;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModCreativeTabs;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.loot.ModLootModifiers;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.recipe.ModRecipes;
import net.tunamods.customglint.module.rule.GlintRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full standalone mod entry. Registry content (items, creative tab, recipes, loot modifiers, blocks, block
 * entities, menus) lives in the {@code Mod*} holder classes under the matching module packages; see
 * {@link ModItems}, {@link ModCreativeTabs}, {@link ModRecipes}, {@link ModLootModifiers}, etc. This class
 * only wires their {@code register(bus)} hooks and owns the data-pack design reload/sync and the glint rule
 * events (load on server start, sync on join, drop rules).
 */
@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModLootModifiers.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);

        // Client-only: bind the Glint Table menu to its screen. Class-loaded on the client only.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> GlintTableClientInit.register(modEventBus));

        // Client-only: warn Photon users that their pack is collapsing multi-layer glints to one. The nested
        // supplier is load-bearing. Its body is a second invokedynamic, so PhotonGlintNotice only links on the
        // client, however the name is spelled.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> PhotonGlintNotice.register());

        ModNetworking.register();
        IceAndFireCompat.register();
        FirstPersonCompat.register();
        EpicKnightsCompat.register();
        GeckoLibArmorCompat.register();
        ImmersiveArmorsCompat.register();
        MekanismArmorCompat.register();
        ArtifactsCompat.register();

        // Entity-glint sync (EntityGlintEvents, ApiNetworking, EntityGlintClientInit) is now
        // registered by CustomGlintApiMod: the api jar ships with the full jar via jarJar, so
        // those registrations always happen exactly once regardless of which jar a player has.

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.addListener(this::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        MinecraftForge.EVENT_BUS.addListener(this::onPlayerJoin);
        MinecraftForge.EVENT_BUS.addListener(this::onItemCrafted);
        MinecraftForge.EVENT_BUS.addListener(this::onItemPickup);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarting);
        MinecraftForge.EVENT_BUS.addListener(this::onLivingDrops);
    }

    /** Load the glint rules file ({@code /glint rule}) before anyone joins or any loot rolls. */
    private void onServerStarting(ServerStartingEvent event) {
        int n = GlintRules.load();
        if (n > 0) LOGGER.info("Loaded {} glint rule(s) from {}", n, GlintRules.file());
    }

    /** DROP rules ({@code /glint rule drop}): glint the matching items a mob type drops. Runs after the mob's
     *  loot table, so a LOOT rule on that table has already had its turn and is not overwritten. */
    private void onLivingDrops(LivingDropsEvent event) {
        GlintRules.applyDrops(event.getEntity().getType(), event.getDrops());
    }

    /** Route Glint loot items straight into a Glint Bag the player is carrying, so a looting run doesn't fill
     *  the main inventory. Anything the bags can't hold (full, or a bag is absent) falls through to vanilla
     *  pickup. */
    private void onItemPickup(EntityItemPickupEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) return;
        ItemEntity itemEntity = event.getItem();
        ItemStack picked = itemEntity.getItem();
        if (picked.isEmpty() || !GlintBagItem.canStore(picked)) return;

        int before = picked.getCount();
        ItemStack remaining = picked;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack bag = inv.getItem(i);
            if (!(bag.getItem() instanceof GlintBagItem)) continue;
            if (!GlintBagItem.isAutoCollect(bag)) continue;
            IItemHandler handler =
                    bag.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
            if (handler == null) continue;
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, false);
        }

        int inserted = before - remaining.getCount();
        if (inserted <= 0) return; // no bag, or no room: let vanilla handle it

        player.take(itemEntity, inserted); // pickup animation + sound for the portion the bag took
        if (remaining.isEmpty()) {
            itemEntity.discard();
            event.setCanceled(true);
        } else {
            itemEntity.setItem(remaining); // vanilla picks up whatever the bags couldn't hold
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        // Custom advancement triggers register straight into the vanilla registry (no DeferredRegister on
        // 1.20.1). enqueueWork keeps it on the main thread, off the parallel mod-loading threads.
        event.enqueueWork(() -> {
            ModTriggers.register();
            // Rename the pre-1.7.0 config/customglint/ folder to config/glint-and-glamour/ before anything
            // reads it (blueprint scans, gui.properties). No-op after the first migrated launch.
            ModConfigPaths.migrateLegacy();
        });
    }

    /** Award the color/layer trim advancements when a crafting-table recipe (dye / merge / layer) yields a
     *  qualifying Glint Trim. The Glint Table print fires the same triggers from {@code GlintTableMenu#print}. */
    private void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        // A crafted Glint Bag gets the same Golden glow trim its creative/JEI icon shows.
        if (event.getCrafting().getItem() instanceof GlintBagItem) {
            GlintBagItem.applyGoldenGlint(event.getCrafting());
            return;
        }
        if (!(event.getCrafting().getItem() instanceof GlintTrimItem)) return;
        if (GlintTrimItem.getColors(event.getCrafting()).length >= 8) {
            ModTriggers.EIGHT_COLOR_TRIM.trigger(sp);
        }
        CustomGlint.Data data = CustomGlint.read(event.getCrafting());
        int layers = data != null ? data.layers().length : 0;
        if (layers >= 2) ModTriggers.LAYERED_TRIM.trigger(sp);
        if (layers >= 8) ModTriggers.EIGHT_LAYER_TRIM.trigger(sp);
        if (EightByEightTrimTrigger.matches(data)) ModTriggers.EIGHT_BY_EIGHT_TRIM.trigger(sp);
    }

    private void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "customglint/designs") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> object, ResourceManager manager, ProfilerFiller profiler) {
                GlintTrimItem.PATTERNS.removeAll(dataPackDesigns);
                dataPackDesigns.clear();
                for (JsonElement file : object.values()) {
                    if (!file.isJsonArray()) continue;
                    for (JsonElement entry : file.getAsJsonArray()) {
                        if (!entry.isJsonPrimitive()) continue;
                        String name = entry.getAsString();
                        if (!GlintTrimItem.PATTERNS.contains(name)) {
                            GlintTrimItem.PATTERNS.add(name);
                            dataPackDesigns.add(name);
                        }
                    }
                }
                if (ServerLifecycleHooks.getCurrentServer() != null) {
                    GlintDesignSyncPacket packet = new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns));
                    ModNetworking.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
                }
            }
        });
    }

    private void registerCommands(RegisterCommandsEvent event) {
        GlintCommand.register(event.getDispatcher());
    }

    private void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!dataPackDesigns.isEmpty()) {
            ModNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns)));
        }
        GlintRules.syncTo(player); // item rules render client-side, so every client needs the current set
    }
}
