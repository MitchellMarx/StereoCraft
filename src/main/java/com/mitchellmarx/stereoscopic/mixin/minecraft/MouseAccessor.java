package com.mitchellmarx.stereoscopic.mixin.minecraft;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link Mouse}'s private cursor position fields for virtual-cursor
 * seeding on screen open. Must contain ONLY {@code @Accessor}-annotated
 * abstract methods — adding a default method would trip Mixin's "interface
 * mixin requires interface target" check at PREPARE since {@code Mouse} is
 * a class.
 */
@Mixin(MouseHandler.class)
public interface MouseAccessor {
    @Accessor("xpos") void stereoscopic$setX(double v);
    @Accessor("ypos") void stereoscopic$setY(double v);
}
