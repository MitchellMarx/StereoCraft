package com.mitchellmarx.stereoscopic.mixin.minecraft;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@link MouseHandler}'s private cursor position fields for
 * virtual-cursor seeding on screen open. Must contain ONLY
 * {@code @Accessor}-annotated abstract methods — adding a default method
 * would trip Mixin's "interface mixin requires interface target" check at
 * PREPARE since {@code MouseHandler} is a class.
 *
 * <p>Field names (Mojang): {@code xpos}, {@code ypos}. (Yarn: {@code x},
 * {@code y}.) Verify against the resolved 1.21.1 MouseHandler if accessor
 * binding fails.
 */
@Mixin(MouseHandler.class)
public interface MouseAccessor {
    @Accessor("xpos") void stereoscopic$setX(double v);
    @Accessor("ypos") void stereoscopic$setY(double v);
}
