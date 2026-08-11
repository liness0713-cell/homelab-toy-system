# policy-service

迷你保单系统的核心写服务。当前职责：
- 保单 CRUD（创建 / 查询详情 / 列表 / 更新 / 取消 / 删除）
- 详情查询（`GET /api/policies/{id}`）走 Redis 缓存，更新/取消时刷新缓存
- 表结构由 Liquibase 管理（`src/main/resources/db/changelog/`），启动时自动建表/迁移，不手写建表逻辑
- （P4.5新增）`policy` 表按 `holder_name` 哈希分了4张物理表（`policy_0`~`policy_3`），
  见下方"分库分表（P4.5）"一节
- （P3新增）创建/更新/取消保单时，通过 `EventPublisher` 往 `policy-events` topic 发一条事件
  （见 `src/main/java/com/toysystem/policy/event/`）。这里特意把"发事件"单独封装成一个接口
  （`EventPublisher` / `KafkaEventPublisher`），P9阶段要换成Canal监听binlog触发时，只需要换掉
  "谁来调用publish"这一层，事件本身和下游消费者都不用动

事件格式契约见 `docs/kafka-event-schema.md`。消息体的Java类（`PolicyEvent`）定义在共享模块
`../event-contracts/` 里，`notification-service`等消费者引用的是同一个类，不是各自维护一份
（P3踩坑后的修订，见该模块的README）。`PolicyEventFactory`（本服务内部）负责把内部实体
`Policy`转换成这个共享的`PolicyEvent`——这层转换必须留在这里，`event-contracts`不能反过来
依赖本服务的实体类。

## 技术栈

Spring Boot 3.3 + MyBatis + ShardingSphere-JDBC 5.5.3 + Liquibase + MySQL 8 + Redis 7 +
Kafka，Java 21。这是一个Maven多模块项目的子模块（父pom在仓库根目录），第一次构建前要先在
根目录跑一次 `mvn install`（把 `event-contracts` 装进本地仓库），见根目录 `README.md`。

## 分库分表（P4.5）

在正式上k3s（P5）之前加的一个练习：`policy` 表按 `holder_name` 的哈希分成4张物理表
（`policy_0`~`policy_3`），只做分表，不做分库（都在同一个MySQL实例/schema里）——分库留作
以后单独的练习。完整设计动机、取舍见 `docs/homelab-toy-system-plan.md` 的"分库分表练习"一节；
ShardingSphere-JDBC集成过程中踩的坑（依赖版本、SPI报错、Liquibase冲突、Spring循环依赖等）
记录在 `docs/policy-service-sharding-troubleshooting.md`。

关键设计点：

- **分片键**：`holder_name`（不是`id`/`policyNo`）。生产系统一般按客户ID分片，但这个玩具
  系统没有独立的客户服务，用姓名字符串当"客户身份"的替代品，代价是：同名的人会分到同一
  个分片；同一个人姓名拼写不一致会分散到不同分片。仅作练习，不代表生产可用方案。
- **id生成**：不用ShardingSphere自带的雪花算法key生成器（它拿不到`holder_name`，没法跟
  分片决策对齐），改成应用层自己算——`SnowflakeIdGenerator`把分片号编码进id的低位（低到高：
  12位序列号、2位分片号、41位时间戳），分片号来自`ShardKeyUtil.shardIndexFor(holderName)`，
  跟ShardingSphere自己算的路由用的是同一个哈希公式，两边必然一致。
- **`holder_name`创建后不可变**：它是分片键，UPDATE改它意味着这一行要"搬"到另一张物理表，
  ShardingSphere直接拒绝这种UPDATE（`Can not update sharding value for table 'policy'`）。
  所以 `PUT /api/policies/{id}` 不再接受 `holderName` 字段。
- **`shardAware`对比开关**：`GET /api/policies/{id}` 默认（`shardAware=false`）查
  ShardingSphere的逻辑表`policy`，会广播到全部4张物理表再合并结果；`shardAware=true`从id
  直接解码出分片号，用一个完全独立、不经过ShardingSphere的原生JDBC连接
  （`ShardTableReader`/`rawMySqlDataSource`）直接查那一张物理表，单分片命中、不广播。两条
  路径故意都不接口，方便配合`shardingsphere-config.yaml`里的`sql-show: true`日志对比实际
  发出去的SQL差别。之所以不能让ShardingSphere包装的连接直接查`policy_N`——实测
  ShardingSphere会把`policy_0`~`policy_3`当成`policy`逻辑表的`actualDataNodes`，不允许绕开
  逻辑表名直接寻址，哪怕加了`!SINGLE`的`"*.*"`通配也不行，会报`TableNotFoundException`。
  响应头`X-Policy-Shard-Route`会标出这次走的是`broadcast:policy_0..policy_3`还是
  `single:policy_N`。

## 依赖的中间件

- MySQL（`toy_policy_db` 库）
- Redis
- Kafka（`policy-events` topic，3分区）

本地用 `infra/docker-compose.dev.yml` 拉起：

```bash
docker compose -f infra/docker-compose.dev.yml up -d mysql redis kafka
```

## 本地单独运行

```bash
source ~/.sdkman/bin/sdkman-init.sh   # 如果 java/mvn 不在 PATH 里
cd policy-service
mvn spring-boot:run
```

默认配置（可用环境变量覆盖，见 `application.yml`）：

| 环境变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8081` |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/toy_policy_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `SPRING_DATASOURCE_USERNAME` | `toy_app` |
| `SPRING_DATASOURCE_PASSWORD` | `toy_app_pw` |
| `SPRING_REDIS_HOST` | `localhost` |
| `SPRING_REDIS_PORT` | `6379` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

## 暴露的端口 / API

服务端口：`8081`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/policies` | 创建保单，状态默认为 `DRAFT` |
| GET | `/api/policies/{id}?shardAware=false\|true` | 查询详情。`shardAware=false`（默认）经ShardingSphere广播查询，走Redis缓存；`shardAware=true`从id解码分片号直连物理表，不走缓存，见上方"分库分表"一节 |
| GET | `/api/policies` | 列表（按id倒序，未分页） |
| PUT | `/api/policies/{id}` | 更新保单基本信息（`productType`/`premium`，不含`holderName`——分片键创建后不可变） |
| POST | `/api/policies/{id}/cancel` | 将状态置为 `CANCELLED` |
| DELETE | `/api/policies/{id}` | 删除保单 |
| GET | `/actuator/health` | 健康检查（为P6接入Prometheus预留） |

## curl 示例

```bash
# 创建
curl -s -X POST localhost:8081/api/policies \
  -H 'Content-Type: application/json' \
  -d '{"holderName":"山田太郎","productType":"TENGAN","premium":1200.50}'

# 查询详情（第二次请求走缓存，可用 redis-cli KEYS 'policy-detail::*' 观察）
curl -s localhost:8081/api/policies/1349058480844800

# 查询详情，绕开广播，直连解码出来的物理分片（对比X-Policy-Shard-Route响应头）
curl -sD - localhost:8081/api/policies/1349058480844800?shardAware=true

# 列表
curl -s localhost:8081/api/policies

# 更新（不含holderName——分片键创建后不可变）
curl -s -X PUT localhost:8081/api/policies/1349058480844800 \
  -H 'Content-Type: application/json' \
  -d '{"productType":"TENGAN","premium":1500.00}'

# 取消
curl -s -X POST localhost:8081/api/policies/1349058480844800/cancel
```
