package com.mia.aperture.mixin;

import com.mia.aperture.state.AbyssMapState;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.hierachical.NodeManager;
import me.cortex.voxy.common.world.WorldEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = NodeManager.class, remap = false)
public class NodeManagerMixin {
    @ModifyVariable(method = "processGeometryResult", at = @At("HEAD"), argsOnly = true)
    private BuiltSection mia$cullSection(BuiltSection sectionResult) {
        if (sectionResult == null) return null;
        if (AbyssMapState.scrollActive) {
            long pos = sectionResult.position;
            int lvl = WorldEngine.getLevel(pos);
            int y = WorldEngine.getY(pos);
            if (!AbyssMapState.isSectionVisible(lvl, y)) {
                // If the section falls outside the vertical aperture window, return an empty
                // BuiltSection but keep the child octree nodes existence mask so the octree hierarchy
                // remains internally coherent.
                return BuiltSection.emptyWithChildren(pos, sectionResult.childExistence);
            }
        }
        return sectionResult;
    }
}
