package dev.ship.citygen.mixin;

import dev.ship.citygen.CityCitizens;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 市民生成的挂载点。
 *
 * <p>为什么不挂在区块生成里：区块生成线程上还没有 {@code LevelChunk}，
 * 实体既没地方放也存不下来。{@code ServerChunkCache.tick} 是区块**已经进世界之后**
 * 每 tick 必过的地方，而且能从里面直接拿到 {@link ServerLevel}。
 *
 * <p>{@code require = 0}：这一钩子挂不上顶多是没有市民，不该让整个模组崩掉。
 */
@Mixin(ServerChunkCache.class)
public class ServerChunkCacheMixin {

    @Inject(method = "tick", at = @At("TAIL"), require = 0)
    private void citygen$spawnCitizens(BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        ServerChunkCache self = (ServerChunkCache) (Object) this;
        Level level = self.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            CityCitizens.tick(serverLevel);
        }
    }
}
