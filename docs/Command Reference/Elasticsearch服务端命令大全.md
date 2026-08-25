# Elasticsearch 服务端命令大全
## Elasticsearch(えらすてぃっくさーち)サーバー(さーばー)コマンド(こまんど)大全(たいぜん) / Elasticsearch Server Command Reference

> 说明 / 説明(せつめい) / Note：以下命令基于 Elasticsearch 8.x 整理，默认已开启安全认证（Security，8.x起默认开启），示例中的 REST API 调用使用 `curl`，也可在 Kibana Dev Tools 中直接执行（语法略有简化）。
> 以下(いか)のコマンドは Elasticsearch 8.x を前提(ぜんてい)にまとめたものです。8.x からはセキュリティ(せきゅりてぃ)機能(きのう)（Security(せきゅりてぃ)）がデフォルト(でふぉると)で有効(ゆうこう)です。例(れい)の REST API(れすとえーぴーあい) 呼(よ)び出(だ)しには `curl` を使用(しよう)していますが、Kibana(きばな) Dev Tools(でぶつーるず) でも（構文(こうぶん)を簡略化(かんりゃくか)して）実行(じっこう)できます。
> These commands assume Elasticsearch 8.x, where Security is enabled by default. Examples use `curl` for REST API calls, but the same requests (in simplified syntax) also work directly in Kibana Dev Tools.

---

## 一、服务管理 / サービス(さーびす)管理(かんり) / Service Management

```bash
# 启动服务（systemd管理）
systemctl start elasticsearch

# 停止服务
systemctl stop elasticsearch

# 重启服务
systemctl restart elasticsearch

# 查看服务状态
systemctl status elasticsearch

# 开机自启
systemctl enable elasticsearch

# 直接前台启动（调试用）
./bin/elasticsearch

# 查看版本
curl -X GET "localhost:9200" -u elastic:password
```

```bash
# 生成/重置内置elastic用户密码（首次安装常用）
./bin/elasticsearch-reset-password -u elastic

# 生成用于Kibana连接的注册token
./bin/elasticsearch-create-enrollment-token -s kibana
```

说明：ES 8.x 默认开启TLS和身份验证，首次安装后必须通过 `elasticsearch-reset-password` 获取或重置密码，否则无法用 `curl` 直接访问API。
説明：ES(いーえす) 8.x はデフォルト(でふぉると)で TLS(てぃーえるえす) と認証(にんしょう)が有効(ゆうこう)になっています。初回(しょかい)インストール(いんすとーる)後(ご)は必(かなら)ず `elasticsearch-reset-password` でパスワード(ぱすわーど)を取得(しゅとく)またはリセット(りせっと)しないと `curl` で直接(ちょくせつ) API(えーぴーあい) にアクセス(あくせす)できません。
Note: ES 8.x enables TLS and authentication by default. After first install, you must obtain or reset a password via `elasticsearch-reset-password`, or direct `curl` API access won't work.

---

## 二、集群健康与状态 / クラスター(くらすたー)ヘルス(へるす)と状態(じょうたい) / Cluster Health & Status

```bash
# 查看集群健康状态（green/yellow/red）
curl -X GET "localhost:9200/_cluster/health?pretty" -u elastic:password

# 查看集群详细状态
curl -X GET "localhost:9200/_cluster/state?pretty" -u elastic:password

# 查看所有节点信息
curl -X GET "localhost:9200/_cat/nodes?v" -u elastic:password

# 查看节点资源使用（CPU/内存/磁盘）
curl -X GET "localhost:9200/_cat/nodes?v&h=name,cpu,ram.percent,disk.used_percent" -u elastic:password

# 查看集群设置
curl -X GET "localhost:9200/_cluster/settings?pretty" -u elastic:password

# 查看待处理任务
curl -X GET "localhost:9200/_cluster/pending_tasks?pretty" -u elastic:password
```

