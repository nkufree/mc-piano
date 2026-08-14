package io.github.alab.mcpiano.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the display entity's private transform setter for client-only note scaling. */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation")
    void mcpiano$setTransformation(Transformation transformation);

    @Invoker("setBrightnessOverride")
    void mcpiano$setBrightnessOverride(Brightness brightness);
}
