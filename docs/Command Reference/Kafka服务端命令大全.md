# Kafka 服务端命令大全
## Kafka(かふか)サーバー(さーばー)コマンド(こまんど)大全(たいぜん) / Kafka Server Command Reference

> 说明 / 説明(せつめい) / Note：以下命令基于 Kafka 3.x（含 KRaft 模式，即无需 ZooKeeper 的新架构）整理，脚本位于 `$KAFKA_HOME/bin/` 目录。若使用旧版 ZooKeeper 模式，部分命令需加 `--zookeeper` 参数替代 `--bootstrap-server`。
> 以下(いか)のコマンドは Kafka 3.x（ZooKeeper(ずーきーぱー)不要(ふよう)の新(あたら)しい KRaft(けーらふと) モード(もーど)含(ふく)む）を前提(ぜんてい)にまとめたものです。スクリプト(すくりぷと)は `$KAFKA_HOME/bin/` にあります。旧(きゅう)ZooKeeper(ずーきーぱー)モード(もーど)の場合(ばあい)、一部(いちぶ)のコマンド(こまんど)は `--bootstrap-server` の代(か)わりに `--zookeeper` パラメータ(ぱらめーた)が必要(ひつよう)です。
> These commands assume Kafka 3.x (including KRaft mode, the new ZooKeeper-free architecture). Scripts live in `$KAFKA_HOME/bin/`. For legacy ZooKeeper mode, some commands need `--zookeeper` instead of `--bootstrap-server`.

---

## 一、服务管理 / サービス(さーびす)管理(かんり) / Service Management

```bash
# 启动 ZooKeeper（仅传统模式需要，KRaft模式跳过此步）
zookeeper-server-start.sh -daemon config/zookeeper.properties

# 启动 Kafka Broker
kafka-server-start.sh -daemon config/server.properties

# 停止 Kafka Broker
kafka-server-stop.sh

# 停止 ZooKeeper
zookeeper-server-stop.sh

# KRaft模式：初始化存储（首次启动前执行一次）
kafka-storage.sh random-uuid
kafka-storage.sh format -t <生成的UUID> -c config/kraft/server.properties

# 查看Kafka版本
kafka-topics.sh --version
```

```bash
# 若使用 systemd 管理
systemctl start kafka
systemctl status kafka
systemctl restart kafka
```

说明：KRaft 模式（Kafka 3.3+ 生产可用）用内置的 Raft 协议管理元数据，彻底移除了 ZooKeeper 依赖，是当前官方推荐的新集群部署方式。
説明：KRaft(けーらふと) モード(もーど)（Kafka 3.3+ で本番(ほんばん)利用(りよう)可能(かのう)）は内蔵(ないぞう)の Raft(らふと) プロトコル(ぷろとこる)でメタデータ(めただーた)を管理(かんり)し、ZooKeeper(ずーきーぱー)への依存(いぞん)を完全(かんぜん)に排除(はいじょ)します。現在(げんざい)公式(こうしき)に推奨(すいしょう)されている新規(しんき)クラスター(くらすたー)構築(こうちく)方式(ほうしき)です。
Note: KRaft mode (production-ready since Kafka 3.3) manages metadata via an embedded Raft protocol, fully removing the ZooKeeper dependency — it's now the officially recommended way to deploy new clusters.

---

## 二、Topic 管理 / Topic(とぴっく)管理(かんり) / Topic Management

```bash
# 创建topic
kafka-topics.sh --create \
  --bootstrap-server localhost:9092 \
  --topic my_topic \
  --partitions 3 \
  --replication-factor 2

# 查看所有topic
kafka-topics.sh --list --bootstrap-server localhost:9092

# 查看topic详情（分区、副本分布、ISR）
kafka-topics.sh --describe --topic my_topic --bootstrap-server localhost:9092

# 修改分区数（只能增加，不能减少）
kafka-topics.sh --alter --topic my_topic --partitions 6 --bootstrap-server localhost:9092

# 删除topic
kafka-topics.sh --delete --topic my_topic --bootstrap-server localhost:9092

# 修改topic配置（如保留时间）
kafka-configs.sh --alter \
  --entity-type topics --entity-name my_topic \
  --add-config retention.ms=604800000 \
  --bootstrap-server localhost:9092

# 查看topic配置
kafka-configs.sh --describe --entity-type topics --entity-name my_topic --bootstrap-server localhost:9092
```

