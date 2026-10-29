package net.tunamods.customglint.module.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.standalone.SimpleUnbakedStandaloneModel;
import net.neoforged.neoforge.client.model.standalone.StandaloneModelKey;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.tunamods.customglint.module.block.ModBlocks;
import net.tunamods.customglint.common.CustomGlint;
import net.tunamods.customglint.common.client.GlintClientConfig;
import net.tunamods.customglint.module.block.GlintTableBlockEntity;

/**
 * Makes the placed Glint Table re-skin to match the player's chosen GUI skin (Default / Dark / Forge), a
 * purely client-side cosmetic tied to {@link GlintClientConfig#glintTableSkin()}, the same setting the table
 * window uses. The block is horizontally facing, and each (skin, facing) pair has its own variant model that
 * puts the front texture on the player-facing side; all are baked as standalone models. The block's baked
 * {@link BlockStateModel} for every facing is replaced (in {@link ModelEvent.ModifyBakingResult}) by a
 * delegate that forwards to the variant for the current skin at mesh time. Cycling the skin calls
 * {@link #refresh()} so loaded chunks re-mesh and pick up the new look.
 *
 * <p>The held / inventory ITEM keeps the base {@code block/glint_table} model (Forge, front north), only the
 * placed block follows the GUI skin.
 */
public final class GlintTableModelClient {
    private GlintTableModelClient() {}

    // Skin index matches GlintTableSkin.ALL / glintTableSkin() (0=Default, 1=Dark, 2=Forge).
    private static final String[] SKINS = { "default", "dark", "forge" };
    private static final Direction[] FACINGS = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

    // One standalone key per (skin, facing) variant model.
    private static final Map<String, StandaloneModelKey<BlockStateModel>> KEYS = new HashMap<>();
    static {
        for (String skin : SKINS) {
            for (Direction d : FACINGS) {
                String id = CustomGlint.res("glint_table_" + skin + "_" + d.getSerializedName()).toString();
                KEYS.put(id(skin, d), new StandaloneModelKey<>(() -> id));
            }
        }
    }

    private static String id(String skin, Direction d) {
        return skin + "|" + d.getSerializedName();
    }

    private static Identifier model(String skin, Direction d) {
        return CustomGlint.res("block/glint_table_" + skin + "_" + d.getSerializedName());
    }

    // Client-side positions of every loaded Glint Table, so a skin change can re-mesh just those sections
    // instead of the whole world. Fed by ChunkEvent on the client; both that and refresh() run on the client
    // thread, but a concurrent set keeps iteration safe regardless.
    private static final Set<BlockPos> CLIENT_TABLES = ConcurrentHashMap.newKeySet();

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(GlintTableModelClient::onRegisterStandalone);
        modEventBus.addListener(GlintTableModelClient::onModifyBakingResult);
        // Track loaded tables (client only) so refresh() can target their sections.
        NeoForge.EVENT_BUS.addListener(GlintTableModelClient::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(GlintTableModelClient::onChunkUnload);
    }

    private static void onChunkLoad(ChunkEvent.Load e) {
        if (!e.getLevel().isClientSide()) return;
        e.getChunk().getBlockEntities().forEach((pos, be) -> {
            if (be instanceof GlintTableBlockEntity) CLIENT_TABLES.add(pos.immutable());
        });
    }

    private static void onChunkUnload(ChunkEvent.Unload e) {
        if (!e.getLevel().isClientSide()) return;
        int cx = e.getChunk().getPos().x(), cz = e.getChunk().getPos().z();
        CLIENT_TABLES.removeIf(p -> (p.getX() >> 4) == cx && (p.getZ() >> 4) == cz);
    }

    /** Drop all tracked positions, call on disconnect so a later session can't re-mesh stale coords. */
    public static void clearTracked() {
        CLIENT_TABLES.clear();
    }

    private static void onRegisterStandalone(ModelEvent.RegisterStandalone e) {
        for (String skin : SKINS) {
            for (Direction d : FACINGS) {
                e.register(KEYS.get(id(skin, d)), SimpleUnbakedStandaloneModel.blockStateModel(model(skin, d)));
            }
        }
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult e) {
        Map<BlockState, BlockStateModel> models = e.getBakingResult().blockStateModels();
        BlockState base = ModBlocks.GLINT_TABLE_BLOCK.get().defaultBlockState();
        for (Direction d : FACINGS) {
            BlockState state = base.setValue(HorizontalDirectionalBlock.FACING, d);
            BlockStateModel fallback = models.get(state);
            if (fallback == null) continue;
            try {
                models.put(state, new SkinSwitchModel(d, fallback));
            } catch (UnsupportedOperationException ignored) {
                // immutable baking result on some setup, fall back to the static (Forge) model.
            }
        }
    }

    /** Re-mesh only the sections that contain a Glint Table so a placed table picks up a just-changed skin,
     *  instead of rebuilding every loaded chunk (allChanged(), a full-render-distance re-mesh that spiked on
     *  every skin click). The skin is baked into {@link SkinSwitchModel#createGeometryKey}, so each table's
     *  cached section mesh is stale until rebuilt; no other section is touched. Safe to call any time. */
    public static void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (BlockPos p : CLIENT_TABLES) {
            mc.levelExtractor.setSectionDirty(
                    SectionPos.blockToSectionCoord(p.getX()),
                    SectionPos.blockToSectionCoord(p.getY()),
                    SectionPos.blockToSectionCoord(p.getZ()));
        }
    }

    /** Delegating block model that forwards to the standalone variant for the current GUI skin + its facing. */
    private static final class SkinSwitchModel implements BlockStateModel {
        private final Direction facing;
        private final BlockStateModel fallback;

        SkinSwitchModel(Direction facing, BlockStateModel fallback) {
            this.facing = facing;
            this.fallback = fallback;
        }

        private BlockStateModel pick() {
            int idx = Math.floorMod(GlintClientConfig.glintTableSkin(), SKINS.length);
            BlockStateModel m = Minecraft.getInstance().getModelManager().getStandaloneModel(KEYS.get(id(SKINS[idx], facing)));
            return m != null ? m : fallback;
        }

        @Override
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
            pick().collectParts(random, output);
        }

        @Override
        public Material.Baked particleMaterial() {
            return pick().particleMaterial();
        }

        @Override
        public int materialFlags() {
            return pick().materialFlags();
        }

        @Override
        public void collectParts(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random, List<BlockStateModelPart> parts) {
            pick().collectParts(level, pos, state, random, parts);
        }

        @Override
        public Material.Baked particleMaterial(BlockAndTintGetter level, BlockPos pos, BlockState state) {
            return pick().particleMaterial(level, pos, state);
        }

        @Override
        public int materialFlags(BlockAndTintGetter level, BlockPos pos, BlockState state) {
            return pick().materialFlags(level, pos, state);
        }

        @Override
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos, BlockState state, RandomSource random) {
            // Geometry depends on the chosen skin and this model's fixed facing, so the mesher can cache per-skin.
            // Normalize the skin index the same way pick() does, so the cache key matches the skin rendered.
            // Stride 8 > the 6 Direction ordinals, so (skin, facing) pairs never collide in the key.
            return Math.floorMod(GlintClientConfig.glintTableSkin(), SKINS.length) * 8 + facing.ordinal();
        }
    }
}
