package dev.ship.citygen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 城市维度用的区块生成器。
 *
 * <p>关键约束：26.3 里 {@link NoiseBasedChunkGenerator} 是 {@code final}，不能继承；
 * 原来的 {@code populateNoise} 也不存在了，地形生成统一走
 * {@link ChunkGenerator#buildTerrain}。所以这里改成<b>组合</b>：
 * 内部持有一个原版 {@code NoiseBasedChunkGenerator} 负责真正的地形，
 * 本类只把结果拿过来刷一层城市地表，其余方法全部转发。
 *
 * <p>为什么不自己实现地形：原版那套密度函数 / 含水层 / 洞穴是几万行调好的代码，
 * 抄一遍只会更差。城市要的是「地形之上再加一层规划」，所以只做后置涂装。
 *
 * <p>本类的所有方法签名均已用 {@code javap} 对照 26.3 真实 jar 校核。
 */
public class CityChunkGenerator extends ChunkGenerator {

    public static final MapCodec<CityChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                    NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.settings)
            ).apply(instance, instance.stable(CityChunkGenerator::new))
    );

    private final NoiseBasedChunkGenerator delegate;
    private final Holder<NoiseGeneratorSettings> settings;

    public CityChunkGenerator(BiomeSource biomeSource, Holder<NoiseGeneratorSettings> settings) {
        super(biomeSource);
        this.settings = settings;
        this.delegate = new NoiseBasedChunkGenerator(biomeSource, settings);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    /**
     * 地形由原版生成，完成之后立刻涂装。
     * 用 {@code thenApply} 而不是 {@code thenApplyAsync}，保证不改动原版的线程模型：
     * 一个区块的涂装只碰它自己的方块，不访问邻区块。
     */
    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender,
                                                       RandomState randomState,
                                                       StructureManager structureManager,
                                                       BiomeManager biomeManager,
                                                       WorldGenRegion region,
                                                       Set<Holder<Biome>> biomes) {
        // WorldGenRegion.getSeed() 在区块生成线程上直接可用，省掉了全局种子钩子
        CityPlanManager manager = CityPlanManager.forSeed(region.getSeed());
        return delegate.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, region, biomes)
                .thenApply(painted -> {
                    CitySurfacePainter.paint(painted, manager, delegate, randomState);
                    return painted;
                });
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        delegate.spawnOriginalMobs(region);
    }

    @Override
    public int getGenDepth() {
        return delegate.getGenDepth();
    }

    @Override
    public int getSeaLevel() {
        return delegate.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return delegate.getMinY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor heightAccessor, RandomState randomState) {
        return delegate.getBaseHeight(x, z, type, heightAccessor, randomState);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        return delegate.getBaseColumn(x, z, heightAccessor, randomState);
    }

    @Override
    public void addDebugScreenInfo(List<String> lines, RandomState randomState, BlockPos pos, SamplerContext context) {
        delegate.addDebugScreenInfo(lines, randomState, pos, context);
    }
}
