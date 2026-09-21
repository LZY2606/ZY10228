# 粒级混合谱（Grain-size Mixing Spectrum）

把**筛分质量分布**与**激光粒度仪体积分布**投影到同一条对数粒径网格，在显式声明的
转换假设下，反演多个源端元的混合比例。针对端元近线性相关的情形，系统不只给一个
“小数位稳定”的比例，而是并列保留多个稀疏候选、候选簇与可识别性诊断。

- 技术栈：Kotlin + Ktor (Netty) + SQLite (xerial JDBC) + 原生 Web UI
- 本地服务，无外部服务依赖；数据库为单个文件 `data/grainmix.db`
- 每次求解**冻结**网格、目标口径、密度模型与折射率假设

---

## 构建与演示

```bash
# 安装 / 打包
mvn -q -DskipTests package

# 演示（先跑自动化测试，再启动服务）
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5568'
```

浏览器访问 <http://127.0.0.1:5568>，页面标题为 **粒级混合谱**。

可选参数：`--port 5568 --db data/grainmix.db`。

---

## 页面能看到什么

1. **原始箱**：每台仪器的 `[lo, hi)` 与箱值，最后一箱的右端点单独标注，避免质量重复。
2. **转换后分布**：投影到共同对数网格的曲线，以及逐仪器的 `below / above / 网格内 / 最后右端点 / 守恒`。
3. **观测 vs 拟合、残差**：多台仪器按支持区纵向堆叠后的加权拟合。
4. **端元比例**：多个稀疏候选（SSE / RMSE / BIC）、候选簇、最佳解。
5. **可识别性**：端元两两相关、近相关对（|r|≥0.90）、条件数提示与文字诊断。
6. **运行记录**：可导出 JSON；可**清空数据库后重新导入并重算核对**；可一键重置为固定 fixture。

---

## 数据口径与显式转换假设

### 1. 箱与边界

- 所有箱一律**左闭右开**：`[edges[i], edges[i+1])`。
- 共同网格为等 `log10(d)` 间距；最后一箱的右端点 `edges[n]` **单独声明**
  （fixture 中为 1000 µm）。相邻箱只在端点相接（零测度），不产生重复计数。

### 2. 投影到共同对数网格（守恒、不归一化）

- 假设每个原始箱内部在 `log10(d)` 上**均匀分布**，按“对数长度占比”切分箱内总量。
- 每个原始箱切成三段（以同一整箱宽归一化）：
  - `below`：`[xl, g0)`，低于网格左界；
  - 网格内段 `[cl, ch)`：再按各网格箱对数长度细分；
  - `above`：`[gn, xh)`，高于网格右界。
- 落在网格外的量计入 `below / above`，**绝不归一化吞掉失量**，精确满足

  `totalRaw = sum(onGrid) + below + above`（误差仅浮点舍入）。

- 筛分（63~2000 µm）粗端超出 1000 µm → 产生 `above`（fixture 约 10.5%）；
  激光（0.4~500 µm，含黏粒肩）细端低于 1 µm → 产生 `below`（fixture 约 1.9%）。
  两台仪器的网格与共同网格、彼此之间都**不完全重叠**。

### 3. 数量 / 面积 / 体积 / 质量口径

- 关系：`A ∝ N·d²`，`V ∝ N·d³`，`M = ρ·V`。
- 逐箱以网格**对数几何中心粒径**代表该箱做口径换算（固定网格上的标准一阶假设，
  避免箱内形状模型歧义）。转换只乘正权重，不产生失量、不改变非负性。
- 密度模型（每次求解二选一并冻结）：
  - `uniform-density`：所有端元/样品同一密度（默认 2.65 g/cm³，石英），`V→M` 仅整体缩放；
  - `endmember-density`：各端元用自报密度，端元间形状出现差异。
- **折射率**（实部/虚部）是激光粒度仪由光散射反演体积分布时的固有假设；系统把它作为
  每次求解的冻结参数记录与回显，保证“同一次求解使用同一 RI 假设”，不在中途偷换。