说明：分区数只能增不能减，因为减少分区会破坏消息的key到分区的哈希映射，导致顺序性和数据一致性问题，规划时应预留余量。
説明：パーティション(ぱーてぃしょん)数(すう)は増(ふ)やすことはできても減(へ)らすことはできません。減(へ)らすとメッセージ(めっせーじ)の key からパーティション(ぱーてぃしょん)へのハッシュ(はっしゅ)マッピング(まっぴんぐ)が崩(くず)れ、順序性(じゅんじょせい)やデータ(でーた)整合性(せいごうせい)の問題(もんだい)が発生(はっせい)するためです。計画時(けいかくじ)に余裕(よゆう)を持(も)たせておくべきです。
Note: Partition count can only increase, never decrease — reducing it breaks the key-to-partition hash mapping, causing ordering and consistency issues. Plan with headroom in mind.

---

## 三、生产者与消费者 / Producer(ぷろでゅーさー)とConsumer(こんしゅーまー) / Producer & Consumer

```bash
# 命令行生产消息（交互式，逐行输入）
kafka-console-producer.sh --topic my_topic --bootstrap-server localhost:9092

# 生产消息并指定key（用冒号分隔key和value）
kafka-console-producer.sh --topic my_topic --bootstrap-server localhost:9092 \
  --property "parse.key=true" --property "key.separator=:"

# 命令行消费消息（从最新offset开始）
kafka-console-consumer.sh --topic my_topic --bootstrap-server localhost:9092

# 从头开始消费全部历史消息
kafka-console-consumer.sh --topic my_topic --from-beginning --bootstrap-server localhost:9092

# 消费时显示key和分区信息
kafka-console-consumer.sh --topic my_topic --bootstrap-server localhost:9092 \
  --property print.key=true --property print.partition=true

# 指定消费者组消费（便于测试offset提交行为）
kafka-console-consumer.sh --topic my_topic --group my_group --bootstrap-server localhost:9092
```

说明：命令行生产/消费工具主要用于调试和快速验证，生产环境的实际业务应使用官方或第三方客户端SDK（Java/Python等）以获得更好的性能和可靠性控制。
説明：コマンド(こまんど)ライン(らいん)の生産(せいさん)/消費(しょうひ)ツール(つーる)は主(おも)にデバッグ(でばっぐ)や動作確認(どうさかくにん)用(よう)です。本番(ほんばん)業務(ぎょうむ)では公式(こうしき)またはサードパーティ(さーどぱーてぃ)のクライアント(くらいあんと)SDK(えすでぃーけー)（Java/Python等(とう)）を使(つか)い、性能(せいのう)と信頼性(しんらいせい)を確保(かくほ)すべきです。
Note: Console producer/consumer tools are mainly for debugging and quick verification. Production workloads should use official or third-party client SDKs (Java/Python, etc.) for better performance and reliability control.

---

## 四、消费者组管理 / Consumer(こんしゅーまー)Group(ぐるーぷ)管理(かんり) / Consumer Group Management

```bash
# 查看所有消费者组
kafka-consumer-groups.sh --list --bootstrap-server localhost:9092

# 查看某消费者组详情（重点看LAG列，代表消费滞后量）
kafka-consumer-groups.sh --describe --group my_group --bootstrap-server localhost:9092

# 重置offset到最早
kafka-consumer-groups.sh --reset-offsets --to-earliest \
  --group my_group --topic my_topic --execute --bootstrap-server localhost:9092

# 重置offset到最新
kafka-consumer-groups.sh --reset-offsets --to-latest \
  --group my_group --topic my_topic --execute --bootstrap-server localhost:9092

# 重置offset到指定时间点
kafka-consumer-groups.sh --reset-offsets --to-datetime 2026-08-01T00:00:00.000 \
  --group my_group --topic my_topic --execute --bootstrap-server localhost:9092

# 删除消费者组（组内所有消费者需先下线）
kafka-consumer-groups.sh --delete --group my_group --bootstrap-server localhost:9092
```

