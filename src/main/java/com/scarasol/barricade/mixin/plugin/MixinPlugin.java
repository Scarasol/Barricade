package com.scarasol.barricade.mixin.plugin;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraftforge.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class MixinPlugin implements IMixinConfigPlugin {
    private final Predicate<String> installed;
    private String prefix;

    public MixinPlugin() {
        this(modId -> LoadingModList.get().getModFileById(modId) != null);
    }

    MixinPlugin(Predicate<String> installed) {
        this.installed = installed;
    }

    @Override public void onLoad(String mixinPackage) {
        prefix = mixinPackage + ".";
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        int separator = mixinClassName.indexOf('.', prefix.length());
        return separator < 0 || installed.test(mixinClassName.substring(prefix.length(), separator));
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
