# Redis 服务端命令大全
## Redis(れでぃす)サーバー(さーばー)コマンド(こまんど)大全(たいぜん) / Redis Server Command Reference

> 说明 / 説明(せつめい) / Note：以下命令基于 Linux (CentOS/Ubuntu) + Redis 6.x/7.x 环境整理，`redis-cli` 为客户端交互命令，Shell 命令需相应权限。
> 以下(いか)のコマンドは Linux (CentOS/Ubuntu) + Redis 6.x/7.x 環境(かんきょう)を前提(ぜんてい)にまとめたものです。`redis-cli` はクライアント(くらいあんと)対話(たいわ)コマンド(こまんど)、シェル(しぇる)コマンド(こまんど)は相応(そうおう)の権限(けんげん)が必要(ひつよう)です。
> These commands assume a Linux (CentOS/Ubuntu) + Redis 6.x/7.x environment. `redis-cli` commands are interactive client commands; shell commands require appropriate privileges.

---

## 一、服务管理 / サービス(さーびす)管理(かんり) / Service Management

```bash
# 启动服务（systemd管理时）
systemctl start redis

# 停止服务
systemctl stop redis

# 重启服务
systemctl restart redis

# 查看服务状态
systemctl status redis

# 开机自启
systemctl enable redis

# 直接以配置文件方式启动（前台运行，常用于调试）
redis-server /etc/redis/redis.conf

# 后台守护进程方式启动
redis-server /etc/redis/redis.conf --daemonize yes

# 查看版本
redis-server --version
redis-cli --version
```

说明：生产环境推荐用 systemd 管理服务而非手动 `daemonize`，便于统一日志、开机自启和进程守护。
説明：本番(ほんばん)環境(かんきょう)では手動(しゅどう)の `daemonize` ではなく systemd でサービス(さーびす)を管理(かんり)することを推奨(すいしょう)します。ログ(ろぐ)の一元化(いちげんか)、自動起動(じどうきどう)、プロセス(ぷろせす)監視(かんし)が統一的(とういつてき)に行(おこな)えます。
Note: In production, prefer systemd over manual `daemonize` — it unifies logging, auto-start, and process supervision.

---

## 二、连接与登录 / 接続(せつぞく)とログイン(ろぐいん) / Connect & Login

```bash
# 本地连接
redis-cli

# 指定主机端口连接
redis-cli -h 127.0.0.1 -p 6379

# 带密码连接（旧方式，明文暴露风险）
redis-cli -h 127.0.0.1 -p 6379 -a "password"

# 交互式安全输入密码（推荐）
redis-cli -h 127.0.0.1 -p 6379 --askpass

# 连接指定库（Redis默认16个库，索引0-15）
redis-cli -n 3

# 执行单条命令后退出
redis-cli PING

# 使用URI方式连接（Redis 6+，支持ACL用户）
redis-cli -u redis://username:password@127.0.0.1:6379/0
```

```
# 进入交互模式后的常用命令
PING                # 测试连接，返回 PONG
AUTH password       # 进入后再认证
SELECT 3            # 切换数据库
QUIT / EXIT         # 退出
```

说明：`-a` 直接带密码会在进程列表（`ps aux`）中暴露密码，生产环境建议用 `--askpass` 或环境变量 `REDISCLI_AUTH` 代替。
説明：`-a` で直接(ちょくせつ)パスワード(ぱすわーど)を指定(してい)すると `ps aux` などのプロセス(ぷろせす)一覧(いちらん)にパスワード(ぱすわーど)が露出(ろしゅつ)します。本番(ほんばん)環境(かんきょう)では `--askpass` または環境変数(かんきょうへんすう) `REDISCLI_AUTH` の利用(りよう)を推奨(すいしょう)します。
Note: Passing `-a` with a plaintext password exposes it in `ps aux`. In production, prefer `--askpass` or the `REDISCLI_AUTH` environment variable instead.

---

## 三、用户与权限管理（ACL）/ ユーザー(ゆーざー)と権限(けんげん)管理(かんり)（ACL）/ User & ACL Management

```
# 查看所有用户
ACL LIST
ACL WHOAMI

# 创建/编辑用户
ACL SETUSER myuser on >mypassword ~cache:* +get +set

# 查看某用户权限详情
ACL GETUSER myuser

# 删除用户
ACL DELUSER myuser

# 查看所有可用权限分类
ACL CAT

# 查看某分类下具体命令
ACL CAT string

# 重新加载ACL配置文件
ACL LOAD

# 将当前ACL配置持久化写入aclfile
ACL SAVE

# 生成随机安全密码（辅助创建账号）
ACL GENPASS
```