### 4. 反演：加权非负最小二乘 + 多稀疏候选

- 多台仪器按各自**支持掩码（support mask）**纵向堆叠：仪器没有原始箱覆盖的网格箱
  不参与该仪器的拟合（避免把“无数据”误当 0 惩罚）；权重为 `1/σ`，默认 5% 相对误差
  加一个小的绝对地板。
- 求解器为标准 Lawson–Hanson 非负最小二乘（Cholesky 解自由集法方程）。
- 除全列解外，额外枚举“剔除任一端元”与“成对端元”的子集解，得到一族**稀疏候选**，
  去重后按 SSE/BIC 排序；候选按系数向量余弦相似度 ≥ 0.985 归为候选簇。
- 可识别性诊断在共同网格的端元原型上计算两两相关与条件数近似。当存在
  `|r| ≥ 0.90` 的近相关对时，页面明确提示“比例不可唯一识别”，并并列展示
  “主要用 A”与“主要用 B”等 SSE 接近但比例不同的候选簇——请并列判读，不要只取一个数。

fixture 中端元 A（µ=40µm）与 B（µ=55µm）刻意近线性相关（r≈0.925），真实混合为
质量 `0.55·A + 0.62·C`。最佳稀疏候选恢复 A≈0.51、C≈0.59，同时保留以 B 替代 A 的
候选簇。

---

## 运行记录导出 / 清空 / 重新导入复核

- `GET /api/export`：导出一个自描述 JSON（网格、端元、样品、观测、全部运行记录与假设）。
- `POST /api/reimport`：接收该 JSON，**先清空数据库**，再重新写入并对其中每条运行记录
  **用相同假设重新求解**，逐条核对 SSE（相对容差 1e-6）与总量守恒，返回 `allVerified`。
- 页面右上“清空后导入复核”即调用该流程；“重置为 fixture”清空并写回固定数据。

这保证：清空数据库 → 重新导入 → 复核结果一致，可重复重放。

---

## HTTP API 摘要

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（返回标题） |
| GET/POST | `/api/grids` | 列出 / 新建对数网格 |
| GET/POST/DELETE | `/api/endmembers[/id]` | 端元库（含分布与逐箱协方差） |
| GET/POST | `/api/samples`、`/api/observations` | 样品与仪器原始箱 |
| POST | `/api/project` | 只预览投影与失量（不求解、不落库） |
| POST | `/api/solve` | 在固定假设下反演并存运行记录 |
| GET | `/api/runs`、`/api/runs/{id}` | 运行记录列表 / 详情 |
| GET | `/api/export` | 导出全量 JSON |
| POST | `/api/reimport` | 清空后导入并重算核对 |
| POST | `/api/reset-fixtures` | 重置为固定 fixture |
| GET | `/api/counts` | 各表行数 |

`solve` 请求体关键字段：`sampleId, gridName, targetBasis
(MASS/VOLUME/AREA/NUMBER), densityModel, uniformDensity, riReal, riImag,
endmemberNames[]`。

---

## 自动化测试

`mvn -q test`（共 15 个，JUnit5）：

- `ProjectorTest`：左闭右开、最后右端点、完全/部分越界失量、等对数切分、双仪器失量方向、
  口径转换往返与**总量守恒**；
- `SolverTest`：近相关对识别、多稀疏候选与多簇保留、真值恢复与残差无偏；
- `ImportExportTest`：空库播种 fixture、service 守恒、导出→清空→重导入 SSE 与守恒复核、
  重复重入确定性；
- `HttpTest`：页面标题、health/grids/endmembers/solve 端到端。

## 目录

```
src/main/kotlin/app/
  domain/   Types / Projector / Convert / Linalg / Solver / Service / ImportExport
  db/       Database(SQLite) / Fixtures
  web/      WebServer(Ktor 路由 + JSON DTO)
  Main.kt   入口
src/main/resources/static/  index.html, app.js
src/test/kotlin/app/        自动化测试
data/grainmix.db            SQLite 数据文件（首启自动生成并播种 fixture）
```
