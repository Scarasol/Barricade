package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.sampling.MemoryLevel;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

class ProductionPackingArchitectureTest {
    @Test void productionHasOnlyPackingPayloadsAndNoGraphResourceOrJob() {
        assertTrue(FieldBatch.class.isSealed());
        assertEquals(Set.of(PackingBatch.class, PackingGroup.class),
                Set.of(FieldBatch.class.getPermittedSubclasses()));
        assertEquals(0, FieldBatch.class.getConstructors().length);
        assertThrows(NoSuchFieldException.class, () -> FieldBatch.class.getField("rounds"));
        assertNull(getClass().getResource("/assets/barricade/shaders/layout.compute"));
        assertNotNull(getClass().getResource("/assets/barricade/shaders/packing.compute"));
        assertNull(getClass().getResource("/com/scarasol/barricade/client/LayoutGpuJob.class"));
        assertNull(getClass().getResource("/com/scarasol/barricade/client/LayoutGpuJob$Buffers.class"));
        assertTrue(Arrays.stream(MemoryLevel.class.getDeclaredMethods()).noneMatch(method -> method.getName().equals("context")));
        assertTrue(Arrays.stream(MemoryLevel.class.getDeclaredFields()).noneMatch(field -> field.getName().equals("contextMode")));
        assertTrue(Arrays.stream(PackingInput.class.getRecordComponents()).noneMatch(field -> field.getName().equals("boundaries")));
    }

    @Test void productionWorkerOnlyConstructsPackingJobsAndLoadsPackingShader() throws Exception {
        Set<String> jobs = new HashSet<>();
        Set<String> resources = new HashSet<>();
        for (Class<?> type : List.of(GpuLayoutWorker.class, GpuLayouts.class)) {
            ClassNode node = new ClassNode();
            try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                new ClassReader(Objects.requireNonNull(input)).accept(node, 0);
            }
            for (MethodNode method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof TypeInsnNode allocation
                        && allocation.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && allocation.desc.endsWith("GpuJob")) jobs.add(allocation.desc);
                if (instruction instanceof LdcInsnNode literal && literal.cst instanceof String text
                        && text.startsWith("shaders/")) resources.add(text);
            }
        }
        assertEquals(Set.of("com/scarasol/barricade/client/PackingGpuJob"), jobs);
        assertEquals(Set.of("shaders/packing.compute"), resources);
    }
}