说明：集群状态 `yellow` 通常表示副本分片未完全分配（常见于单节点测试环境属正常现象），`red` 则表示有主分片丢失，必须立即排查。
説明：クラスター(くらすたー)状態(じょうたい)が `yellow`(いえろー) の場合(ばあい)、通常(つうじょう)はレプリカ(れぷりか)シャード(しゃーど)が完全(かんぜん)に割(わ)り当(あ)てられていないことを意味(いみ)します（単一(たんいつ)ノード(のーど)のテスト(てすと)環境(かんきょう)ではよくある正常(せいじょう)な状態(じょうたい)です）。`red`(れっど) はプライマリ(ぷらいまり)シャード(しゃーど)の欠落(けつらく)を意味(いみ)し、直(ただ)ちに調査(ちょうさ)が必要(ひつよう)です。
Note: A `yellow` cluster status usually means replica shards aren't fully allocated (common and normal in single-node test setups). `red` means a primary shard is missing and requires immediate investigation.

---

## 三、索引管理 / インデックス(いんでっくす)管理(かんり) / Index Management

```bash
# 创建索引
curl -X PUT "localhost:9200/my_index?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{
  "settings": { "number_of_shards": 3, "number_of_replicas": 1 }
}'

# 查看所有索引
curl -X GET "localhost:9200/_cat/indices?v" -u elastic:password

# 查看某索引详情（mapping、settings）
curl -X GET "localhost:9200/my_index?pretty" -u elastic:password

# 查看索引mapping
curl -X GET "localhost:9200/my_index/_mapping?pretty" -u elastic:password

# 修改索引设置（部分设置需先关闭索引）
curl -X PUT "localhost:9200/my_index/_settings?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "number_of_replicas": 2 }'

# 关闭/打开索引
curl -X POST "localhost:9200/my_index/_close?pretty" -u elastic:password
curl -X POST "localhost:9200/my_index/_open?pretty" -u elastic:password

# 删除索引（危险操作）
curl -X DELETE "localhost:9200/my_index?pretty" -u elastic:password

# 为索引创建别名
curl -X POST "localhost:9200/_aliases?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "actions": [{ "add": { "index": "my_index", "alias": "my_alias" } }] }'
```

说明：`number_of_shards`（主分片数）在索引创建后无法直接修改，必须通过 `_reindex` 或 `_shrink`/`_split` API 迁移数据，因此规划容量时应谨慎评估初始分片数。
説明：`number_of_shards`(なんばーおぶしゃーず)（プライマリ(ぷらいまり)シャード(しゃーど)数(すう)）はインデックス(いんでっくす)作成後(さくせいご)に直接(ちょくせつ)変更(へんこう)できません。データ(でーた)を移行(いこう)するには `_reindex`(りーいんでっくす) や `_shrink`(しゅりんく)/`_split`(すぷりっと) API(えーぴーあい) を使(つか)う必要(ひつよう)があるため、容量(ようりょう)計画時(けいかくじ)には初期(しょき)シャード(しゃーど)数(すう)を慎重(しんちょう)に検討(けんとう)すべきです。
Note: `number_of_shards` (primary shard count) cannot be changed after index creation — migrating requires `_reindex` or `_shrink`/`_split` APIs. Plan the initial shard count carefully when sizing capacity.

---

## 四、文档操作 / ドキュメント(どきゅめんと)操作(そうさ) / Document Operations

```bash
# 新增/覆盖文档（指定ID）
curl -X PUT "localhost:9200/my_index/_doc/1?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "title": "hello", "content": "world" }'

# 新增文档（自动生成ID）
curl -X POST "localhost:9200/my_index/_doc?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "title": "hello", "content": "world" }'

# 查询单个文档
curl -X GET "localhost:9200/my_index/_doc/1?pretty" -u elastic:password

# 局部更新文档
curl -X POST "localhost:9200/my_index/_update/1?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "doc": { "title": "updated title" } }'

# 删除文档
curl -X DELETE "localhost:9200/my_index/_doc/1?pretty" -u elastic:password

# 批量操作（bulk API，NDJSON格式，注意每行必须换行结尾）
curl -X POST "localhost:9200/_bulk?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "index": { "_index": "my_index", "_id": "2" } }
{ "title": "bulk doc" }
'
```

