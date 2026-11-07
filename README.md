# mc-city —— Minecraft 26.3 的确定性城市生成模组

一个 Fabric 模组：在**原生地形之上**叠加一个确定性地生成的城市——路网、地块、分区、建筑、地铁、桥、远古城市、市民、地图导航，全部由世界种子决定，任何区块以任何顺序生成都能得到完全一致的结果。

- 支持版本：Minecraft 26.3 / Fabric Loader 0.19.5+ / Java 25
- 不修改原版地形：城市是「贴」在原版世界上的一层规划，卸载模组即回到原版地形
- 无硬前置：不装 HowToGo 也能玩（地图导航是可选增强）

```
始于问道，继以寻道。 —— 城市因路而活。
```

---

## 一、快速开始

### 构建

```bash
# 需要 JDK 25。先准备一个辅助目录（tools），里面放一份 cp.txt：
#   cp.txt = 编译 classpath，一行、分号分隔，包含
#   fabric-loader / sponge-mixin / brigadier / Minecraft 反混淆 jar 等
CITYGEN_JDK="C:/path/to/jdk-25/bin" CITYGEN_TOOLS=/d/your/tools ./build.sh
```

构建脚本会把编译产物中转到 ASCII 路径（javac 对中文 classpath 不可靠），产物在 `build/citygen-0.1.0.jar`。

### 安装

1. `build/citygen-0.1.0.jar` 放进 `mods/`
2. 单人游戏直接开新世界即可

### 可选：地图导航

