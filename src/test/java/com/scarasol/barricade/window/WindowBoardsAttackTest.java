package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MemoryLevel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.registries.*;
import org.junit.jupiter.api.*;

class WindowBoardsAttackTest {
    private static final BlockPos POS = new BlockPos(0, 8, 0);
    private static WindowBoardsBlock boards;
    private static WindowBarsBlock bars;
    private Map<TagKey<Item>, List<Holder<Item>>> originalTags;

    @BeforeAll static void bootstrap() {
        MemoryFixture.create(false);
        WindowBoardsTestRegistries.initializeFluids();
        WindowBoardsTestRegistries.initializePlayerAttributes();
        var forge = (ForgeRegistry<Block>) ForgeRegistries.BLOCKS;
        boolean locked = forge.isLocked();
        forge.unfreeze();
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        try {
            boards = new WindowBoardsBlock();
            bars = new WindowBarsBlock();
            forge.register(-1, new ResourceLocation("barricade", "attack_test_boards"), boards);
            forge.register(-1, new ResourceLocation("barricade", "attack_test_bars"), bars);
        } finally {
            BuiltInRegistries.BLOCK.freeze();
            if (locked) forge.freeze();
        }
        for (var block : List.of(boards, bars))
            block.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }

    @BeforeEach void bindDefaults() {
        var registry = BuiltInRegistries.ITEM;
        registry.getOrCreateTag(WindowSealBlock.ATTACK_THROUGH_ITEMS);
        originalTags = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        allow(Items.TRIDENT);
    }
    @AfterEach void restoreTags() { BuiltInRegistries.ITEM.bindTags(originalTags); }
    private void allow(Item... items) {
        var tags = new HashMap<>(originalTags);
        tags.put(WindowSealBlock.ATTACK_THROUGH_ITEMS,
                Arrays.stream(items).map(BuiltInRegistries.ITEM::wrapAsHolder).toList());
        BuiltInRegistries.ITEM.bindTags(tags);
    }

