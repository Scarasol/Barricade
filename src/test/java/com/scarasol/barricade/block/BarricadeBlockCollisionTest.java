package com.scarasol.barricade.block;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.WindowBoardsTestRegistries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BarricadeBlockCollisionTest {
    private static final BlockPos POS = new BlockPos(0, 8, 0);
    private static BarricadeBlock block;

    @BeforeAll
    @SuppressWarnings("deprecation")
    static void bootstrap() {
        MemoryFixture.create(false);
        WindowBoardsTestRegistries.initializeFluids();
        var registry = (ForgeRegistry<Block>) ForgeRegistries.BLOCKS;
        boolean locked = registry.isLocked();
        registry.unfreeze();
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).unfreeze();
        try {
            block = new BarricadeBlock();
            registry.register(-1, new ResourceLocation("barricade", "collision_fixture"), block);
        } finally {
            BuiltInRegistries.BLOCK.freeze();
            if (locked) registry.freeze();
        }
        block.getStateDefinition().getPossibleStates().forEach(BlockState::initCache);
    }

    @Test void collisionIsOneAndAHalfBlocksWhileOutlineAndCameraStayOneBlockLikeFences() {
        for (boolean client : new boolean[]{false, true}) {
            var level = MemoryFixture.create(client);
            BlockState state = block.defaultBlockState();
            var collision = state.getCollisionShape(level, POS);
            assertEquals(new AABB(0, 0, 0, 1, 1.5, 1), collision.bounds());
            assertEquals(new AABB(0, 0, 0, 1, 1, 1), state.getShape(level, POS).bounds());
            assertEquals(state.getShape(level, POS).bounds(),
                    state.getVisualShape(level, POS, CollisionContext.empty()).bounds());
            assertSame(collision, state.getCollisionShape(level, POS, CollisionContext.empty()));
            assertSame(collision, state.getCollisionShape(level, POS.above()));
            assertTrue(state.hasLargeCollisionShape());
            var fence = Blocks.OAK_FENCE.defaultBlockState();
            assertEquals(fence.getCollisionShape(level, POS).max(Direction.Axis.Y),
                    collision.max(Direction.Axis.Y));
            assertEquals(fence.getShape(level, POS).max(Direction.Axis.Y),
                    state.getShape(level, POS).max(Direction.Axis.Y));
        }
    }

    @Test void embeddedCollisionIncludesTheBarricadeAndPreservesAnyProtrudingBlockShape() {
        var small = Shapes.box(.25, .25, .25, .75, .75, .75);
        assertEquals(new AABB(0, 0, 0, 1, 1.5, 1),
                EmbeddedCollision.includeBarricade(small).bounds());

        var protruding = Shapes.box(-.25, 0, 0, 1.5, 2, 1);
        assertEquals(new AABB(-.25, 0, 0, 1.5, 2, 1),
                EmbeddedCollision.includeBarricade(protruding).bounds());
    }

    @Test void movementCollidesWithTheUpperHalfEvenWhenTheEntityIsEntirelyInTheCellAbove() {
        for (boolean client : new boolean[]{false, true}) {
            var level = MemoryFixture.create(client);
            level.seed(POS, block.defaultBlockState());
            var entity = new ItemEntity(EntityType.ITEM, level);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Vec3 axis = Vec3.atLowerCornerOf(direction.getNormal());
                Vec3 start = Vec3.atBottomCenterOf(POS).add(axis.scale(1.25));
                Vec3 motion = axis.scale(-2.5);
                entity.setPos(start.x, POS.getY() + 1.125, start.z);
                Vec3 actual = Entity.collideBoundingBox(entity, motion, entity.getBoundingBox(), level, List.of());
                assertTrue(actual.lengthSqr() < motion.lengthSqr(), direction.toString());
                entity.setPos(start.x, POS.getY() + 1.5001, start.z);
                assertEquals(motion, Entity.collideBoundingBox(
                        entity, motion, entity.getBoundingBox(), level, List.of()));
            }
            entity.setPos(POS.getX() + 0.5, POS.getY() + 2.5, POS.getZ() + 0.5);
            assertEquals(-1, Entity.collideBoundingBox(entity, new Vec3(0, -3, 0),
                    entity.getBoundingBox(), level, List.of()).y, 1.0e-12);
        }
    }

    @Test void clickingTheTopPlacesAnOrdinaryBlockImmediatelyAboveInsteadOfSkippingACell() {
        for (boolean client : new boolean[]{false, true}) {
            var level = MemoryFixture.create(client);
            level.seed(POS, block.defaultBlockState());
            Vec3 start = Vec3.atBottomCenterOf(POS).add(0, 3, 0);
            Vec3 end = Vec3.atBottomCenterOf(POS);
            var hit = level.clip(new ClipContext(start, end,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null));
            assertEquals(HitResult.Type.BLOCK, hit.getType());
            assertEquals(POS, hit.getBlockPos());
            assertEquals(Direction.UP, hit.getDirection());
            assertEquals(POS.getY() + 1, hit.getLocation().y, 1.0e-12);
            var context = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                    new ItemStack(Items.STONE), hit);
            assertEquals(POS.above(), context.getClickedPos());
            assertTrue(((BlockItem) Items.STONE).place(context).consumesAction());
            assertTrue(level.getBlockState(POS.above()).is(Blocks.STONE));
            assertTrue(level.getBlockState(POS.above(2)).isAir());
        }
    }

    @Test void stackedBarricadesStillSelectTheTopCellAndPlaceDirectlyAboveIt() {
        for (boolean client : new boolean[]{false, true}) {
            var level = MemoryFixture.create(client);
            level.seed(POS, block.defaultBlockState());
            level.seed(POS.above(), block.defaultBlockState());
            var hit = level.clip(new ClipContext(Vec3.atBottomCenterOf(POS).add(0, 4, 0),
                    Vec3.atBottomCenterOf(POS), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, null));
            assertEquals(POS.above(), hit.getBlockPos());
            assertEquals(POS.getY() + 2, hit.getLocation().y, 1.0e-12);
            var context = new BlockPlaceContext(level, null, InteractionHand.MAIN_HAND,
                    new ItemStack(Items.STONE), hit);
            assertEquals(POS.above(2), context.getClickedPos());
            assertTrue(context.canPlace());
        }
    }
}