说明：`LAG` 是判断消费能力是否跟得上生产速度的核心指标，持续增长的LAG通常意味着需要扩容消费者实例或排查消费逻辑瓶颈。
説明：`LAG`(らぐ) は消費(しょうひ)能力(のうりょく)が生産(せいさん)速度(そくど)に追(お)いついているかを判断(はんだん)する核心(かくしん)的(てき)な指標(しひょう)です。継続的(けいぞくてき)に増加(ぞうか)する LAG(らぐ) は通常(つうじょう)、コンシューマー(こんしゅーまー)インスタンス(いんすたんす)の増強(ぞうきょう)や消費(しょうひ)ロジック(ろじっく)のボトルネック(ぼとるねっく)調査(ちょうさ)が必要(ひつよう)なことを意味(いみ)します。
Note: `LAG` is the key indicator of whether consumption is keeping pace with production. A steadily growing LAG usually means you need more consumer instances or should investigate a bottleneck in the consumption logic.

---

## 五、集群与Broker管理 / クラスター(くらすたー)とBroker(ぶろーかー)管理(かんり) / Cluster & Broker Management

```bash
# 查看集群元数据（KRaft模式）
kafka-metadata-quorum.sh --bootstrap-server localhost:9092 describe --status

# 查看所有broker（通过topic描述间接确认，或用以下工具）
kafka-broker-api-versions.sh --bootstrap-server localhost:9092

# 分区副本重新分配（迁移数据到新broker）
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file reassign.json --execute

# 查看重新分配进度
kafka-reassign-partitions.sh --bootstrap-server localhost:9092 \
  --reassignment-json-file reassign.json --verify

# leader选举（手动触发，用于均衡leader分布）
kafka-leader-election.sh --bootstrap-server localhost:9092 \
  --election-type PREFERRED --all-topic-partitions
```

说明：`PREFERRED` leader选举是把leader角色恢复到副本列表中的首选（第一个）副本上，用于broker重启后leader分布不均的场景，属于常规运维操作。
説明：`PREFERRED`(ぷりふぁーど) リーダー(りーだー)選挙(せんきょ) は、リーダー(りーだー)役割(やくわり)をレプリカ(れぷりか)リスト(りすと)内(ない)の優先(ゆうせん)（先頭(せんとう)）レプリカ(れぷりか)に戻(もど)す操作(そうさ)です。broker(ぶろーかー)再起動後(さいきどうご)にリーダー(りーだー)分布(ぶんぷ)が偏(かたよ)った場合(ばあい)に使(つか)う、通常(つうじょう)の運用(うんよう)作業(さぎょう)です。
Note: `PREFERRED` leader election restores the leader role to the preferred (first) replica in the replica list — a routine ops task used to rebalance leaders after broker restarts.

---

## 六、性能与状态监控 / パフォーマンス(ぱふぉーまんす)と状態(じょうたい)監視(かんし) / Performance & Status Monitoring

```bash
# 查看topic的消息生产/消费吞吐性能测试
kafka-producer-perf-test.sh --topic my_topic --num-records 100000 \
  --record-size 1024 --throughput -1 \
  --producer-props bootstrap.servers=localhost:9092

kafka-consumer-perf-test.sh --topic my_topic --bootstrap-server localhost:9092 \
  --messages 100000

# 查看某topic各分区的最早/最新offset
kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic my_topic --time -1   # -1 最新, -2 最早

# 查看某分区的日志文件段信息（用于排查磁盘占用）
kafka-run-class.sh kafka.tools.DumpLogSegments --files /path/to/segment.log

# 查看JVM相关指标（配合JMX监控工具，如Prometheus JMX Exporter）
# 需在启动时配置 KAFKA_JMX_OPTS 开启JMX端口
```

说明：Kafka 自身命令行工具无法直接给出全局的CPU/内存/网络指标，生产环境通常需搭配 JMX Exporter + Prometheus + Grafana 或 Kafka Manager/Cruise Control 等外部工具做可视化监控。
説明：Kafka 自体(じたい)のコマンドラインツール(こまんどらいんつーる)ではCPU(しーぴーゆー)・メモリ(めもり)・ネットワーク(ねっとわーく)の全体(ぜんたい)指標(しひょう)を直接(ちょくせつ)確認(かくにん)できません。本番(ほんばん)環境(かんきょう)では通常(つうじょう) JMX(じぇいえむえっくす) Exporter(えくすぽーたー) + Prometheus(ぷろめてうす) + Grafana(ぐらふぁな) や Kafka Manager(まねーじゃー)、Cruise Control(くるーずこんとろーる) などの外部(がいぶ)ツール(つーる)と組(く)み合(あ)わせて可視化(かしか)監視(かんし)を行(おこな)います。
Note: Kafka's own CLI tools can't directly show global CPU/memory/network metrics. Production setups typically pair JMX Exporter + Prometheus + Grafana, or tools like Kafka Manager / Cruise Control, for visualized monitoring.

