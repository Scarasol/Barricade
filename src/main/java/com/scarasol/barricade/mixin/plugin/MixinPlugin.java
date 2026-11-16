package com.scarasol.barricade.mixin.plugin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinPlugin implements IMixinConfigPlugin {
    private boolean betterCombatLoaded;

    @Override public void onLoad(String mixinPackage) {
        try {
            Class.forName("net.bettercombat.BetterCombat", false, getClass().getClassLoader());
            betterCombatLoaded = true;
        } catch (ClassNotFoundException ignored) {
            betterCombatLoaded = false;
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.startsWith("com.scarasol.barricade.mixin.bettercombat.") || betterCombatLoaded;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