说明：`~cache:*` 表示该用户只能访问 key 前缀为 `cache:` 的数据，`+get +set` 表示仅授权 GET/SET 命令，遵循最小权限原则。
説明：`~cache:*` はそのユーザー(ゆーざー)が `cache:` プレフィックス(ぷれふぃっくす)のキー(きー)にしかアクセス(あくせす)できないことを示(しめ)し、`+get +set` は GET/SET コマンド(こまんど)のみ許可(きょか)することを意味(いみ)します。最小権限(さいしょうけんげん)の原則(げんそく)に従(したが)っています。
Note: `~cache:*` restricts the user to keys prefixed with `cache:`, and `+get +set` grants only the GET/SET commands — following the principle of least privilege.

---

## 四、数据库与Key操作 / データベース(でーたべーす)とKey(きー)操作(そうさ) / Database & Key Operations

```
# 查看当前库key数量
DBSIZE

# 查看所有key（生产环境慎用，会阻塞）
KEYS *

# 安全遍历key（推荐替代KEYS）
SCAN 0 MATCH cache:* COUNT 100

# 判断key是否存在
EXISTS key_name

# 查看key类型
TYPE key_name

# 设置过期时间（秒）
EXPIRE key_name 3600

# 设置过期时间（毫秒）
PEXPIRE key_name 3600000

# 查看剩余过期时间
TTL key_name
PTTL key_name

# 移除过期时间（变为持久化）
PERSIST key_name

# 删除key
DEL key_name

# 重命名key
RENAME old_key new_key

# 清空当前数据库（危险操作）
FLUSHDB

# 清空所有数据库（极度危险）
FLUSHALL

# 切换数据库
SELECT 1

# 将key从一个库移动到另一个库
MOVE key_name 1
```

说明：生产环境严禁使用 `KEYS *`，因为它会遍历整个keyspace并阻塞单线程的Redis，应始终使用游标式的 `SCAN` 分批扫描。
説明：本番(ほんばん)環境(かんきょう)では `KEYS *` の使用(しよう)を厳禁(げんきん)とします。keyspace(きーすぺーす)全体(ぜんたい)を走査(そうさ)し、シングルスレッド(しんぐるすれっど)の Redis をブロック(ぶろっく)してしまうためです。常(つね)にカーソル(かーそる)方式(ほうしき)の `SCAN` で分割(ぶんかつ)スキャン(すきゃん)すべきです。
Note: Never use `KEYS *` in production — it scans the entire keyspace and blocks Redis's single thread. Always use cursor-based `SCAN` for batched scanning instead.

---

## 五、持久化管理 / 永続化(えいぞくか)管理(かんり) / Persistence Management

```
# 手动触发RDB快照（同步，会阻塞）
SAVE

# 后台异步触发RDB快照（推荐）
BGSAVE

# 查看最近一次RDB保存时间
LASTSAVE

# 手动触发AOF重写（压缩AOF文件体积）
BGREWRITEAOF

# 查看持久化相关配置
CONFIG GET save
CONFIG GET appendonly
CONFIG GET appendfsync

# 动态开启/关闭AOF（重启后需配置文件同步）
CONFIG SET appendonly yes
CONFIG SET appendonly no
```

```bash
# 查看RDB/AOF文件所在目录
redis-cli CONFIG GET dir
```

说明：`SAVE` 是同步阻塞命令，生产环境几乎不用；`BGSAVE` 会fork子进程后台写盘，是常规备份的正确方式，但瞬间内存占用可能翻倍，需预留足够内存。
説明：`SAVE` は同期(どうき)ブロッキング(ぶろっきんぐ)コマンド(こまんど)であり、本番(ほんばん)環境(かんきょう)ではほぼ使(つか)いません。`BGSAVE` は子(こ)プロセス(ぷろせす)をfork(ふぉーく)してバックグラウンド(ばっくぐらうんど)で書(か)き込(こ)む正(ただ)しい方法(ほうほう)ですが、瞬間的(しゅんかんてき)にメモリ(めもり)使用量(しようりょう)が倍増(ばいぞう)する可能性(かのうせい)があるため十分(じゅうぶん)なメモリ(めもり)を確保(かくほ)してください。
Note: `SAVE` is a blocking synchronous command rarely used in production; `BGSAVE` forks a child process to write in the background — the correct approach — but memory usage can briefly double, so ensure adequate headroom.