---

## 七、ACL 权限管理 / ACL 権限(けんげん)管理(かんり) / ACL Management

```bash
# 添加ACL权限（授权用户对topic的读写）
kafka-acls.sh --bootstrap-server localhost:9092 \
  --add --allow-principal User:alice \
  --operation Read --operation Write \
  --topic my_topic

# 查看所有ACL规则
kafka-acls.sh --bootstrap-server localhost:9092 --list

# 删除ACL规则
kafka-acls.sh --bootstrap-server localhost:9092 \
  --remove --allow-principal User:alice \
  --operation Read --topic my_topic

# 针对消费者组授权
kafka-acls.sh --bootstrap-server localhost:9092 \
  --add --allow-principal User:alice \
  --operation Read --group my_group
```

说明：Kafka ACL 需要在 `server.properties` 中开启 `authorizer.class.name`（如SimpleAclAuthorizer或StandardAuthorizer）才会生效，默认关闭状态下ACL命令虽可执行但不会真正拦截未授权访问。
説明：Kafka(かふか) の ACL(えーしーえる) は `server.properties` で `authorizer.class.name`（SimpleAclAuthorizer(しんぷるあくるおーそらいざー) や StandardAuthorizer(すたんだーどおーそらいざー) など）を有効化(ゆうこうか)しないと機能(きのう)しません。デフォルト(でふぉると)で無効(むこう)の状態(じょうたい)ではACLコマンド(こまんど)自体(じたい)は実行(じっこう)できても、実際(じっさい)には未承認(みしょうにん)アクセス(あくせす)を遮断(しゃだん)しません。
Note: Kafka ACLs only take effect if `authorizer.class.name` (e.g. SimpleAclAuthorizer or StandardAuthorizer) is enabled in `server.properties`. With it disabled by default, ACL commands run but don't actually block unauthorized access.

---

## 八、常见运维小结 / 運用(うんよう)ノート(のーと) / Common Ops Notes

| 场景 / シーン(しーん) / Scenario | 命令 / コマンド(こまんど) / Command |
|---|---|
| 检查topic是否有消息堆积 / メッセージ(めっせーじ)滞留(たいりゅう)確認(かくにん) / Check message backlog | `kafka-consumer-groups.sh --describe --group xxx` 看LAG |
| 紧急扩容分区 / パーティション(ぱーてぃしょん)緊急(きんきゅう)拡張(かくちょう) / Emergency partition scale-up | `kafka-topics.sh --alter --partitions N` |
| 查看某条消息具体内容 / 特定(とくてい)メッセージ(めっせーじ)確認(かくにん) / Inspect a specific message | `kafka-console-consumer.sh` + `--partition` + `--offset` |
| 检查副本是否同步（ISR）/ ISR(あいえすあーる)確認(かくにん) / Check in-sync replicas | `kafka-topics.sh --describe` 看 Isr 列 |
| 安全下线broker / Broker(ぶろーかー)の安全(あんぜん)停止(ていし) / Safely decommission a broker | 先做分区重分配迁出数据，再停止服务 |

---

📌 温馨提示 / ワンポイント(わんぽいんと)アドバイス(あどばいす) / Tip：
生产环境执行 `--delete`（删除topic）或 `--reset-offsets`（重置offset）等高危命令前，务必先用 `--describe` 确认目标topic/group及其所在集群，避免误删或误重置导致数据丢失或重复消费。
本番(ほんばん)環境(かんきょう)で `--delete`（topic削除(さくじょ)）や `--reset-offsets`(りせっとおふせっつ)（offset(おふせっと)リセット(りせっと)）などの危険(きけん)なコマンド(こまんど)を実行(じっこう)する前(まえ)には、必(かなら)ず `--describe` で対象(たいしょう)のtopic/groupとその所属(しょぞく)クラスター(くらすたー)を確認(かくにん)してください。誤削除(ごさくじょ)や誤(あやま)ったリセット(りせっと)によるデータ(でーた)消失(しょうしつ)や重複(じゅうふく)消費(しょうひ)を防(ふせ)ぐためです。
Before running high-risk commands like `--delete` (delete topic) or `--reset-offsets` in production, always confirm the target topic/group and cluster with `--describe` first, to avoid accidental deletion or resets causing data loss or duplicate consumption.
