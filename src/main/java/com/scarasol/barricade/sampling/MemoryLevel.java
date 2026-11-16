package com.scarasol.barricade.sampling;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.WritableLevelData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.nbt.CompoundTag;

/** Bounded scratch storage. It never has a backing map chunk or storage directory. */
public class MemoryLevel extends Level {
    private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
    private final Set<BlockPos> writes = new LinkedHashSet<>();
    private final ArrayDeque<Runnable> updates = new ArrayDeque<>();
    private final Scoreboard scoreboard = new Scoreboard();
    private final FeatureFlagSet features;
    private RecipeManager recipes;
    private final Holder<Biome> biome;
    private final LevelEntityGetter<Entity> entityGetter = new LevelEntityGetterAdapter<>(
            new EntityLookup<>(), new EntitySectionStorage<>(Entity.class, key -> Visibility.HIDDEN));
    private boolean preparing;
    private int operations;
    private int chunkRequests;
    private final LevelTickAccess<Block> blockTicks = new UnsupportedTicks<>();
    private final LevelTickAccess<Fluid> fluidTicks = new UnsupportedTicks<>();

    public MemoryLevel(RegistryAccess registries, Holder<DimensionType> dimension,
                       FeatureFlagSet features, RecipeManager recipes, boolean client) {
        super(new Data(), Level.OVERWORLD, registries, dimension, () -> InactiveProfiler.INSTANCE,
                client, false, 0, 64);
        this.features = features;
        this.recipes = recipes;
        this.biome = registries.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
    }

    public void reset() {
        entities.values().forEach(BlockEntity::setRemoved);
        entities.clear();
        states.clear();
        writes.clear();
        updates.clear();
        operations = 0;
        preparing = false;
        random.setSeed(0);
    }

    public void seed(BlockPos pos, BlockState state) {
        preparing = true;
        try {
            setBlock(pos, state, 0, 0);
        } finally {
            preparing = false;
        }
        writes.clear();
        operations = 0;
    }

    public Set<BlockPos> writtenPositions() { return Set.copyOf(writes); }
    public int chunkRequests() { return chunkRequests; }
    public void recipes(RecipeManager recipes) { this.recipes = recipes; }

    /** Vanilla's standard NBT merge, without requiring a real server reference. */
    public boolean applyItemData(BlockPos pos, Player player, ItemStack stack) {
        CompoundTag data = BlockItem.getBlockEntityData(stack);
        BlockEntity entity = getBlockEntity(pos);
        if (data == null || entity == null
                || entity.onlyOpCanSetNbt() && (player == null || !player.canUseGameMasterBlocks())) return false;
        CompoundTag previous = entity.saveWithoutMetadata();
        CompoundTag merged = previous.copy().merge(data);
        if (merged.equals(previous)) return false;
        entity.load(merged);
        entity.setChanged();
        return true;
    }

    public void finishUpdates() {
        while (!updates.isEmpty()) {
            budget();
            updates.removeFirst().run();
        }
    }