说明：`_bulk` API 是批量写入的标准方式，比逐条 `PUT`/`POST` 效率高得多，生产环境的大批量导入应始终使用 bulk，且需注意请求体必须以换行符结尾。
説明：`_bulk`(ばるく) API(えーぴーあい) は一括(いっかつ)書(か)き込(こ)みの標準的(ひょうじゅんてき)な方法(ほうほう)で、1件(けん)ずつの `PUT`/`POST` よりはるかに効率的(こうりつてき)です。本番(ほんばん)環境(かんきょう)での大量(たいりょう)インポート(いんぽーと)には常(つね)に bulk(ばるく) を使(つか)うべきで、リクエスト(りくえすと)本文(ほんぶん)は改行(かいぎょう)で終(お)わる必要(ひつよう)がある点(てん)に注意(ちゅうい)してください。
Note: The `_bulk` API is the standard way to write in batches and is far more efficient than individual `PUT`/`POST` calls. Always use bulk for large production imports, and note the request body must end with a newline.

---

## 五、查询与搜索 / クエリ(くえり)と検索(けんさく) / Query & Search

```bash
# 简单搜索（URI query string方式）
curl -X GET "localhost:9200/my_index/_search?q=title:hello&pretty" -u elastic:password

# DSL查询（推荐方式，功能更完整）
curl -X GET "localhost:9200/my_index/_search?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{
  "query": { "match": { "title": "hello" } }
}'

# 聚合查询示例
curl -X GET "localhost:9200/my_index/_search?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{
  "size": 0,
  "aggs": { "by_category": { "terms": { "field": "category.keyword" } } }
}'

# 统计文档数量
curl -X GET "localhost:9200/my_index/_count?pretty" -u elastic:password

# 解释某条查询的评分逻辑（调试相关性）
curl -X GET "localhost:9200/my_index/_explain/1?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "query": { "match": { "title": "hello" } } }'
```

说明：URI query string 方式简单但功能有限，生产环境应优先使用 Query DSL（JSON请求体），可组合bool查询、过滤、排序、高亮等复杂能力。
説明：URI(ゆーあーるあい) query(くえり) string(すとりんぐ) 方式(ほうしき)はシンプル(しんぷる)ですが機能(きのう)が限定的(げんていてき)です。本番(ほんばん)環境(かんきょう)では Query(くえり) DSL(でぃーえすえる)（JSON(じぇいそん)リクエスト(りくえすと)ボディ(ぼでぃ)）を優先的(ゆうせんてき)に使用(しよう)し、bool(ぶーる)クエリ(くえり)・フィルター(ふぃるたー)・ソート(そーと)・ハイライト(はいらいと)などの複雑(ふくざつ)な機能(きのう)を組(く)み合(あ)わせるべきです。
Note: URI query string search is simple but limited. In production, prefer Query DSL (JSON request body), which supports combining bool queries, filters, sorting, highlighting, and more.

---

## 六、快照与备份 / スナップショット(すなっぷしょっと)とバックアップ(ばっくあっぷ) / Snapshot & Backup

```bash
# 注册快照仓库（以文件系统仓库为例，需先在配置中加入path.repo）
curl -X PUT "localhost:9200/_snapshot/my_backup?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "type": "fs", "settings": { "location": "/mnt/backup/es_snapshots" } }'

# 创建快照（备份所有索引）
curl -X PUT "localhost:9200/_snapshot/my_backup/snapshot_1?wait_for_completion=true&pretty" -u elastic:password

# 查看快照列表
curl -X GET "localhost:9200/_snapshot/my_backup/_all?pretty" -u elastic:password

# 查看快照状态
curl -X GET "localhost:9200/_snapshot/my_backup/snapshot_1/_status?pretty" -u elastic:password

# 从快照恢复索引
curl -X POST "localhost:9200/_snapshot/my_backup/snapshot_1/_restore?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "indices": "my_index" }'

# 删除快照
curl -X DELETE "localhost:9200/_snapshot/my_backup/snapshot_1?pretty" -u elastic:password
```

