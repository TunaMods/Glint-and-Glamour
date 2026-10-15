package net.tunamods.customglint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.module.ModConfigPaths;
import net.tunamods.customglint.module.item.GlintBagItem;
import net.tunamods.customglint.module.advancement.EightByEightTrimTrigger;
import net.tunamods.customglint.module.advancement.ModTriggers;
import net.tunamods.customglint.module.block.ModBlockEntities;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.module.client.GlintTableClientInit;
import net.tunamods.customglint.module.client.GlintTableModelClient;
import net.tunamods.customglint.module.command.GlintCommand;
import net.tunamods.customglint.module.compat.epicknights.EpicKnightsCompat;
import net.tunamods.customglint.module.compat.firstperson.FirstPersonCompat;
import net.tunamods.customglint.module.compat.iceandfire.IceAndFireCompat;
import net.tunamods.customglint.module.item.GlintTrimItem;
import net.tunamods.customglint.module.item.ModComponents;
import net.tunamods.customglint.module.item.ModCreativeTabs;
import net.tunamods.customglint.module.item.ModItems;
import net.tunamods.customglint.module.loot.ModLootModifiers;
import net.tunamods.customglint.module.menu.ModAttachments;
import net.tunamods.customglint.module.menu.ModMenuTypes;
import net.tunamods.customglint.module.network.GlintDesignSyncPacket;
import net.tunamods.customglint.module.network.ModNetworking;
import net.tunamods.customglint.module.recipe.ModRecipes;
import net.tunamods.customglint.module.rule.GlintRules;

/**
 * Full standalone mod entry. Registry content (items, creative tab, recipes, loot modifiers, blocks, block
 * entities, menus, attachments) lives in the {@code Mod*} holder classes under the matching module packages.
 * See {@link ModItems}, {@link ModCreativeTabs}, {@link ModRecipes}, {@link ModLootModifiers}, etc. This
 * class only wires their {@code register(bus)} hooks and owns the data-pack design reload/sync and the glint
 * rule events (load on server start, sync on join, drop rules).
 */
@Mod(CustomGlintMod.MOD_ID)
public class CustomGlintMod {
    public static final String MOD_ID = "customglint";

    private final List<String> dataPackDesigns = new ArrayList<>();

    public CustomGlintMod(IEventBus modEventBus) {
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(this::commonSetup);

        ModItems.register(modEventBus);
        ModComponents.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModLootModifiers.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModTriggers.register(modEventBus);

        ModNetworking.register(modEventBus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            GlintTableClientInit.register(modEventBus);
            GlintTableModelClient.register(modEventBus);
        }
        IceAndFireCompat.register();
        FirstPersonCompat.register();
        EpicKnightsCompat.register();

        // Entity glint is the synced ENTITY_GLINT attachment (registered by CustomGlintApiMod via
        // CustomGlintComponents); NeoForge auto-syncs it to trackers, so there is no sync packet to wire.

        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onPlayerJoin);
        NeoForge.EVENT_BUS.addListener(this::onItemCrafted);
        NeoForge.EVENT_BUS.addListener(this::onItemPickup);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onLivingDrops);
    }

    /** Load the glint rules file ({@code /glint rule}) before anyone joins or any loot rolls. */
    private void onServerStarting(ServerStartingEvent event) {
        GlintRules.load();
    }

    /** DROP rules ({@code /glint rule drop}): glint the matching items a mob type drops. Runs after the mob's
     *  loot table, so a LOOT rule on that table has already had its turn and is not overwritten. */
    private void onLivingDrops(LivingDropsEvent event) {
        GlintRules.applyDrops(event.getEntity().getType(), event.getDrops());
    }

    /** Expose the Glint Bag's contents as an item-handler capability, backed by the stack's
     *  {@code minecraft:container} component (see {@link GlintBagItem#createHandler}). */
    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.ItemHandler.ITEM,
                (stack, ctx) -> GlintBagItem.createHandler(stack), ModItems.GLINT_BAG.get());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Rename the pre-1.7.0 config/customglint/ folder to config/glint-and-glamour/ before anything
        // reads it (blueprint scans, gui.properties). No-op after the first migrated launch.
        event.enqueueWork(ModConfigPaths::migrateLegacy);
    }

    /** Route Glint loot items straight into a Glint Bag the player is carrying, so a looting run doesn't fill
     *  the main inventory. Anything the bags can't hold (full, or a bag is absent) falls through to vanilla
     *  pickup. */
    private void onItemPickup(ItemEntityPickupEvent.Pre event) {
        Player player = event.getPlayer();
        if (player.level().isClientSide) return;
        ItemEntity itemEntity = event.getItemEntity();
        ItemStack picked = itemEntity.getItem();
        if (picked.isEmpty() || !GlintBagItem.isAutoCollectable(picked)) return;

        int before = picked.getCount();
        ItemStack remaining = picked;
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack bag = inv.getItem(i);
            if (!(bag.getItem() instanceof GlintBagItem)) continue;
            if (!GlintBagItem.isAutoCollect(bag)) continue;
            IItemHandler handler = bag.getCapability(Capabilities.ItemHandler.ITEM);
            if (handler == null) continue;
            remaining = ItemHandlerHelper.insertItemStacked(handler, remaining, false);
        }

        int inserted = before - remaining.getCount();
        if (inserted <= 0) return; // no bag / no room, let vanilla handle it

        // Play the pickup pop + fly-to-player animation for the portion the bag absorbed; the vanilla pickup
        // path is denied below, so it wouldn't do it for us.
        player.take(itemEntity, inserted);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2f,
                ((player.getRandom().nextFloat() - player.getRandom().nextFloat()) * 0.7f + 1.0f) * 2.0f);
        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining); // vanilla picks up whatever the bags couldn't hold, next tick
        }
        event.setCanPickup(TriState.FALSE); // we've handled this touch, don't also add to the main inventory
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
        if (GlintTrimItem.getColors(event.getCrafting()).length >= CustomGlint.MAX_COLORS_PER_LAYER) {
            ModTriggers.EIGHT_COLOR_TRIM.get().trigger(sp);
        }
        CustomGlint.Data data = CustomGlint.read(event.getCrafting());
        int layers = data != null ? data.layers().length : 0;
        if (layers >= 2) ModTriggers.LAYERED_TRIM.get().trigger(sp);
        // 8 layers is the layer-tear cap, so this fires on a maxed-out stack (see GlintLayerTearRecipe).
        if (layers >= 8) ModTriggers.EIGHT_LAYER_TRIM.get().trigger(sp);
        if (EightByEightTrimTrigger.matches(data)) ModTriggers.EIGHT_BY_EIGHT_TRIM.get().trigger(sp);
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
                    PacketDistributor.sendToAllPlayers(packet);
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
            PacketDistributor.sendToPlayer(player, new GlintDesignSyncPacket(new ArrayList<>(dataPackDesigns)));
        }
        GlintRules.syncTo(player); // item rules render client-side, so every client needs the current set
    }
}
