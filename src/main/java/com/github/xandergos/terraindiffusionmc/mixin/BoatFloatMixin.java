package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boats float back up to the surface whenever they are under water (Calvin, Oct 28). Vanilla lets a boat under still
 * water sink (its small buoyancy loses to gravity) and one under flowing water hang there, so a boat pushed under by a
 * waterfall, a current or a sinking bank stayed on the bottom and threw its riders off after three seconds. After
 * vanilla's own float step, a boat in either under-water state now rises, gaining {@link #RISE_ACCEL} blocks per tick
 * up to {@link #RISE_MAX}; once its top clears the water it is back in the IN_WATER state and vanilla's buoyancy settles
 * it on the surface. Not in falling water (a waterfall over the boat), which would lift it up the fall. Runs on both sides like the rest of the boat's physics. Config {@code gameplay.floating_boats}.
 */
@Mixin(AbstractBoat.class)
public abstract class BoatFloatMixin {
    private static final double RISE_ACCEL = 0.05, RISE_MAX = 0.3;

    @Shadow
    private AbstractBoat.Status status;

    @Inject(method = "floatBoat", at = @At("TAIL"))
    private void terra$floatUp(CallbackInfo ci) {
        if (status != AbstractBoat.Status.UNDER_WATER && status != AbstractBoat.Status.UNDER_FLOWING_WATER) return;
        if (!TerrainDiffusionConfig.floatingBoats()) return;
        AbstractBoat self = (AbstractBoat) (Object) this;
        // Not up a waterfall: falling water over the boat is a column, not a body of water with a surface to reach,
        // and rising there would carry the boat up the fall like a lift.
        net.minecraft.core.BlockPos top = net.minecraft.core.BlockPos.containing(self.getX(), self.getBoundingBox().maxY + 0.01, self.getZ());
        net.minecraft.world.level.material.FluidState above = self.level().getFluidState(top);
        if (!above.isEmpty() && !above.isSource() && above.hasProperty(net.minecraft.world.level.material.FlowingFluid.FALLING)
                && above.getValue(net.minecraft.world.level.material.FlowingFluid.FALLING)) return;
        Vec3 v = self.getDeltaMovement();
        self.setDeltaMovement(v.x, Math.min(RISE_MAX, Math.max(v.y, 0.0) + RISE_ACCEL), v.z);
    }
}
