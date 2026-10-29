package net.tunamods.customglint.module.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.tunamods.customglint.module.menu.GlintTableMenu;

/**
 * The Glint Table, a workbench for building Glint Trims. Opens {@link GlintTableMenu} on use. The table's
 * contents are stored per player (see {@link GlintTableBlockEntity}), so every table shows the same materials.
 * Horizontally facing, so the front panel always faces the player who placed it. The placed model also
 * re-skins to the player's chosen GUI skin client-side (see {@code GlintTableModelClient}).
 */
public class GlintTableBlock extends HorizontalDirectionalBlock implements EntityBlock {

    public GlintTableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Front faces the player: the opposite of the direction they are looking when placing.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GlintTableBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GlintTableBlockEntity be) {
            player.openMenu(be);
        }
        return InteractionResult.SUCCESS;
    }
}
