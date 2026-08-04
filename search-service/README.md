# search-service

`policy-events` 的消费者B + 只读搜索服务。CQRS的读路径：写路径是`policy-service`写MySQL，
读路径是这个服务把同一份事件投影进Elasticsearch，对外提供搜索API，跟MySQL完全解耦。

## 职责

- 订阅 `policy-events` topic（消费者组 `search-service`，跟`notification-service`的消费者组
  完全独立——两边各自拿到同一份事件的完整拷贝，这是P3就验证过的"同一份事件，两种消费策略"）
- 收到事件（创建/更新/取消，不区分）后，把payload整份覆盖写入ES的`policies`索引，用`policyNo`
  当文档ID，天然幂等
- 暴露 `GET /api/search/policies?q=...` 只读搜索API，不接触MySQL

## 技术栈

Spring Boot 3.3 + Spring Data Elasticsearch + spring-kafka，Java 21。Maven多模块子模块，
依赖共享契约模块 `../event-contracts/`。

## 依赖

- Elasticsearch 已经在跑（默认 `http://localhost:9200`，
  `docker compose -f infra/docker-compose.dev.yml up -d elasticsearch`）
- Kafka 已经在跑，`policy-service` 已经在发事件
- 第一次构建前，先在**仓库根目录**跑一次 `mvn install`

## 本地单独运行

```bash
source ~/.sdkman/bin/sdkman-init.sh
cd search-service
mvn spring-boot:run
```

默认配置（可用环境变量覆盖，见 `application.yml`）：

| 环境变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8083` |
| `ELASTICSEARCH_URIS` | `http://localhost:9200` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

## 死信Topic容错

跟 `notification-service` 同一套模式（`ErrorHandlingDeserializer` + `DefaultErrorHandler` +
`DeadLetterPublishingRecoverer`，详见其README）。**唯一的差异**：死信topic用的是
`policy-events.search-service.DLT`，不是共用`policy-events.DLT`——因为现在`policy-events`
有两个完全独立的消费者组，如果两边失败的消息都堆到同一个默认死信topic里，没法一眼看出
是哪个消费者处理失败的。每个消费者组用自己名字命名的死信topic，排查问题时更清楚。

## 暴露的端口 / API

服务端口：`8083`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/search/policies?q=<关键词>` | 搜索保单：`q`匹配投保人姓名（模糊/纠错）、保单号、产品类型（精确）；不传`q`返回全部 |
| GET | `/actuator/health` | 健康检查 |

## curl 示例

```bash
# 创建一个保单（走policy-service），等一两秒让事件被消费
curl -s -X POST localhost:8081/api/policies -H 'Content-Type: application/json' \
  -d '{"holderName":"山田太郎","productType":"TENGAN","premium":1200.50}'

# 按投保人姓名搜索（走search-service）
curl -s 'localhost:8083/api/search/policies?q=山田'

# 按保单号精确搜索
curl -s 'localhost:8083/api/search/policies?q=POL-XXXXXXXXXXXX'

# 不带q，返回全部
curl -s localhost:8083/api/search/policies
```

## 怎么亲眼确认数据在ES里

可以直接用Kibana（`http://localhost:5601`）的Dev Tools控制台查：

```
GET policies/_search
```

或者直接curl ES：`curl localhost:9200/policies/_search`。
