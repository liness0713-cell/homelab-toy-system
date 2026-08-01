# notification-service

`policy-events` 的消费者A。纯消费者，没有数据库。当前阶段（P3）职责：
- 订阅 `policy-events` topic（消费者组 `notification-service`）
- 收到事件后模拟"发通知"——只是打一条日志，不真的发邮件/短信

## 技术栈

Spring Boot 3.3 + spring-kafka，Java 21。

## 依赖

- Kafka 已经在跑（默认 `localhost:9092`，`docker compose -f infra/docker-compose.dev.yml up -d kafka`）
- `policy-service` 已经在跑并且发过事件（这个服务本身不产生数据，纯被动消费）

## 本地单独运行

```bash
source ~/.sdkman/bin/sdkman-init.sh
cd notification-service
mvn spring-boot:run
```

默认配置（可用环境变量覆盖，见 `application.yml`）：

| 环境变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8082` |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |

`auto-offset-reset: earliest`：第一次启动时消费者组还没有提交过offset，会从topic最早的消息开始追，
方便演示"服务起晚了也能追上之前错过的事件"。

## 怎么看到效果

创建/更新/取消一个保单（通过 `policy-service` 或 `gateway-service`），观察这个服务的控制台日志，
应该能看到类似：

```
[通知] eventId=... holder=山田太郎 -> 您的保单 POL-XXXX 已创建成功，产品：TENGAN，保费：1200.50
```

## 暴露的端口

服务端口：`8082`，只有 `/actuator/health`，没有对外业务API。

## 已知的Kafka JSON反序列化坑（写给自己看的，别踩第二次）

跨服务用Spring Kafka的JSON序列化时，`policy-service` 那边的 `JsonSerializer` 默认会把**自己的类名**
（`com.toysystem.policy.event.PolicyEvent`）写进消息的 `__TypeId__` 头。消费端的 `JsonDeserializer`
默认会优先信这个头，而不是用你在这边配置的 `spring.json.value.default.type`——结果就是不管
`trusted.packages` 怎么配，它都会去找 `com.toysystem.policy.event.PolicyEvent` 这个类（在这个服务里
根本不存在），直接报"not in the trusted packages"。

修复：在这边把 `spring.json.use.type.headers` 设为 `false`，强制消费端永远用自己配的
`default.type`，不理会生产端塞进消息头里的类名——这也更符合"两个服务各自维护自己的DTO副本，
只共享JSON契约不共享Java类"的设计。

**第二个坑**：反序列化异常是在Kafka客户端内部抛出的，`DefaultErrorHandler` 处理不了这种异常，
会报 `This error handler cannot process 'SerializationException's directly`，然后**卡在同一条
消息上死循环重试，永远不往后消费**。解法是用 `ErrorHandlingDeserializer` 包一层实际的
`JsonDeserializer`（见 `application.yml`），让反序列化异常被包装进record交给容器的错误处理器，
而不是直接抛出来阻塞整个consumer。