说明：恢复快照前目标索引必须先关闭或不存在，否则会报错，且快照仓库路径需在所有相关节点的 `elasticsearch.yml` 中的 `path.repo` 一致配置，否则无法识别仓库。
説明：スナップショット(すなっぷしょっと)を復元(ふくげん)する前(まえ)には、対象(たいしょう)のインデックス(いんでっくす)を事前(じぜん)に閉(と)じるか存在(そんざい)しない状態(じょうたい)にしておく必要(ひつよう)があります。そうしないとエラー(えらー)になります。また、スナップショット(すなっぷしょっと)リポジトリ(りぽじとり)のパス(ぱす)は関連(かんれん)する全(すべ)てのノード(のーど)の `elasticsearch.yml` の `path.repo` で一致(いっち)させておかないとリポジトリ(りぽじとり)が認識(にんしき)されません。
Note: Before restoring a snapshot, the target index must be closed or not exist, or it will error. Also, the snapshot repository path must be consistently configured in `path.repo` across all relevant nodes' `elasticsearch.yml`, or the repository won't be recognized.

---

## 七、用户与角色权限 / ユーザー(ゆーざー)とロール(ろーる)権限(けんげん) / User & Role Security

```bash
# 创建角色
curl -X POST "localhost:9200/_security/role/my_role?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{
  "indices": [{ "names": ["my_index*"], "privileges": ["read", "write"] }]
}'

# 创建用户并绑定角色
curl -X POST "localhost:9200/_security/user/myuser?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "password": "mypassword", "roles": ["my_role"] }'

# 查看所有用户
curl -X GET "localhost:9200/_security/user?pretty" -u elastic:password

# 查看所有角色
curl -X GET "localhost:9200/_security/role?pretty" -u elastic:password

# 禁用/启用用户
curl -X PUT "localhost:9200/_security/user/myuser/_disable?pretty" -u elastic:password
curl -X PUT "localhost:9200/_security/user/myuser/_enable?pretty" -u elastic:password

# 删除用户
curl -X DELETE "localhost:9200/_security/user/myuser?pretty" -u elastic:password
```

说明：`_security` 相关API需要Elasticsearch启用了内置安全功能（8.x默认开启），若使用的是Basic许可证以外的功能（如更细粒度的字段级安全），可能需要相应许可证支持。
説明：`_security`(せきゅりてぃ) 関連(かんれん)の API(えーぴーあい) は Elasticsearch(えらすてぃっくさーち) の組(く)み込(こ)みセキュリティ(せきゅりてぃ)機能(きのう)が有効(ゆうこう)（8.x ではデフォルト(でふぉると)有効(ゆうこう)）である必要(ひつよう)があります。Basic(べーしっく)ライセンス(らいせんす)以外(いがい)の機能(きのう)（フィールド(ふぃーるど)レベル(れべる)セキュリティ(せきゅりてぃ)など、より細(こま)かい制御(せいぎょ)）を使(つか)う場合(ばあい)は対応(たいおう)するライセンス(らいせんす)が必要(ひつよう)になることがあります。
Note: `_security` APIs require Elasticsearch's built-in security to be enabled (on by default in 8.x). Features beyond the Basic license, like fine-grained field-level security, may require an appropriate license.

---

## 八、性能与监控 / パフォーマンス(ぱふぉーまんす)と監視(かんし) / Performance & Monitoring

```bash
# 查看索引统计（文档数、大小、查询/写入耗时等）
curl -X GET "localhost:9200/my_index/_stats?pretty" -u elastic:password

# 查看节点性能统计
curl -X GET "localhost:9200/_nodes/stats?pretty" -u elastic:password

# 查看当前正在执行的任务
curl -X GET "localhost:9200/_tasks?pretty" -u elastic:password

# 查看慢查询相关设置（需先在索引层面配置慢日志阈值）
curl -X PUT "localhost:9200/my_index/_settings?pretty" -u elastic:password \
  -H 'Content-Type: application/json' -d'
{ "index.search.slowlog.threshold.query.warn": "2s" }'

# 查看分片分布情况
curl -X GET "localhost:9200/_cat/shards?v" -u elastic:password

# 查看线程池状态（排查拒绝请求问题）
curl -X GET "localhost:9200/_cat/thread_pool?v" -u elastic:password

# 手动触发段合并（减少小段数量，提升查询性能，会消耗大量IO）
curl -X POST "localhost:9200/my_index/_forcemerge?max_num_segments=1&pretty" -u elastic:password
```

