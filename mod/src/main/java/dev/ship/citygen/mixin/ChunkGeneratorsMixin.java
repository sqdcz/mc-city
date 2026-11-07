package dev.ship.citygen.mixin;

import com.mojang.serialization.MapCodec;
import dev.ship.citygen.CityChunkGenerator;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGenerators;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 在 vanilla 注册完内置生成器之后、注册表冻结之前，追加 citygen:city。
 *
 * <p>为什么只能这么做——26.3 的两个时间窗都堵死了：
 * <ul>
 *   <li>{@code ModInitializer.onInitialize}：注册表已冻结，抛
 *       {@code IllegalStateException: Registry is already frozen}，
 *       且 26.3 已移除 {@code MappedRegistry.unfreeze()}，没有退路；</li>
 *   <li>{@code PreLaunchEntrypoint.onPreLaunch}：太早，{@code Bootstrap} 还没跑，
 *       访问 {@code BuiltInRegistries} 直接抛 {@code IllegalArgumentException: Not bootstrapped}。</li>
 * </ul>
 * 唯一合法的窗口就在 {@code ChunkGenerators.bootstrap} 的尾部——那时 bootstrap 已在进行中、
 * 注册表尚未冻结，而且是 MappedRegistry 而不是冻结后的只读视图。
 *
 * <p>26.3 的游戏 jar 不再混淆，因此本 mixin 不需要 refmap。
 */
@Mixin(ChunkGenerators.class)
public class ChunkGeneratorsMixin {

    // require = 0：这个注册只是给以后做独立维度留的口子，当前版本没人引用它。
    // 万一某个版本 bootstrap 签名变了，宁可少注册一个生成器，也不能让整包崩掉
    @Inject(method = "bootstrap", at = @At("TAIL"), require = 0)
    private static void citygen$registerCityGenerator(
            Registry<MapCodec<? extends ChunkGenerator>> registry,
            CallbackInfoReturnable<MapCodec<? extends ChunkGenerator>> cir) {
        Registry.register(registry,
                Identifier.fromNamespaceAndPath("citygen", "city"),
                (MapCodec<? extends ChunkGenerator>) CityChunkGenerator.CODEC);
    }
}