    private void budget() {
        if (++operations > 4096) throw new UnsupportedOperationException("Sampling update budget exceeded");
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        return states.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) { return entities.get(pos); }
    @Override public void setBlockEntity(BlockEntity entity) {
        entity.setLevel(this);
        entities.put(entity.getBlockPos().immutable(), entity);
    }
    @Override public void removeBlockEntity(BlockPos pos) {
        BlockEntity entity = entities.remove(pos);
        if (entity != null) entity.setRemoved();
    }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        if (Math.abs(pos.getX()) > 8 || Math.abs(pos.getZ()) > 8 || pos.getY() < 0 || pos.getY() >= 32) {
            throw new UnsupportedOperationException("Write outside memory sampling bounds: " + pos);
        }
        if (!preparing) budget();
        BlockPos key = pos.immutable();
        BlockState before = getBlockState(pos);
        if (before == state) return false;
        states.put(key, state);
        if (!preparing) writes.add(key);
        if (!preparing && !isClientSide) before.onRemove(this, key, state, false);
        if (before.getBlock() != state.getBlock()) {
            removeBlockEntity(key);
            if (state.getBlock() instanceof EntityBlock source) {
                BlockEntity entity = source.newBlockEntity(key, state);
                if (entity != null) setBlockEntity(entity);
            }
        } else if (getBlockEntity(key) != null) {
            getBlockEntity(key).setBlockState(state);
        }
        if (!preparing && !isClientSide) {
            state.onPlace(this, key, before, false);
            if ((flags & 1) != 0) blockUpdated(key, state.getBlock());
            if ((flags & 16) == 0 && recursion > 0) {
                updates.addLast(() -> {
                    state.updateNeighbourShapes(this, key, flags & ~33, Math.min(recursion - 1, 16));
                    state.updateIndirectNeighbourShapes(this, key, flags & ~33, Math.min(recursion - 1, 16));
                });
            }
        }
        return true;
    }
    @Override public void blockUpdated(BlockPos pos, Block block) {
        if (preparing || isClientSide) return;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            updates.addLast(() -> getBlockState(neighbor).neighborChanged(this, neighbor, block, pos, false));
        }
    }
    @Override public void neighborChanged(BlockPos pos, Block block, BlockPos from) {
        if (!preparing && !isClientSide) {
            budget();
            getBlockState(pos).neighborChanged(this, pos, block, from, false);
        }
    }
    @Override public BlockGetter getChunkForCollisions(int x, int z) { return this; }
    @Override public ChunkSource getChunkSource() {
        chunkRequests++;
        throw new UnsupportedOperationException("Memory sampling never accesses a real chunk source");
    }
    @Override public boolean hasChunk(int x, int z) {
        return x >= -1 && x <= 0 && z >= -1 && z <= 0;
    }
    @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 0; }
    @Override public int getRawBrightness(BlockPos pos, int amount) { return 0; }
    @Override public float getShade(Direction direction, boolean shade) { return 1; }
    @Override public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) { return biome; }
    @Override public Holder<Biome> getBiome(BlockPos pos) { return biome; }
    @Override public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) {
        return resolver.getColor(biome.value(), pos.getX(), pos.getZ());
    }
    @Override public void blockEntityChanged(BlockPos pos) { }
    @Override public void sendBlockUpdated(BlockPos pos, BlockState before, BlockState after, int flags) { }
    @Override public FeatureFlagSet enabledFeatures() { return features; }
    @Override public LevelTickAccess<Block> getBlockTicks() { return blockTicks; }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return fluidTicks; }
    @Override public boolean addFreshEntity(Entity entity) { return false; }
    @Override public void playSeededSound(Player player, double x, double y, double z,
            Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed) { }
    @Override public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch, long seed) { }
    @Override public String gatherChunkSourceStats() { return "barricade-memory-only"; }
    @Override public Entity getEntity(int id) { return null; }
    @Override protected LevelEntityGetter<Entity> getEntities() { return entityGetter; }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public MapItemSavedData getMapData(String id) { return null; }
    @Override public void setMapData(String id, MapItemSavedData data) { }
    @Override public int getFreeMapId() { return 0; }
    @Override public void destroyBlockProgress(int breaker, BlockPos pos, int progress) { }
    @Override public Scoreboard getScoreboard() { return scoreboard; }
    @Override public RecipeManager getRecipeManager() { return recipes; }
    @Override public void levelEvent(Player player, int event, BlockPos pos, int data) { }
    @Override public void gameEvent(GameEvent event, Vec3 pos, GameEvent.Context context) { }
    @Override public long nextSubTickCount() { return 0; }

    private static final class UnsupportedTicks<T> implements LevelTickAccess<T> {
        @Override public void schedule(ScheduledTick<T> tick) {
            throw new UnsupportedOperationException("Material requires delayed world ticks");
        }
        @Override public boolean hasScheduledTick(BlockPos pos, T type) { return false; }
        @Override public boolean willTickThisTick(BlockPos pos, T type) { return false; }
        @Override public int count() { return 0; }
    }

    private static final class Data implements WritableLevelData {
        private final GameRules rules = new GameRules();
        @Override public int getXSpawn() { return 0; }
        @Override public int getYSpawn() { return 8; }
        @Override public int getZSpawn() { return 0; }
        @Override public float getSpawnAngle() { return 0; }
        @Override public long getGameTime() { return 0; }
        @Override public long getDayTime() { return 6000; }
        @Override public boolean isThundering() { return false; }
        @Override public boolean isRaining() { return false; }
        @Override public void setRaining(boolean raining) { }
        @Override public boolean isHardcore() { return false; }
        @Override public GameRules getGameRules() { return rules; }
        @Override public Difficulty getDifficulty() { return Difficulty.PEACEFUL; }
        @Override public boolean isDifficultyLocked() { return true; }
        @Override public void setXSpawn(int x) { }
        @Override public void setYSpawn(int y) { }
        @Override public void setZSpawn(int z) { }
        @Override public void setSpawnAngle(float angle) { }
    }
}
