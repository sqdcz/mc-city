package dev.ship.citygen.mixin;

import dev.ship.citygen.CityPlanManager;
import dev.ship.citygen.CitySurfacePainter;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 城市地表涂装的实际挂载点。
 *
 * <p>为什么不用「自带区块生成器 + 世界预设」这条更干净的路：
 * Fabric 下模组自带的 {@code data/} 目录要经 Fabric API 的资源加载模块才会被注册成数据包，
 * 只装 loader 时 {@code datapack list} 只认 vanilla 一个包，覆盖
 * {@code worldgen/world_preset/normal.json} 永远不会生效（实测主世界仍是 {@code minecraft:noise}）。
 *
 * <p>所以改成挂在原版噪声生成器的 {@code buildTerrain} 之后：原版生成器照常跑，
 * 出来之后把地表那层换成路面/铺装。好处是不依赖任何数据包、不依赖 Fabric API，
 * 而且**已有的存档走到没探索过的区块也会自动变成城市**，不需要重开世界。
 *
 * <p>只对主世界那三套噪声设置生效（默认 / 大生物群系 / 放大化），下界和末地原样不动。
 *
 * <p>26.3 签名已用 javap 对真实 jar 校核：
 * {@code NoiseBasedChunkGenerator.buildTerrain(ChunkAccess, Blender, RandomState, StructureManager,
 * BiomeManager, WorldGenRegion, Set<Holder<Biome>>)} 返回 {@code CompletableFuture<ChunkAccess>}；
 * {@code generatorSettings()} 返回 {@code Holder<NoiseGeneratorSettings>}；
 * {@code Holder.is(ResourceKey)} 与 {@code WorldGenRegion.getSeed()} 均可用。
 */
@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {

    // cancellable 必须显式打开：RETURN 注入点默认不允许改写返回值，
    // 否则 setReturnValue 会抛 "The call buildTerrain is not cancellable"
    @Inject(method = "buildTerrain", at = @At("RETURN"), cancellable = true)
    private void citygen$paintCitySurface(
            ChunkAccess chunk,
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            BiomeManager biomeManager,
            WorldGenRegion region,
            Set<Holder<Biome>> biomes,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {

        NoiseBasedChunkGenerator self = (NoiseBasedChunkGenerator) (Object) this;
        Holder<NoiseGeneratorSettings> settings = self.generatorSettings();
        noteCall(settings);
        if (!isOverworldLike(settings)) {
            return;
        }

        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            return;
        }

        CityPlanManager manager = CityPlanManager.forSeed(region.getSeed());
        // 必须把链式 future 塞回去：直接 thenApply 丢掉返回值的话，
        // 调用方等的是原 future，我们的涂装还没落地区块就可能已经被读走了
        cir.setReturnValue(future.thenApply(painted -> {
            try {
                // 传 generator 和 randomState 进去：涂装要用 getBaseHeight 采样平滑地形标高
                // 来实现挖山与隧道，而 getBaseHeight 是纯函数，只依赖这两样东西
                CitySurfacePainter.paint(painted, manager, self, randomState);
            } catch (RuntimeException e) {
                // 涂装抛异常会让整块生成失败；宁可这块是原版地形，也不能让存档坏掉
                System.out.println("[CityGen] 涂装失败，本块保持原版地形: " + e);
            }
            return painted;
        }));
    }

    private static boolean isOverworldLike(Holder<NoiseGeneratorSettings> settings) {
        return settings.is(NoiseGeneratorSettings.OVERWORLD)
                || settings.is(NoiseGeneratorSettings.LARGE_BIOMES)
                || settings.is(NoiseGeneratorSettings.AMPLIFIED);
    }

    /** 采样日志：第一次调用把噪声设置名打出来，便于确认守卫条件是否正确。 */
    private static final java.util.concurrent.atomic.AtomicLong CALLS =
            new java.util.concurrent.atomic.AtomicLong();

    private static void noteCall(Holder<NoiseGeneratorSettings> settings) {
        if (CALLS.incrementAndGet() == 1) {
            System.out.println("[CityGen] buildTerrain 钩子已挂上, settings="
                    + settings.unwrapKey().map(k -> k.identifier().toString()).orElse("<inline>"));
        }
    }
}