---

## 六、主从复制 / レプリケーション(れぷりけーしょん) / Replication

```
# 在从节点上设置主节点（Redis 5+新语法）
REPLICAOF 192.168.1.10 6379
# 旧语法（仍兼容）
SLAVEOF 192.168.1.10 6379

# 取消复制关系，提升为主节点
REPLICAOF NO ONE

# 查看当前复制状态（含主从信息）
INFO replication

# 查看连接的从节点列表
CLIENT LIST TYPE replica
```

说明：`INFO replication` 是排查主从延迟最常用的命令，重点关注 `master_repl_offset` 与从节点 `slave_repl_offset` 的差值。
説明：`INFO replication` は主従(しゅじゅう)遅延(ちえん)を調(しら)べる際(さい)に最(もっと)もよく使(つか)われるコマンド(こまんど)です。`master_repl_offset` と従(じゅう)ノード(のーど)の `slave_repl_offset` の差(さ)に注目(ちゅうもく)してください。
Note: `INFO replication` is the go-to command for checking replication lag — focus on the difference between `master_repl_offset` and the replica's `slave_repl_offset`.

---

## 七、哨兵与集群 / Sentinel(せんちねる)とCluster(くらすたー) / Sentinel & Cluster

```bash
# 启动哨兵进程
redis-sentinel /etc/redis/sentinel.conf
```

```
# 哨兵交互命令（连接到sentinel端口，默认26379）
SENTINEL masters
SENTINEL slaves mymaster
SENTINEL get-master-addr-by-name mymaster
SENTINEL failover mymaster    # 手动触发故障转移
```

```bash
# 创建集群（Redis 5+内置工具）
redis-cli --cluster create \
  192.168.1.10:6379 192.168.1.11:6379 192.168.1.12:6379 \
  --cluster-replicas 1
```

```
# 集群内查看状态
CLUSTER INFO
CLUSTER NODES
CLUSTER SLOTS

# 检查集群健康状态
redis-cli --cluster check 192.168.1.10:6379
```

说明：Sentinel 用于主从架构下的自动故障转移，Cluster 是原生分片方案，二者用途不同，不要混用概念——生产环境需按数据规模和高可用需求二选一。
説明：Sentinel(せんちねる) はマスター(ますたー)・スレーブ(すれーぶ)構成(こうせい)における自動(じどう)フェイルオーバー(ふぇいるおーばー)用(よう)、Cluster(くらすたー) はネイティブ(ねいてぃぶ)なシャーディング(しゃーでぃんぐ)方式(ほうしき)です。両者(りょうしゃ)は用途(ようと)が異(こと)なるため混同(こんどう)しないでください。本番(ほんばん)環境(かんきょう)ではデータ(でーた)規模(きぼ)と可用性(かようせい)要件(ようけん)に応(おう)じてどちらか一方(いっぽう)を選(えら)びます。
Note: Sentinel handles automatic failover in a master-replica setup, while Cluster is native sharding — they serve different purposes. Choose one based on your data scale and high-availability requirements in production.

---

## 八、性能与状态监控 / パフォーマンス(ぱふぉーまんす)と状態(じょうたい)監視(かんし) / Performance & Status Monitoring

```
# 查看服务器全部信息
INFO
INFO server
INFO memory
INFO clients
INFO stats

# 查看内存使用详情
MEMORY USAGE key_name
MEMORY STATS
MEMORY DOCTOR

# 查看当前连接的客户端列表
CLIENT LIST

# 结束某个客户端连接
CLIENT KILL ID 12345

# 查看慢查询日志
SLOWLOG GET 10
SLOWLOG LEN
SLOWLOG RESET

# 实时监控所有命令（调试用，生产环境高负载时慎用）
MONITOR

# 查看延迟监控信息
LATENCY HISTORY event-name
LATENCY LATEST

# 测试延迟
redis-cli --latency -h 127.0.0.1 -p 6379
```

```bash
# 命令行压测工具
redis-benchmark -h 127.0.0.1 -p 6379 -q -n 100000
```

