# policy-service

迷你保单系统的核心写服务。当前职责：
- 保单 CRUD（创建 / 查询详情 / 列表 / 更新 / 取消 / 删除）
- 详情查询（`GET /api/policies/{id}`）走 Redis 缓存，更新/取消时刷新缓存
- 表结构由 Liquibase 管理（`src/main/resources/db/changelog/`），启动时自动建表/迁移，不手写建表逻辑
- （P3新增）创建/更新/取消保单时，通过 `EventPublisher` 往 `policy-events` topic 发一条事件
  （见 `src/main/java/com/toysystem/policy/event/`）。这里特意把"发事件"单独封装成一个接口
  （`EventPublisher` / `KafkaEventPublisher`），P9阶段要换成Canal监听binlog触发时，只需要换掉
  "谁来调用publish"这一层，事件本身和下游消费者都不用动

事件格式契约见 `docs/kafka-event-schema.md`。

## 技术栈

Spring Boot 3.3 + MyBatis + Liquibase + MySQL 8 + Redis 7 + Kafka，Java 21。

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
| GET | `/api/policies/{id}` | 查询详情（Redis 缓存，5分钟TTL） |
| GET | `/api/policies` | 列表（按id倒序，未分页） |
| PUT | `/api/policies/{id}` | 更新保单基本信息 |
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
curl -s localhost:8081/api/policies/1

# 列表
curl -s localhost:8081/api/policies

# 更新
curl -s -X PUT localhost:8081/api/policies/1 \
  -H 'Content-Type: application/json' \
  -d '{"holderName":"山田太郎","productType":"TENGAN","premium":1500.00}'

# 取消
curl -s -X POST localhost:8081/api/policies/1/cancel
```
