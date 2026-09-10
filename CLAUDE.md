# MongoDB Log Analyzer 项目规范

## 项目目标

本项目是供运维人员在个人电脑上离线使用的 MongoDB 日志分析工具。用户双击启动后，在本地 Web 页面上传 MongoDB 日志，程序流式解析全部日志、生成精确聚合结果，并仅保存按耗时排序的 Top 5000 条慢查询明细。

## 技术约束

- 后端使用 Java 17、Spring Boot 和 Maven。
- MongoDB Extended JSON 只使用 MongoDB BSON 库解析，不依赖 MongoDB 服务。
- 前端使用 Vue 3、Vite、Element Plus 和 ECharts，构建产物打包进 Spring Boot JAR。
- 运行时不得依赖 MongoDB、Node.js、Nacos、S3 或其他外部服务。
- 应用默认只监听 `127.0.0.1`。
- 持久化采用本地 JSON 和 JSONL 文件，不引入数据库。

## 目录约定

```text
mongodb-log/
├── CLAUDE.md
├── ROADMAP.md
├── README.md
├── pom.xml
├── docs/
│   └── superpowers/
│       ├── specs/
│       └── plans/
├── src/
│   ├── main/
│   │   ├── java/com/vaultsphere/mongodblog/
│   │   └── resources/
│   └── test/
│       ├── java/com/vaultsphere/mongodblog/
│       └── resources/fixtures/
├── web/
│   ├── src/
│   └── tests/
└── scripts/
```

后端按 `parser`、`analysis`、`task`、`storage`、`web` 分包。一个类只承担一个明确职责，不把数据模型、解析、统计和接口混在同一个类中。

## 解析与统计红线

- 所有日志必须流式处理，禁止一次性读入整个上传文件。
- 支持纯文本日志、结构化 JSON 日志以及 `.gz` 压缩日志。
- 每次上传创建一个任务；一个任务可包含多个日志文件。
- Top 5000 必须基于全部慢查询，通过固定容量最小堆精确选取。
- 耗时区间分布必须基于全部慢查询，不能基于 Top 5000 抽样。
- 固定耗时区间为：`<100ms`、`100ms-500ms`、`500ms-1s`、`1s-3s`、`3s-10s`、`10s-30s`、`30s-60s`、`>=60s`。
- 普通日志和未进入 Top 5000 的慢查询不得保存原文，只保留聚合结果。
- 解析异常必须计数并分类，不得静默吞掉。
- CPU 统计仅在日志实际包含 `cpuNanos` 时展示。
- 时间统一存储为 UTC Epoch Milliseconds，页面按本机时区展示。

## 开发纪律

- 功能与修复遵循测试驱动开发：先写失败测试，再写最小实现。
- OPS 和 OPS_web 仅作为逻辑与交互参考，不机械复制其错误处理和旧依赖。
- 解析器兼容性测试要逐字段对比 OPS 的 `MongoLogV2`；确认过的旧逻辑错误以独立正确性测试为准。
- 每个统计结果都必须有手工可复算的小数据集测试。
- 前端图表必须同时提供精确数据表或可核对数值。
- 不添加与 MongoDB 日志解析无关的功能。
- 每次完成并验证开发事项后同步更新 `ROADMAP.md`。

## 验证要求

- `mvn test` 必须通过。
- `npm test` 和 `npm run build` 必须通过。
- `mvn package` 必须生成可启动 JAR，并包含前端静态资源。
- 使用真实或等价样例验证纯文本、JSON、GZip、混合格式、异常行和 Top 5000 边界。
- 启动后必须通过浏览器完成上传、进度、结果页和明细查看的端到端验证。

