package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.PilePiece;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;

/** Stable identity for a real block state stored in a logical barricade cell. */
public record EmbeddedBlock(BlockPos pos, long instance, long revision) {
    public EmbeddedBlock {
        pos = pos.immutable();
        if (instance <= 0 || revision <= 0) throw new IllegalArgumentException("Invalid embedded block identity");
    }

    public PileModel model(Level level) {
        var state = level.getBlockState(pos);
        if (state.isAir()) throw new IllegalStateException("Missing embedded block at " + pos);
        var entity = level.getBlockEntity(pos);
        CompoundTag data = entity == null ? new CompoundTag() : entity.saveWithoutMetadata();
        List<CollisionBox> boxes = new ArrayList<>();
        state.getCollisionShape(level, pos, CollisionContext.empty()).toAabbs().stream().limit(16).forEach(box ->
                boxes.add(new CollisionBox((float) box.minX, (float) box.minY, (float) box.minZ,
                        (float) box.maxX, (float) box.maxY, (float) box.maxZ)));
        if (boxes.isEmpty()) boxes.add(CollisionBox.CUBE);
        return new PileModel("embedded:" + instance,
                List.of(new ModelPart(BlockStateParser.serialize(state), 0, 0, 0, data)),
                1, 1, 1, 1, boxes, 0.5f, 0.5f, 0.5f);
    }

    public PilePiece transform(PileModel model) {
        long seed = pos.asLong() ^ instance * 0x9E3779B97F4A7C15L;
        seed = (seed ^ seed >>> 30) * 0xBF58476D1CE4E5B9L;
        seed = (seed ^ seed >>> 27) * 0x94D049BB133111EBL;
        Random random = new Random(seed ^ seed >>> 31);
        // Sample a full 3D orientation per instance, not a shared upright yaw with tiny tilts.
        float split = random.nextFloat();
        double first = random.nextDouble() * Math.PI * 2, second = random.nextDouble() * Math.PI * 2;
        float a = (float) Math.sqrt(1 - split), b = (float) Math.sqrt(split);
        Quaternionf rotation = new Quaternionf(a * (float) Math.sin(first), a * (float) Math.cos(first),
                b * (float) Math.sin(second), b * (float) Math.cos(second)).normalize();
        return PilePiece.create(model, 0.5f + (random.nextFloat() - 0.5f) * 0.08f,
                0.5f + (random.nextFloat() - 0.5f) * 0.08f,
                0.5f + (random.nextFloat() - 0.5f) * 0.08f, 0.98f, rotation);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.putLong("Instance", instance);
        tag.putLong("Revision", revision);
        return tag;
    }

    public static EmbeddedBlock load(CompoundTag tag) {
        return new EmbeddedBlock(BlockPos.of(tag.getLong("Pos")), tag.getLong("Instance"), tag.getLong("Revision"));
    }
}