安装 [HowToGo（问道）](https://github.com/Dong-awa/howtogo) 与 [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 后，本模组会把生成的路网（高速/主干/次干三级折线）注册为 HowToGo 的附属图层——打开世界地图就能看到整座城市的道路，并用问道的导航选目的地。

---

## 二、架构

分两层：

```
core/  纯规划，零 Minecraft 依赖，可直接单测
  Field    宏观势场：城市中心度 / 人口 / 坡度 / 绿地 / 水体 / 路网弯曲
  Zoning   Voronoi 分区：CBD / 商业 / 高密住宅 / 低密住宅 / 工业 / 绿地 / 水体
  Roads    四级路网：高速(512) / 主干(128) / 次干(64) / 支路，含弯道、路口、标线、地铁线
  Parcels  地块切分与建筑生成（地标 / 塔楼 / 板楼 / 联排 / 盒子）
  CityPlan 把以上打包成 512×512 的「巨型区块」规划 + 逐区块切片

mod/   Minecraft 侧
  mixin    挂进 NoiseBasedChunkGenerator.buildTerrain（涂装）与 ServerChunkCache.tick（市民/远古城市/HUD）
  painter  逐列涂装：地形削填、路面、建筑、地铁、桥、路牌、箱子
  citizens 市民生成（区块变实体后补种）+ HUD 区域提示 + 村民取名
  bridge   HowToGo 地图数据源（软依赖，未装不加载）
```

**确定性是硬约束**：所有噪声查询只吃世界坐标，任何区块的生成顺序不影响结果。

---

## 三、特性

### 城市分布
全局抖动网格决定「哪里有城」：每 512 格一个单元，约 38% 留空当郊野；有城则位置、半径（185–340）、强度随机。实测相邻三块 CBD 占比 9.6% / 1.2% / 3.1%——不再每 512 格复制粘贴一座城。

### 路网与地铁
四级道路（间距 512/128/64/32–44）+ 低频弯曲（warp ±6 格，全局连续不裂缝）+ 路口深色沥青 + 斑马线。地铁三层埋深：高速线 y=40、主干线与环线 y=36，站厅 4 格高带柱网、轨道穿中间、换乘竖井用脚手架上下贯通、地面站亭。

### 遇山开路、遇水架桥
路面用 64 格粗网格拉平纵向剖面——山坡上路面高出地面时自动长出桥墩（每 8 格一根）变成高架；削深超过 4 格自动切成隧道；地块跨越水面时整块退让，水体保留。

### 建筑
退让式地块 + 五种形态（地标 20–30 层 / 塔楼 / 板楼 / 联排 / 盒子）。立面四种角色：实墙、凹窗、凹槽、转角柱；凸腰线每 3 层一道、飘窗凸出一格、住宅每 6 层内凹阳台。楼底统一标高，陡坡自动变成铺装平台（削深 ≤ 7 格）。

### 远古城市
主干线地铁隧道沿线每 384 格一座**原版远古城市**（city_center 结构模板，y=-8，随机旋转），原版战利品表与监守者机制原样生效。区块变实体后放置，自动去重。

### 市民
区块变实体后补种市民（每区块约 2 名，半径 6 区块去重），13 种原版职业，一半有名字（「黄霞 的书报亭」），玩家跨区时 HUD 弹出「🧭 HowToGo · 商业街」。

### 路牌
主路口立 HowToGo 导航牌（指向 CBD 的方向 + 剩余距离 + 街名，发光字），次路口挂街名牌（中山大道 / 梧桐街），全部由坐标哈希确定性派生。

---

## 四、已知限制

1. 建筑没有家具与村民交易表定制；箱子战利品是写死的物品池（不依赖数据包）
2. 每 512×512 块规划约 0.5 s（后台线程），缓存 4 块约 30 MB
3. 墙 1 格厚（壁柱处 2 格），贴脸看是薄片
4. 洞穴地图、区域领地等 Xaero 高级功能与本项目无关
5. HowToGo 集成为附属图层：**可显示、不参与问道自身的寻路**（官方 API 的边界，见其文档）
6. 需要 JDK 25 编译；javac 对非 ASCII classpath 不可靠，构建脚本会把产物中转到 ASCII 路径

---

## 五、想改造型改哪里

| 想要的效果 | 改哪里 |
|---|---|
| 城市疏密 / 有没有城 | `Field.center()` 里 `((h >>> 40) & 0xFF) < 97`（38% 留空） |
| 城市大小 / 强弱 | 同上 `radius` / `strength` |
| 分区碎度 | `Zoning.SITE_CELL`（现 52） |
| 高速/主干/次干间距与路宽 | `Roads` 的 `*_SPACING` 与 `halfWidthOf()` |
| 街道弯曲程度 | `Field.warp()` 振幅（现 6.0） |
| 斑马线宽度 | `Roads.build()` 里 `clearZ < 3` |
| 地铁埋深 / 站厅净空 | `METRO_Y`（40）/ `METRO_HALL_CLEAR`（4） |
| 地铁站 / 换乘站间距 | `Roads.build()` 里 `perp < 14` / 主干线 `96` |
| 建筑层数 | `Parcels.floorsFor()` 的 `lo`/`hi` |
| 壁柱密度 / 阳台层距 | `building()` 里 `% 7` / `fl % 6 == 5` |
| 箱子密度 / 物品池 | `building()` 里 `% 121` / `loot()` |
| 远古城市频率 | `CityPlan.isAncientCitySpot()` 里 `384` / `24` |
| 市民密度 | `CityCitizens.PER_CHUNK` 与 `RADIUS` |
| HowToGo 图层内容 | `Roads.buildLines()` 的 `specs` |

---

## 六、目录结构

```
mc-city/
├─ build.sh              一键构建（无 Gradle；classpath 由 tools/cp.txt 提供）
├─ core/src/citygen/     纯规划核心（Field / Zoning / Roads / Parcels / CityPlan / Main）
├─ core/src/citygen/Main.java   独立测试入口：生成规划、渲染 PNG 预览、一致性自检
├─ mod/src/main/java/dev/ship/citygen/   MC 侧（painter / citizens / placer / bridge / mixin）
├─ mod/stub-java/        HowToGo API 编译桩（只参与编译，不进 jar）
├─ mod/src/main/resources/fabric.mod.json
└─ README.md
```

## 许可

MIT，见 [LICENSE](LICENSE)。
