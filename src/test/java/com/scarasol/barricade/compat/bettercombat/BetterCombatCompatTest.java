package com.scarasol.barricade.compat.bettercombat;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.authlib.GameProfile;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MemoryLevel;
import com.scarasol.barricade.window.WindowBoardsBlock;
import com.scarasol.barricade.window.WindowSealBlock;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.bettercombat.BetterCombat;
import net.bettercombat.api.*;
import net.bettercombat.config.ServerConfig;
import net.bettercombat.logic.PlayerAttackProperties;
import net.bettercombat.logic.WeaponRegistry;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.registries.*;
import org.junit.jupiter.api.*;

class BetterCombatCompatTest {
    private static final BlockPos POS = new BlockPos(0, 8, 0);
    private static WindowBoardsBlock boards;
    private Map<TagKey<Item>, List<Holder<Item>>> originalTags;
    private Map<Item, WeaponAttributes> originalAttributes;
    private ServerConfig originalConfig;

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
            forge.register(-1, new ResourceLocation("barricade", "better_combat_test_boards"), boards);
        } finally {
            BuiltInRegistries.BLOCK.freeze();
            if (locked) forge.freeze();
        }
        boards.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }

    @BeforeEach void prepare() {
        var registry = BuiltInRegistries.ITEM;
        registry.getOrCreateTag(WindowSealBlock.ATTACK_THROUGH_ITEMS);
        originalTags = registry.getTags().collect(Collectors.toMap(
                pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
        originalAttributes = new HashMap<>();
        for (var item : List.of(Items.STICK, Items.BLAZE_ROD))
            originalAttributes.put(item, WeaponRegistry.getAttributes(new ItemStack(item)));
        originalConfig = BetterCombat.config;
        BetterCombat.config = new ServerConfig();
        // Native BC rays require a live Minecraft singleton; the shared collider rays have their own tests.
        BetterCombat.config.allow_attacking_thru_walls = true;
        allow();
    }

    @AfterEach void restore() {
        BuiltInRegistries.ITEM.bindTags(originalTags);
        originalAttributes.forEach((item, attributes) ->
                WeaponRegistry.register(BuiltInRegistries.ITEM.getKey(item), attributes));
        BetterCombat.config = originalConfig;
    }

    @Test void whitelistOrExactlyOneOfTheThreeCategoriesGrantsPassageWithoutAnAnimationRestriction() {
        var categories = Arrays.asList("spear", "lance", "trident", "sword", "rapier", "dagger",
                "halberd", "soul_knife", "hammer", "wand", "", null, "Spear", "othermod:spear");
        for (String category : categories) {
            register(Items.STICK, category);
            var player = player(new CombatLevel());
            allow();
            var hand = BetterCombatCompat.currentAttack(player);
            assertNotNull(hand);
            assertEquals(Set.of("spear", "lance", "trident").contains(category == null ? "" : category),
                    BetterCombatCompat.canAttackThrough(hand), "Untagged category: " + category);
            allow(Items.STICK);
            assertTrue(BetterCombatCompat.canAttackThrough(hand), "Tag must override category: " + category);
            allow();
            assertFalse(hand.itemStack().is(WindowSealBlock.ATTACK_THROUGH_ITEMS));
        }
    }

    @Test void actualAttackingHandControlsBothAutomaticAndTaggedPassage() {
        register(Items.STICK, "spear");
        register(Items.BLAZE_ROD, "sword");
        var player = player(new CombatLevel());
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.BLAZE_ROD));
        for (int combo = 0; combo < 4; combo++) {
            player.setComboCount(combo);
            var hand = BetterCombatCompat.currentAttack(player);
            assertEquals(combo % 2 == 1, hand.isOffHand());
            assertEquals(combo % 2 == 0, BetterCombatCompat.canAttackThrough(hand));
        }
        allow(Items.BLAZE_ROD);
        player.setComboCount(1);
        assertTrue(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));

        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLAZE_ROD));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STICK));
        allow();
        player.setComboCount(0);
        assertFalse(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        player.setComboCount(1);
        assertTrue(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        register(Items.STICK, "sword");
        allow(Items.BLAZE_ROD);
        assertFalse(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)),
                "A tagged main hand must not grant passage to the attacking off hand");
    }

    @Test void changedWeaponDefinitionsAndReloadedTagsTakeEffectWithoutAPersistentPermissionCache() {
        var player = player(new CombatLevel());
        register(Items.STICK, "sword");
        assertFalse(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        register(Items.STICK, "lance");
        assertTrue(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        register(Items.STICK, "dagger");
        assertFalse(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        allow(Items.STICK);
        assertTrue(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
        allow();
        assertFalse(BetterCombatCompat.canAttackThrough(BetterCombatCompat.currentAttack(player)));
    }

    @Test void sealInputUsesBetterCombatRangeAndHitboxRatherThanTheVanillaCenterRayOrReach() {
        register(Items.STICK, "spear");
        var level = new CombatLevel();
        var player = player(level);
        player.getAttribute(ForgeMod.ENTITY_REACH.get()).setBaseValue(0);
        var target = level.target(new Vec3(0.9, 8.25, 1.5));
        var hand = BetterCombatCompat.currentAttack(player);
        HitResult original = selection(player);
        assertEquals(POS, ((BlockHitResult) original).getBlockPos());
        assertSame(target, assertInstanceOf(EntityHitResult.class,
                BetterCombatCompat.pick(player, original, hand)).getEntity());
        assertEquals(1, level.entityQueries);
        assertSame(boards.defaultBlockState(), level.blocks.get(POS));
        target.setPos(0.9, 8.25, 6);
        target.setBoundingBox(new AABB(target.position().subtract(0.25, 0.25, 0.25),
                target.position().add(0.25, 0.25, 0.25)));
        assertSame(original, BetterCombatCompat.pick(player, original, hand));
    }

    @Test void failedQualificationsAndNonSealHitsKeepNativeMiningWithoutAnotherTargetQuery() {
        register(Items.STICK, "sword");
        var level = new CombatLevel();
        var player = player(level);
        HitResult original = selection(player);
        var hand = BetterCombatCompat.currentAttack(player);
        int reads = level.blockReads;
        assertSame(original, BetterCombatCompat.pick(player, original, hand));
        assertEquals(reads, level.blockReads);
        assertEquals(0, level.entityQueries);
        allow(Items.STICK);
        level.blocks.put(POS, Blocks.STONE.defaultBlockState());
        assertSame(original, BetterCombatCompat.pick(player, original, hand));
        assertEquals(0, level.entityQueries);
        var miss = BlockHitResult.miss(Vec3.atCenterOf(POS), Direction.NORTH, POS);
        assertSame(miss, BetterCombatCompat.pick(player, miss, hand));
        assertNull(BetterCombatCompat.pick(player, null, hand));
        level.blocks.put(POS, boards.defaultBlockState());
        assertSame(original, BetterCombatCompat.pick(player, original, hand),
                "No target must leave ordinary seal breaking available");
    }

    private void allow(Item... items) {
        var tags = new HashMap<>(originalTags);
        tags.put(WindowSealBlock.ATTACK_THROUGH_ITEMS,
                Arrays.stream(items).map(BuiltInRegistries.ITEM::wrapAsHolder).toList());
        BuiltInRegistries.ITEM.bindTags(tags);
    }

    private static void register(Item item, String category) {
        var attack = new WeaponAttributes.Attack(new WeaponAttributes.Condition[0],
                WeaponAttributes.HitBoxShape.FORWARD_BOX, 1, 0, 0.5,
                "example:custom_attack", null, null);
        WeaponRegistry.register(BuiltInRegistries.ITEM.getKey(item), new WeaponAttributes(
                3.5, "", "", false, category, new WeaponAttributes.Attack[]{attack}));
    }

    private static HitResult selection(Player player) { return player.pick(5, 1, false); }
    private static TestPlayer player(CombatLevel level) {
        var player = new TestPlayer(level);
        player.setPos(0.5, 8.5 - player.getEyeHeight(), -1);
        player.setYRot(0);
        player.setYHeadRot(0);
        player.setXRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        return player;
    }

    private static final class TestPlayer extends Player implements PlayerAttackProperties {
        private int combo;
        TestPlayer(Level level) { super(level, POS, 0, new GameProfile(new UUID(3, 4), "better_combat_test")); }
        @Override public int getComboCount() { return combo; }
        @Override public void setComboCount(int count) { combo = count; }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return false; }
    }

    private static final class CombatLevel extends MemoryLevel {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final List<Entity> targets = new ArrayList<>();
        int entityQueries, blockReads;
        CombatLevel() { this(MemoryFixture.create(false)); }
        private CombatLevel(Level source) {
            super(source.registryAccess(), source.dimensionTypeRegistration(), source.enabledFeatures(),
                    source.getRecipeManager(), false);
            blocks.put(POS, boards.defaultBlockState());
        }
        Target target(Vec3 center) {
            var target = new Target(this);
            target.setPos(center);
            target.setBoundingBox(new AABB(center.subtract(0.25, 0.25, 0.25), center.add(0.25, 0.25, 0.25)));
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
            return targets.stream().filter(entity -> entity != except && bounds.intersects(entity.getBoundingBox())
                    && filter.test(entity)).toList();
        }
    }

    private static final class Target extends Entity {
        Target(Level level) { super(EntityType.SNOWBALL, level); }
        @Override public boolean isPickable() { return true; }
        @Override protected void defineSynchedData() {}
        @Override protected void readAdditionalSaveData(CompoundTag tag) {}
        @Override protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