说明：`_forcemerge` 会消耗大量磁盘IO和CPU，应仅在业务低峰期对只读或很少写入的索引（如日志类历史索引）执行，切勿在高并发写入的索引上使用。
説明：`_forcemerge`(ふぉーすまーじ) は大量(たいりょう)のディスク(でぃすく)IO(あいおー)とCPU(しーぴーゆー)を消費(しょうひ)します。業務(ぎょうむ)の閑散期(かんさんき)に、読(よ)み取(と)り専用(せんよう)または書(か)き込(こ)みがほとんどないインデックス(いんでっくす)（ログ(ろぐ)系(けい)の履歴(れきし)インデックス(いんでっくす)など）に対(たい)してのみ実行(じっこう)すべきで、高並行(こうへいこう)書(か)き込(こ)み中(ちゅう)のインデックス(いんでっくす)には絶対(ぜったい)に使用(しよう)しないでください。
Note: `_forcemerge` consumes heavy disk I/O and CPU. Only run it during low-traffic periods on read-only or rarely-written indices (like historical log indices) — never on indices under heavy concurrent writes.

---

## 九、常见运维小结 / 運用(うんよう)ノート(のーと) / Common Ops Notes

| 场景 / シーン(しーん) / Scenario | 命令 / コマンド(こまんど) / Command |
|---|---|
| 检查集群整体是否健康 / クラスター(くらすたー)全体(ぜんたい)の健全性(けんぜんせい)確認(かくにん) / Overall cluster health | `_cluster/health?pretty` |
| 查看磁盘水位是否触发只读保护 / ディスク(でぃすく)水位(すいい)確認(かくにん) / Check disk watermark | `_cat/allocation?v` |
| 排查某索引写入变慢 / 書(か)き込(こ)み遅延(ちえん)調査(ちょうさ) / Investigate slow writes | `_nodes/stats` + `_cat/thread_pool?v` |
| 重建索引（变更mapping时）/ インデックス(いんでっくす)再構築(さいこうちく) / Reindex on mapping change | `_reindex` API |
| 释放已删除文档占用的空间 / 削除(さくじょ)済(ず)みデータ(でーた)の空間(くうかん)解放(かいほう) / Reclaim deleted-doc space | `_forcemerge`（低峰期(ていほうき)执行(じっこう)） |

---

📌 温馨提示 / ワンポイント(わんぽいんと)アドバイス(あどばいす) / Tip：
生产环境执行 `DELETE`（删除索引）、`_forcemerge`、`_restore`（快照恢复覆盖数据）等高危操作前，务必先用 `_cat/indices?v` 或 `_cluster/health?pretty` 确认当前连接的集群和索引名称，避免误删或覆盖生产数据。
本番(ほんばん)環境(かんきょう)で `DELETE`(でぃーいーえるいーてぃー)（インデックス(いんでっくす)削除(さくじょ)）、`_forcemerge`(ふぉーすまーじ)、`_restore`(りすとあ)（スナップショット(すなっぷしょっと)復元(ふくげん)によるデータ(でーた)上書(うわが)き）などの危険(きけん)な操作(そうさ)を実行(じっこう)する前(まえ)には、必(かなら)ず `_cat/indices?v` や `_cluster/health?pretty` で接続先(せつぞくさき)のクラスター(くらすたー)とインデックス(いんでっくす)名(めい)を確認(かくにん)してください。誤削除(ごさくじょ)や本番(ほんばん)データ(でーた)の上書(うわが)きを防(ふせ)ぐためです。
Before running high-risk operations like `DELETE` (delete index), `_forcemerge`, or `_restore` (snapshot restore overwriting data) in production, always confirm the connected cluster and index name first with `_cat/indices?v` or `_cluster/health?pretty`, to avoid accidental deletion or overwriting production data.
