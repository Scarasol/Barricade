package com.scarasol.barricade.mixin.plugin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinPlugin implements IMixinConfigPlugin {
    private boolean betterCombatLoaded;
    private boolean taczLoaded;
    private boolean sbmLoaded;

    @Override public void onLoad(String mixinPackage) {
        betterCombatLoaded = present("net.bettercombat.BetterCombat");
        taczLoaded = present("com.tacz.guns.GunMod");
        sbmLoaded = present("com.github.mcmodderanchor.simplebedrockmodel.SimpleBedrockModel");
    }

    private boolean present(String name) {
        try {
            Class.forName(name, false, getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.scarasol.barricade.mixin.tacz.")) return taczLoaded;
        if (mixinClassName.startsWith("com.scarasol.barricade.mixin.sbm.")) return sbmLoaded;
        return !mixinClassName.startsWith("com.scarasol.barricade.mixin.bettercombat.") || betterCombatLoaded;
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