    @Test void bothMaterialsAndEveryFacingAllowNativeEntityPicksOnlyForTaggedMainHands() {
        for (boolean client : new boolean[]{false, true}) for (var block : List.of(boards, bars))
            for (var facing : Direction.values()) {
                var level = new CombatLevel(client);
                level.blocks.put(POS, block.defaultBlockState().setValue(WindowSealBlock.FACING, facing));
                var player = player(level, facing);
                var target = level.target(Vec3.atCenterOf(POS).subtract(axis(facing).scale(1)));
                HitResult selection = selection(player);
                assertEquals(POS, ((BlockHitResult) selection).getBlockPos(), "Facing " + facing);
                var picked = assertInstanceOf(EntityHitResult.class, WindowBoardsAttack.pick(player, player, selection));
                assertSame(target, picked.getEntity());
                assertEquals(1, level.entityQueries);
                assertEquals(POS, ((BlockHitResult) selection(player)).getBlockPos(),
                        "Ordinary crosshair/use rays must still see the seal");
                assertFalse(level.blocks.get(POS).getCollisionShape(level, POS, CollisionContext.of(player)).isEmpty());
                for (var item : List.of(Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD,
                        Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD, Items.STICK)) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item));
                    int reads = level.blockReads;
                    assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
                    assertEquals(reads, level.blockReads, "Non-whitelisted hands must not cast another ray");
                }
                player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TRIDENT));
                assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
            }
    }

    @Test void anotherWallStopsTheAttackAfterAnyNumberOfSeals() {
        for (var facing : Direction.values()) {
            var level = new CombatLevel(false);
            level.blocks.put(POS, boards.defaultBlockState().setValue(WindowSealBlock.FACING, facing));
            BlockPos second = POS.relative(facing.getOpposite());
            level.blocks.put(second, bars.defaultBlockState().setValue(WindowSealBlock.FACING, facing));
            var player = player(level, facing);
            player.getAttribute(ForgeMod.ENTITY_REACH.get()).setBaseValue(5);
            var target = level.target(Vec3.atCenterOf(POS).subtract(axis(facing).scale(2.5)));
            HitResult selection = selection(player);
            assertSame(target, assertInstanceOf(EntityHitResult.class,
                    WindowBoardsAttack.pick(player, player, selection)).getEntity());
            level.blocks.put(second, Blocks.STONE.defaultBlockState());
            assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
            level.blocks.put(POS, Blocks.STONE.defaultBlockState());
            selection = selection(player);
            int queries = level.entityQueries;
            assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
            assertEquals(queries, level.entityQueries);
        }
    }

    @Test void attackRangeComesFromTheForgeAttributeAndOnlyTheClosestEligibleEntityIsPicked() {
        var level = level();
        var player = player(level, Direction.NORTH);
        var far = level.target(new Vec3(0.5, 8.5, 3.5));
        HitResult selection = selection(player);
        assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
        player.getAttribute(ForgeMod.ENTITY_REACH.get()).setBaseValue(5);
        assertSame(far, assertInstanceOf(EntityHitResult.class,
                WindowBoardsAttack.pick(player, player, selection)).getEntity());
        var close = level.target(new Vec3(0.5, 8.5, 1.5));
        assertSame(close, assertInstanceOf(EntityHitResult.class,
                WindowBoardsAttack.pick(player, player, selection)).getEntity());
        close.pickable = false;
        assertSame(far, assertInstanceOf(EntityHitResult.class,
                WindowBoardsAttack.pick(player, player, selection)).getEntity());
        close.pickable = true;
        close.spectator = true;
        assertSame(far, assertInstanceOf(EntityHitResult.class,
                WindowBoardsAttack.pick(player, player, selection)).getEntity());
        player.getAttribute(ForgeMod.ENTITY_REACH.get()).setBaseValue(0);
        int queries = level.entityQueries;
        assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
        assertEquals(queries, level.entityQueries);
    }

    @Test void sharedAttackRaysSkipBothSealMaterialsButNotOtherWallsInEitherShapeMode() {
        for (var mode : List.of(ClipContext.Block.OUTLINE, ClipContext.Block.COLLIDER))
            for (var facing : Direction.values()) {
                var level = new CombatLevel(false);
                level.blocks.put(POS, boards.defaultBlockState().setValue(WindowSealBlock.FACING, facing));
                BlockPos second = POS.relative(facing.getOpposite());
                level.blocks.put(second, bars.defaultBlockState().setValue(WindowSealBlock.FACING, facing));
                var player = player(level, facing);
                Vec3 from = player.getEyePosition();
                Vec3 to = from.subtract(axis(facing).scale(5));
                assertEquals(HitResult.Type.MISS, WindowBoardsAttack.clip(player, from, to, mode).getType());
                level.blocks.put(second, Blocks.STONE.defaultBlockState());
                var wall = WindowBoardsAttack.clip(player, from, to, mode);
                assertEquals(HitResult.Type.BLOCK, wall.getType());
                assertEquals(second, wall.getBlockPos());
                assertFalse(level.blocks.get(POS).getCollisionShape(level, POS, CollisionContext.of(player)).isEmpty());
            }
    }

    @Test void noEntityMissesAndOtherCameraEntitiesKeepTheOriginalSelectionAndBlockBreaking() {
        var level = level();
        var player = player(level, Direction.NORTH);
        HitResult selection = selection(player);
        assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
        level.blocks.put(POS.south(), Blocks.STONE.defaultBlockState());
        assertSame(selection, WindowBoardsAttack.pick(player, player, selection),
                "A weapon must not start breaking a wall behind the seal");
        var target = level.target(new Vec3(0.5, 8.5, 1.5));
        var entityHit = new EntityHitResult(target);
        assertSame(entityHit, WindowBoardsAttack.pick(player, player, entityHit));
        var miss = BlockHitResult.miss(new Vec3(0, 8, 4), Direction.NORTH, POS);
        assertSame(miss, WindowBoardsAttack.pick(player, player, miss));
        assertNull(WindowBoardsAttack.pick(player, player, null));
        assertSame(selection, WindowBoardsAttack.pick(player, target, selection));
        assertFalse(WindowBoardsAttack.pick(player, player, selection) instanceof EntityHitResult,
                "The stone wall must remain an obstruction");
    }

    @Test void itemTagReloadImmediatelyUpdatesTheWhitelistWithoutCachingOrChangingPlayerCollision() {
        var level = level();
        var player = player(level, Direction.NORTH);
        level.target(new Vec3(0.5, 8.5, 1.5));
        HitResult selection = selection(player);
        assertInstanceOf(EntityHitResult.class, WindowBoardsAttack.pick(player, player, selection));
        Vec3 movement = new Vec3(0, 0, 3);
        assertTrue(Entity.collideBoundingBox(player, movement, player.getBoundingBox(), level, List.of()).z < movement.z);
        assertFalse(player.noPhysics);
        allow();
        assertSame(selection, WindowBoardsAttack.pick(player, player, selection));
        allow(Items.STICK);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        assertInstanceOf(EntityHitResult.class, WindowBoardsAttack.pick(player, player, selection));
        assertTrue(Entity.collideBoundingBox(player, movement, player.getBoundingBox(), level, List.of()).z < movement.z);
    }

    @Test void nativeAttacksKeepDamageCooldownAndSwordSweepsWithoutGivingTridentsSweeping() {
        allow(Items.IRON_SWORD, Items.TRIDENT);
        for (Item weapon : List.of(Items.IRON_SWORD, Items.TRIDENT)) {
            var level = level();
            var player = player(level, Direction.NORTH);
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
            player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(7);
            player.readyAttack();
            var target = new Skeleton(EntityType.SKELETON, level);
            target.setPos(0.5, 7, 1.5);
            var nearby = new Skeleton(EntityType.SKELETON, level);
            nearby.setPos(1.1, 7, 1.5);
            level.targets.addAll(List.of(target, nearby));
            assertTrue(player.getAttackStrengthScale(0.5f) > 0.9f);
            assertTrue(player.onGround());
            assertFalse(player.isSprinting());
            assertTrue(player.walkDist - player.walkDistO < player.getSpeed());
            assertEquals(weapon == Items.IRON_SWORD, player.getMainHandItem().canPerformAction(ToolActions.SWORD_SWEEP));
            var picked = assertInstanceOf(EntityHitResult.class,
                    WindowBoardsAttack.pick(player, player, selection(player)));
            assertSame(target, picked.getEntity());
            float targetHealth = target.getHealth(), nearbyHealth = nearby.getHealth();
            player.attack(picked.getEntity());
            assertTrue(target.getHealth() < targetHealth, "The picked target must take normal attack damage");
            assertEquals(weapon == Items.IRON_SWORD, nearby.getHealth() < nearbyHealth,
                    "Native sweep: queries=" + level.sweepQueries + ", reach=" + player.getEntityReach()
                            + ", distanceSquared=" + player.distanceToSqr(nearby) + ", allied=" + player.isAlliedTo(nearby));
            assertTrue(player.getAttackStrengthScale(0.5f) < 1, "The normal attack must consume its cooldown");
            assertSame(boards.defaultBlockState(), level.blocks.get(POS));
        }
    }

    @Test void packagedWhitelistContainsOnlyTridentsWithNormalDataPackSemantics() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/barricade/tags/items/window_boards_attack_through.json")) {
            assertNotNull(input);
            var file = TagFile.CODEC.parse(JsonOps.INSTANCE,
                    JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))).result().orElseThrow();
            assertFalse(file.replace());
            var entries = file.entries().stream().map(entry -> new TagLoader.EntryWithSource(entry, "barricade")).toList();
            var loader = new TagLoader<Item>(BuiltInRegistries.ITEM::getOptional, "tags/items");
            var loaded = loader.build(Map.of(WindowSealBlock.ATTACK_THROUGH_ITEMS.location(), entries));
            assertEquals(Set.of(Items.TRIDENT),
                    Set.copyOf(loaded.get(WindowSealBlock.ATTACK_THROUGH_ITEMS.location())));
        }
    }

    private static CombatLevel level() {
        var level = new CombatLevel(false);
        level.blocks.put(POS, boards.defaultBlockState());
        return level;
    }
    private static Vec3 axis(Direction facing) { return Vec3.atLowerCornerOf(facing.getNormal()); }
    private static HitResult selection(Player player) { return player.pick(5, 1, false); }
    private static TestPlayer player(CombatLevel level, Direction facing) {
        var player = new TestPlayer(level);
        Vec3 eye = Vec3.atCenterOf(POS).add(axis(facing).scale(1.5));
        player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
        Vec3 direction = axis(facing).scale(-1);
        player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
        player.setYHeadRot(player.getYRot());
        player.setXRot((float) -Math.toDegrees(Math.asin(direction.y)));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TRIDENT));
        return player;
    }

    private static final class TestPlayer extends Player {
        TestPlayer(Level level) { super(level, POS, 0, new GameProfile(new UUID(1, 2), "window_attack_test")); }
        void readyAttack() {
            setOnGround(true);
            attackStrengthTicker = 100;
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    private static final class CombatLevel extends MemoryLevel {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final List<Entity> targets = new ArrayList<>();
        int entityQueries, blockReads, sweepQueries;
        CombatLevel(boolean client) { this(MemoryFixture.create(client)); }
        private CombatLevel(Level source) {
            super(source.registryAccess(), source.dimensionTypeRegistration(), source.enabledFeatures(),
                    source.getRecipeManager(), source.isClientSide);
        }
        Target target(Vec3 center) {
            var target = new Target(this, center);
            targets.add(target);
            return target;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            blockReads++;
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public boolean hasChunk(int x, int z) { return true; }
        @Override public List<Entity> getEntities(Entity except, AABB bounds, Predicate<? super Entity> filter) {
            entityQueries++;
            return targets.stream().filter(entity -> entity != except && entity.getBoundingBox().intersects(bounds)
                    && filter.test(entity)).toList();
        }
        @Override public <T extends Entity> List<T> getEntitiesOfClass(Class<T> type, AABB bounds, Predicate<? super T> filter) {
            sweepQueries++;
            return targets.stream().filter(type::isInstance).map(type::cast)
                    .filter(entity -> entity.getBoundingBox().intersects(bounds) && filter.test(entity)).toList();
        }
    }
    private static final class Target extends Entity {
        boolean pickable = true, spectator;
        Target(Level level, Vec3 center) {
            super(EntityType.SNOWBALL, level);
            setPos(center);
            setBoundingBox(new AABB(center.subtract(0.25, 0.25, 0.25), center.add(0.25, 0.25, 0.25)));
        }
        @Override public boolean isPickable() { return pickable; }
        @Override public boolean isSpectator() { return spectator; }
        @Override protected void defineSynchedData() {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