说明：`MONITOR` 会实时打印每一条命令，生产环境高并发下会显著增加CPU负担，仅建议短时间用于紧急排障。
説明：`MONITOR` は全(すべ)てのコマンド(こまんど)をリアルタイム(りあるたいむ)に出力(しゅつりょく)するため、本番(ほんばん)環境(かんきょう)の高並(こうへい)行(こう)時(じ)には CPU 負荷(ふか)が著(いちじる)しく増加(ぞうか)します。緊急(きんきゅう)のトラブルシューティング(とらぶるしゅーてぃんぐ)時(じ)に短時間(たんじかん)だけ使用(しよう)することを推奨(すいしょう)します。
Note: `MONITOR` prints every command in real time and significantly increases CPU load under high production traffic — use it only briefly for emergency troubleshooting.

---

## 九、配置管理 / 設定(せってい)管理(かんり) / Configuration Management

```
# 查看某配置项
CONFIG GET maxmemory
CONFIG GET maxmemory-policy

# 动态修改配置（重启失效，需同步写入redis.conf）
CONFIG SET maxmemory 2gb
CONFIG SET maxmemory-policy allkeys-lru

# 将当前运行配置持久化写回配置文件（Redis会自动改写redis.conf）
CONFIG REWRITE

# 查看所有配置项
CONFIG GET *
```

说明：`maxmemory-policy` 决定内存满后的淘汰策略，常见的 `allkeys-lru`（全体key按最近最少使用淘汰）适合纯缓存场景，`noeviction`（默认，不淘汰只报错）适合数据不可丢失的场景。
説明：`maxmemory-policy` はメモリ(めもり)が満(み)たされた際(さい)の削除(さくじょ)戦略(せんりゃく)を決定(けってい)します。`allkeys-lru`（全(すべ)てのkeyを最近(さいきん)最(もっと)も使(つか)われていない順(じゅん)に削除(さくじょ)）は純粋(じゅんすい)なキャッシュ(きゃっしゅ)用途(ようと)に適(てき)し、`noeviction`（デフォルト(でふぉると)、削除(さくじょ)せずエラー(えらー)を返(かえ)す）はデータ(でーた)消失(しょうしつ)が許(ゆる)されない場面(ばめん)に適(てき)します。
Note: `maxmemory-policy` decides eviction behavior when memory is full. `allkeys-lru` (evict least-recently-used keys) suits pure caching, while `noeviction` (default, errors instead of evicting) suits scenarios where data loss is unacceptable.

---

## 十、常见运维小结 / 運用(うんよう)ノート(のーと) / Common Ops Notes

| 场景 / シーン(しーん) / Scenario | 命令 / コマンド(こまんど) / Command |
|---|---|
| 忘记密码需重置 / パスワード(ぱすわーど)紛失(ふんしつ)時(じ) | 修改 `requirepass`（配置文件）后 `redis-cli CONFIG SET requirepass` 或重启 |
| 检查大key排查内存问题 / 大(おお)きなkey調査(ちょうさ) / Find big keys | `redis-cli --bigkeys` |
| 检查热点key / ホット(ほっと)key調査(ちょうさ) / Find hot keys | `redis-cli --hotkeys`（需开启LFU策略） |
| 查看某key内存占用 / メモリ(めもり)占有(せんゆう)確認(かくにん) / Check key memory | `MEMORY USAGE key_name` |
| 优雅关闭服务 / 正常(せいじょう)シャットダウン(しゃっとだうん) / Graceful shutdown | `SHUTDOWN SAVE` |

---

📌 温馨提示 / ワンポイント(わんぽいんと)アドバイス(あどばいす) / Tip：
生产环境执行 `FLUSHALL`、`FLUSHDB`、`CONFIG SET maxmemory-policy` 等高危命令前，务必先用 `CLIENT LIST` 或 `INFO server` 确认当前连接的是哪个实例/环境，避免误操作导致缓存或数据丢失。
本番(ほんばん)環境(かんきょう)で `FLUSHALL`、`FLUSHDB`、`CONFIG SET maxmemory-policy` などの危険(きけん)なコマンド(こまんど)を実行(じっこう)する前(まえ)には、必(かなら)ず `CLIENT LIST` や `INFO server` で接続先(せつぞくさき)のインスタンス(いんすたんす)・環境(かんきょう)を確認(かくにん)してください。誤操作(ごそうさ)によるキャッシュ(きゃっしゅ)やデータ(でーた)消失(しょうしつ)を防(ふせ)ぐためです。
Before running high-risk commands like `FLUSHALL`, `FLUSHDB`, or `CONFIG SET maxmemory-policy` in production, always confirm the connected instance/environment first with `CLIENT LIST` or `INFO server`, to avoid accidental cache or data loss.
