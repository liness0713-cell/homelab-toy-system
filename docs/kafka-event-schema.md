# Kafka 事件契约

各服务共享的事件格式定义，谁改这个schema都要同步通知下游消费者。

## `policy-events`

`policy-service` 在保单创建/更新/取消时发布，单topic多事件类型，通过 `eventType` 字段分流。

- **发布方**：`policy-service`（`EventPublisher` / `KafkaEventPublisher`，见 `policy-service/src/main/java/com/toysystem/policy/event/`）
- **消费方**：
  - `notification-service`（消费者组 `notification-service`）：模拟发通知，纯打日志，无DB
  - `search-service`（P4阶段加入，消费者组 `search-service`）：写入Elasticsearch的`policies`索引，对外提供搜索（CQRS读路径）

两个消费者用**不同的consumer group**，各自拿到同一份消息的完整拷贝——这是"同一份事件，两种独立消费策略"的关键，对应全景图11.1节的设计思想。

消息体的Java类定义（`PolicyEvent`/`PolicyEventType`）在共享Maven模块 `event-contracts` 里，
生产者和消费者引用同一个类，不是各自维护一份（P3踩坑后的修订，见 `event-contracts/README.md`）。

**消费失败容错**：消费者统一配置 `ErrorHandlingDeserializer` + `DefaultErrorHandler`
（固定次数重试 + `DeadLetterPublishingRecoverer`），重试用尽后原始消息转发到死信topic，不会
卡住主流程。**死信topic名字带上各自的消费者组名**（`policy-events.notification-service.DLT`、
`policy-events.search-service.DLT`），不是共用一个默认的`policy-events.DLT`——两个消费者组
完全独立，共用一个死信topic会分不清消息是谁处理失败的。新增消费者也要照此模式配置（含带
消费者组名的死信topic命名），详见 `notification-service/README.md`"死信Topic容错"一节。

```json
{
  "eventId": "uuid",
  "eventType": "POLICY_CREATED | POLICY_UPDATED | POLICY_CANCELLED",
  "occurredAt": "ISO8601时间戳",
  "policy": {
    "id": "...",
    "policyNo": "...",
    "holderName": "...",
    "productType": "TENGAN | INGURAMU",
    "premium": 0,
    "status": "..."
  }
}
```

topic名：`policy-events`，3个分区，单机单副本（replication-factor=1，本地开发环境）。

## `report-events`（P9阶段新增，暂未实现）

由Canal监听宿主机MySQL binlog产生，格式对齐ZorroEvent的思路——用统一事件协议封装binlog变更，
而不是照搬上面 `policy-events` 这套应用层事件schema：

```json
{
  "gtid": "server-uuid:事务序号",
  "database": "toy_policy_db",
  "table": "policy",
  "eventType": "INSERT | UPDATE | DELETE",
  "data": { "...变更后的行数据..." },
  "occurredAt": "ISO8601时间戳"
}
```

`report-service` 消费这个topic，把事件还原成SQL并在ClickHouse里重放。这条链路和 `policy-events`
是两条独立的Kafka topic，互不影响。详见 `docs/homelab-toy-system-plan.md` 第6节。
