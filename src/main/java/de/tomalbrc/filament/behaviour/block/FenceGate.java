package de.tomalbrc.filament.behaviour.block;

import com.google.gson.JsonParseException;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.tomalbrc.filament.api.behaviour.BlockBehaviour;
import de.tomalbrc.filament.data.AbstractBlockData;
import de.tomalbrc.filament.data.BlockData;
import de.tomalbrc.filament.data.properties.BlockProperties;
import de.tomalbrc.filament.util.FilamentBlockResourceUtils;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public class FenceGate implements BlockBehaviour<FenceGate.Config> {
  private final Config config;

  public FenceGate(Config config) {
    this.config = config;
  }

  @Override
  @NotNull
  public Config getConfig() {
    return this.config;
  }

  @Override
  public Optional<Boolean> isPathfindable(BlockState state, PathComputationType pathComputationType) {
    switch (pathComputationType) {
      case LAND, AIR -> {
        return Optional.of(state.getValue(BlockStateProperties.OPEN));
      }
      default -> {
        return Optional.of(false);
      }
    }
  }

  @Override
  public InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
    if (state.getValue(BlockStateProperties.OPEN)) {
      state = state.setValue(BlockStateProperties.OPEN, false);
      level.setBlock(pos, state, 10);
    } else {
      Direction direction = player.getDirection();
      if (state.getValue(BlockStateProperties.FACING) == direction.getOpposite()) {
        state = state.setValue(BlockStateProperties.FACING, direction);
      }

      state = state.setValue(BlockStateProperties.OPEN, true);
      level.setBlock(pos, state, 10);
    }

    boolean bl = state.getValue(BlockStateProperties.OPEN);
    level.playSound(player, pos, SoundEvent.createVariableRangeEvent(bl ? config.openSound : config.closeSound), SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
    level.gameEvent(player, bl ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    return InteractionResult.SUCCESS;
  }

  @Override
  public void onExplosionHit(BlockState blockState, ServerLevel level, BlockPos blockPos, Explosion explosion, BiConsumer<ItemStack, BlockPos> biConsumer) {
    if (explosion.canTriggerBlocks() && this.config.canOpenByWindCharge && !blockState.getValue(BlockStateProperties.POWERED)) {
      this.toggle(blockState, level, blockPos, null);
    }
  }

  private void toggle(BlockState state, Level level, BlockPos pos, @Nullable Player player) {
    boolean bl = state.getValue(BlockStateProperties.OPEN);
    level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.OPEN, !bl));
    this.playSound(null, level, pos, !bl);
  }

  protected void playSound(@Nullable Player player, Level level, BlockPos blockPos, boolean open) {
    level.playSound(player, blockPos, SoundEvent.createVariableRangeEvent(open ? this.config.openSound : this.config.closeSound), SoundSource.BLOCKS, 1.0f, level.getRandom().nextFloat() * 0.1f + 0.9f);
    level.gameEvent(player, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, blockPos);
  }

  @Override
  public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, @Nullable Orientation orientation, boolean movedByPiston) {
    if (!level.isClientSide()) {
      boolean bl = level.hasNeighborSignal(pos);
      if (state.getValue(BlockStateProperties.POWERED) != bl) {
        level.setBlock(pos, state.setValue(BlockStateProperties.POWERED, bl).setValue(BlockStateProperties.OPEN, bl), 2);
        if (state.getValue(BlockStateProperties.OPEN) != bl) {
          level.playSound(null, pos, SoundEvent.createVariableRangeEvent(bl ? config.openSound : config.closeSound), SoundSource.BLOCKS, 1.0F, level.getRandom().nextFloat() * 0.1F + 0.9F);
          level.gameEvent(null, bl ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
        }
      }

    }
  }

  @Override
  public BlockState getStateForPlacement(BlockState self, BlockPlaceContext context) {
    Level level = context.getLevel();
    BlockPos blockPos = context.getClickedPos();
    boolean bl = level.hasNeighborSignal(blockPos);
    Direction direction = context.getHorizontalDirection();
    Direction.Axis axis = direction.getAxis();
    boolean bl2 = axis == Direction.Axis.Z && (this.isWall(level.getBlockState(blockPos.west())) || this.isWall(level.getBlockState(blockPos.east()))) || axis == Direction.Axis.X && (this.isWall(level.getBlockState(blockPos.north())) || this.isWall(level.getBlockState(blockPos.south())));
    return this.modifyDefaultState(self).setValue(BlockStateProperties.FACING, direction).setValue(BlockStateProperties.OPEN, bl).setValue(BlockStateProperties.POWERED, bl).setValue(BlockStateProperties.IN_WALL, bl2);
  }
  private boolean isWall(BlockState state) {
    return state.is(BlockTags.WALLS);
  }

  @Override
  public void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
    builder.add(BlockStateProperties.FACING, BlockStateProperties.OPEN, BlockStateProperties.POWERED, BlockStateProperties.IN_WALL);
  }


  @Override
  public BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess scheduledTickAccess, BlockPos pos, Direction direction, BlockPos neighborPos, BlockState neighborState, RandomSource random) {
    Direction.Axis axis = direction.getAxis();
    if (state.getValue(BlockStateProperties.FACING).getClockWise().getAxis() != axis) {
      return state;
    } else {
      boolean bl = this.isWall(neighborState) || this.isWall(level.getBlockState(pos.relative(direction.getOpposite())));
      return state.setValue(BlockStateProperties.IN_WALL, bl);
    }
  }

  @Override
  public BlockState modifyDefaultState(BlockState blockState) {
    return blockState.setValue(BlockStateProperties.OPEN, false).setValue(BlockStateProperties.POWERED, false).setValue(BlockStateProperties.IN_WALL, false);
  }

  @Override
  public boolean modifyStateMap(Map<BlockState, BlockData.BlockStateMeta> map, AbstractBlockData<? extends BlockProperties> data) {
    for (Map.Entry<String, PolymerBlockModel> entry : data.blockResource().models().entrySet()) {
      PolymerBlockModel blockModel = entry.getValue();

      BlockStateParser.BlockResult parsed;
      String str = String.format("%s[%s]", data.id(), entry.getKey());
      try {
        parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, str, false);
      } catch (CommandSyntaxException e) {
        throw new JsonParseException("Invalid BlockState value: " + str);
      }
      BlockState requestedState = FilamentBlockResourceUtils.requestBlock(getFenceState(parsed.blockState()), blockModel, data.virtual());
      map.put(parsed.blockState(), BlockData.BlockStateMeta.of(requestedState, blockModel));
    }
    return true;
  }

  private BlockModelType getFenceState(BlockState state) {
    boolean open = state.getValue(BlockStateProperties.OPEN);
    boolean inwall = state.getValue(BlockStateProperties.IN_WALL);
    Direction.Axis axis = state.getValue(BlockStateProperties.FACING).getAxis();
    if (open) {
      if (inwall) {
        return switch (axis) {
          case Z -> BlockModelType.NORTH_SOUTH_INWALL_OPEN_GATE;
          case X -> BlockModelType.EAST_WEST_INWALL_OPEN_GATE;
          default -> throw new IllegalArgumentException("Only horizontal axis are supported!");
        };
      }
      return switch (axis) {
        case Z -> BlockModelType.NORTH_SOUTH_OPEN_GATE;
        case X -> BlockModelType.EAST_WEST_OPEN_GATE;
        default -> throw new IllegalArgumentException("Only horizontal axis are supported!");
      };
    }

    if (inwall) {
      return switch (axis) {
        case Z -> BlockModelType.NORTH_SOUTH_INWALL_GATE;
        case X -> BlockModelType.EAST_WEST_INWALL_GATE;
        default -> throw new IllegalArgumentException("Only horizontal axis are supported!");
      };
    }
    return switch (axis) {
      case Z -> BlockModelType.NORTH_SOUTH_GATE;
      case X -> BlockModelType.EAST_WEST_GATE;
      default -> throw new IllegalArgumentException("Only horizontal axis are supported!");
    };
  }

  @Override
  public BlockState modifyPolymerBlockState(BlockState original, BlockState blockState) {
    return blockState.setValue(BlockStateProperties.POWERED, false);
  }

  public static class Config {
    public boolean canOpenByWindCharge = true;
    public ResourceLocation openSound = SoundEvents.FENCE_GATE_OPEN.location();
    public ResourceLocation closeSound = SoundEvents.FENCE_GATE_CLOSE.location();
  }
}
