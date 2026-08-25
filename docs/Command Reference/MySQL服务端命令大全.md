# MySQL 服务端命令大全
## MySQLサーバー(さーばー)コマンド(こまんど)大全(たいぜん) / MySQL Server Command Reference

> 说明 / 説明(せつめい) / Note：以下命令基于 Linux (CentOS/Ubuntu) + MySQL 8.x 环境整理，部分命令需要 root 或 sudo 权限。
> 以下(いか)のコマンドは Linux (CentOS/Ubuntu) + MySQL 8.x 環境(かんきょう)を前提(ぜんてい)にまとめたものです。一部(いちぶ)は root または sudo 権限(けんげん)が必要(ひつよう)です。
> These commands assume a Linux (CentOS/Ubuntu) + MySQL 8.x environment. Some require root or sudo privileges.

---

## 一、服务管理 / サービス(さーびす)管理(かんり) / Service Management

```bash
# 启动 MySQL 服务
systemctl start mysqld        # RHEL/CentOS
systemctl start mysql         # Ubuntu/Debian

# 停止服务
systemctl stop mysqld

# 重启服务
systemctl restart mysqld

# 查看服务状态
systemctl status mysqld

# 设置开机自启
systemctl enable mysqld

# 取消开机自启
systemctl disable mysqld

# 查看MySQL版本
mysql --version
mysqld --version
```

说明：`systemctl` 是管理 systemd 服务的标准方式，MySQL 服务名在不同发行版中可能是 `mysqld` 或 `mysql`。
説明(せつめい)：`systemctl` は systemd サービス(さーびす)を管理(かんり)する標準的(ひょうじゅんてき)な方法(ほうほう)です。ディストリビューション(でぃすとりびゅーしょん)によってサービス名(めい)は `mysqld` または `mysql` になります。
Note: `systemctl` is the standard way to manage systemd services. The service name may be `mysqld` or `mysql` depending on the distribution.

---

## 二、连接与登录 / 接続(せつぞく)とログイン(ろぐいん) / Connect & Login

```bash
# 本地登录
mysql -u root -p

# 指定主机和端口登录
mysql -h 127.0.0.1 -P 3306 -u root -p

# 登录并直接选择数据库
mysql -u root -p database_name

# 执行单条SQL后退出（不进入交互模式）
mysql -u root -p -e "SHOW DATABASES;"

# 从文件导入SQL执行
mysql -u root -p database_name < backup.sql

# 使用socket连接（本地常用，速度更快）
mysql -u root -p -S /var/lib/mysql/mysql.sock
```

说明：`-h` 指定主机、`-P` 指定端口（大写）、`-u` 用户、`-p` 提示输入密码（也可写成 `-p密码` 但不安全，密码会出现在命令历史中）。
説明：`-h` はホスト(ほすと)指定(してい)、`-P` はポート(ぽーと)指定(大文字(おおもじ))、`-u` はユーザー(ゆーざー)、`-p` はパスワード(ぱすわーど)入力(にゅうりょく)を促(うなが)します。`-pパスワード` のように直接(ちょくせつ)書(か)くこともできますが、コマンド履歴(りれき)に残(のこ)るため非推奨(ひすいしょう)です。
Note: `-h` sets host, `-P` sets port (capital), `-u` user, `-p` prompts for password. Writing `-ppassword` directly works but is unsafe since it stays in shell history.

---

## 三、用户与权限管理 / ユーザー(ゆーざー)と権限(けんげん)管理(かんり) / User & Privilege Management

```sql
-- 创建用户
CREATE USER 'username'@'%' IDENTIFIED BY 'password';
CREATE USER 'username'@'localhost' IDENTIFIED BY 'password';

-- 修改密码
ALTER USER 'username'@'%' IDENTIFIED BY 'new_password';

-- 授予权限
GRANT ALL PRIVILEGES ON database_name.* TO 'username'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON database_name.* TO 'username'@'%';

-- 授予所有库所有权限（谨慎使用）
GRANT ALL PRIVILEGES ON *.* TO 'username'@'%' WITH GRANT OPTION;

-- 刷新权限（授权后必须执行）
FLUSH PRIVILEGES;

-- 查看用户权限
SHOW GRANTS FOR 'username'@'%';

-- 撤销权限
REVOKE INSERT ON database_name.* FROM 'username'@'%';

-- 删除用户
DROP USER 'username'@'%';

-- 查看所有用户
SELECT user, host FROM mysql.user;
```

说明：`'username'@'%'` 中的 `%` 表示允许从任意主机连接，`'username'@'localhost'` 表示仅限本机连接，二者是不同的账号。
説明：`'username'@'%'` の `%` は任意(にんい)のホスト(ほすと)からの接続(せつぞく)を許可(きょか)することを意味(いみ)します。`'username'@'localhost'` はローカル(ろーかる)からのみ接続(せつぞく)可能(かのう)という意味(いみ)で、両者(りょうしゃ)は別(べつ)のアカウント(あかうんと)として扱(あつか)われます。
Note: `%` in `'username'@'%'` allows connection from any host, while `'username'@'localhost'` restricts to local connections only — these are treated as separate accounts.

---

## 四、数据库操作 / データベース(でーたべーす)操作(そうさ) / Database Operations

```sql
-- 查看所有数据库
SHOW DATABASES;

-- 创建数据库
CREATE DATABASE db_name CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 选择数据库
USE db_name;

-- 删除数据库
DROP DATABASE db_name;

-- 查看当前数据库
SELECT DATABASE();

-- 查看数据库大小
SELECT table_schema AS "Database",
       SUM(data_length + index_length) / 1024 / 1024 AS "Size (MB)"
FROM information_schema.tables
GROUP BY table_schema;
```

说明：创建数据库时建议明确指定字符集为 `utf8mb4`，以支持完整的Unicode字符（包括emoji和日语汉字）。
説明：データベース(でーたべーす)作成時(さくせいじ)には文字(もじ)コード(こーど)を `utf8mb4` に明示的(めいじてき)に指定(してい)することを推奨(すいしょう)します。絵文字(えもじ)や日本語(にほんご)の漢字(かんじ)を含(ふく)む完全(かんぜん)な Unicode(ゆにこーど) をサポート(さぽーと)できます。
Note: Always specify `utf8mb4` explicitly when creating a database to fully support Unicode, including emoji and Japanese characters.

---

## 五、表操作 / テーブル(てーぶる)操作(そうさ) / Table Operations

```sql
-- 查看所有表
SHOW TABLES;

-- 查看表结构
DESCRIBE table_name;
SHOW CREATE TABLE table_name;

-- 创建表
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 修改表结构
ALTER TABLE users ADD COLUMN email VARCHAR(100);
ALTER TABLE users MODIFY COLUMN name VARCHAR(200);
ALTER TABLE users DROP COLUMN email;

-- 重命名表
RENAME TABLE old_name TO new_name;

-- 清空表数据（保留结构，重置自增ID）
TRUNCATE TABLE table_name;

-- 删除表
DROP TABLE table_name;

-- 查看表索引
SHOW INDEX FROM table_name;
```

说明：`TRUNCATE` 比 `DELETE FROM` 更快，因为它不逐行删除，而是直接重建表，但无法通过事务回滚。
説明：`TRUNCATE` は行(ぎょう)ごとに削除(さくじょ)するのではなくテーブル(てーぶる)を再構築(さいこうちく)するため `DELETE FROM` より高速(こうそく)ですが、トランザクション(とらんざくしょん)によるロールバック(ろーるばっく)はできません。
Note: `TRUNCATE` is faster than `DELETE FROM` because it rebuilds the table instead of deleting row by row, but it cannot be rolled back within a transaction.

---

## 六、备份与恢复 / バックアップ(ばっくあっぷ)と復元(ふくげん) / Backup & Restore

```bash
# 备份单个数据库
mysqldump -u root -p db_name > backup.sql

# 备份所有数据库
mysqldump -u root -p --all-databases > all_backup.sql

# 备份指定表
mysqldump -u root -p db_name table_name > table_backup.sql

# 备份时不含数据（仅结构）
mysqldump -u root -p --no-data db_name > structure_only.sql

# 压缩备份
mysqldump -u root -p db_name | gzip > backup.sql.gz

# 恢复数据库
mysql -u root -p db_name < backup.sql

# 恢复压缩备份
gunzip < backup.sql.gz | mysql -u root -p db_name

# 使用 mysqlpump（并行备份，MySQL 5.7+）
mysqlpump -u root -p --default-parallelism=4 db_name > backup.sql
```

说明：生产环境备份大库时建议加 `--single-transaction` 参数，可在不锁表的情况下获得一致性快照（仅适用于InnoDB）。
説明：本番(ほんばん)環境(かんきょう)で大(おお)きなデータベース(でーたべーす)をバックアップ(ばっくあっぷ)する際(さい)は `--single-transaction` オプション(おぷしょん)を付(つ)けることを推奨(すいしょう)します。テーブル(てーぶる)をロック(ろっく)せずに一貫性(いっかんせい)のあるスナップショット(すなっぷしょっと)を取得(しゅとく)できます（InnoDB(いのでーびー)専用(せんよう)）。
Note: For production backups of large databases, add `--single-transaction` to get a consistent snapshot without locking tables (InnoDB only).

---

## 七、日志与配置 / ログ(ろぐ)と設定(せってい) / Logs & Configuration

```bash
# 查看配置文件位置
mysql --help | grep "Default options"
# 常见路径: /etc/my.cnf 或 /etc/mysql/my.cnf

# 查看错误日志（路径需在配置中确认）
tail -f /var/log/mysqld.log

# 编辑配置文件
vi /etc/my.cnf
```

```sql
-- 查看当前配置变量
SHOW VARIABLES LIKE 'max_connections';
SHOW VARIABLES LIKE '%buffer_pool%';

-- 动态修改配置（重启后失效，需同步改配置文件才能持久生效）
SET GLOBAL max_connections = 500;

-- 开启慢查询日志
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;
SHOW VARIABLES LIKE 'slow_query_log_file';

-- 查看错误日志路径
SHOW VARIABLES LIKE 'log_error';

-- 查看binlog开启状态
SHOW VARIABLES LIKE 'log_bin';

-- 查看binlog文件列表
SHOW BINARY LOGS;

-- 查看当前binlog写入位置
SHOW MASTER STATUS;
```

说明：`SET GLOBAL` 只在当前运行实例生效，MySQL重启后会丢失，若需永久生效必须同步写入配置文件（如 `my.cnf`）。
説明：`SET GLOBAL` は現在(げんざい)稼働中(かどうちゅう)のインスタンス(いんすたんす)にのみ有効(ゆうこう)で、MySQL 再起動(さいきどう)後(ご)は失(うしな)われます。永続的(えいぞくてき)に反映(はんえい)させるには設定(せってい)ファイル(ふぁいる)（`my.cnf`など）にも同(おな)じ内容(ないよう)を書(か)く必要(ひつよう)があります。
Note: `SET GLOBAL` only affects the running instance and resets after restart. To persist it, you must also write the same value into the config file (e.g. `my.cnf`).

---

## 八、性能与状态监控 / パフォーマンス(ぱふぉーまんす)と状態(じょうたい)監視(かんし) / Performance & Status Monitoring

```sql
-- 查看当前连接
SHOW PROCESSLIST;
SHOW FULL PROCESSLIST;

-- 结束某个连接/进程
KILL 12345;  -- 12345 为 ID

-- 查看服务器状态变量
SHOW STATUS LIKE 'Threads_connected';
SHOW STATUS LIKE 'Uptime';
SHOW STATUS LIKE 'Questions';

-- 查看InnoDB引擎状态
SHOW ENGINE INNODB STATUS\G

-- 查看数据库表存储引擎
SELECT table_name, engine FROM information_schema.tables WHERE table_schema='db_name';

-- 查看锁等待情况（MySQL 8.0+）
SELECT * FROM performance_schema.data_lock_waits;

-- 查看正在运行的事务
SELECT * FROM information_schema.innodb_trx;
```

```bash
# 命令行查看运行状态摘要
mysqladmin -u root -p status

# 实时刷新状态（类似 top）
mysqladmin -u root -p -i 2 extended-status
```

说明：`SHOW PROCESSLIST` 常用于排查慢查询或死锁，若发现异常连接可用 `KILL` 加进程ID终止；`\G` 是让输出按列纵向显示，便于阅读长字段。
説明：`SHOW PROCESSLIST` はスロークエリ(すろーくえり)やデッドロック(でっどろっく)の調査(ちょうさ)によく使(つか)われます。異常(いじょう)な接続(せつぞく)を見(み)つけたら `KILL` + プロセス(ぷろせす)ID(あいでぃー) で終了(しゅうりょう)できます。`\G` は出力(しゅつりょく)を縦(たて)に整形(せいけい)して長(なが)いフィールド(ふぃーるど)を見(み)やすくするものです。
Note: `SHOW PROCESSLIST` is commonly used to investigate slow queries or deadlocks; use `KILL` with a process ID to terminate a problematic connection. `\G` formats output vertically, making long fields easier to read.

---

## 九、主从复制常用命令 / レプリケーション(れぷりけーしょん)関連(かんれん)コマンド(こまんど) / Replication Commands

```sql
-- 在主库上查看binlog状态
SHOW MASTER STATUS;

-- 在从库上配置主库信息
CHANGE MASTER TO
  MASTER_HOST='master_ip',
  MASTER_USER='repl_user',
  MASTER_PASSWORD='repl_password',
  MASTER_LOG_FILE='mysql-bin.000001',
  MASTER_LOG_POS=154;

-- 启动从库复制线程
START SLAVE;

-- 停止从库复制线程
STOP SLAVE;

-- 查看从库状态（重点看 Seconds_Behind_Master、Slave_IO/SQL_Running）
SHOW SLAVE STATUS\G

-- 重置从库复制配置
RESET SLAVE ALL;
```

说明：MySQL 8.0.23 之后官方逐步用 `REPLICA` 替代 `SLAVE` 术语（如 `SHOW REPLICA STATUS`），但旧语法仍兼容，生产环境需按版本确认。
説明：MySQL 8.0.23 以降(いこう)、公式(こうしき)には `SLAVE` の代(か)わりに `REPLICA` という用語(ようご)が使(つか)われるようになっています（例(れい)：`SHOW REPLICA STATUS`）。旧構文(きゅうこうぶん)も互換性(ごかんせい)がありますが、本番(ほんばん)環境(かんきょう)ではバージョン(ばーじょん)を確認(かくにん)してください。
Note: Since MySQL 8.0.23, the official terminology shifted from `SLAVE` to `REPLICA` (e.g. `SHOW REPLICA STATUS`), though the old syntax remains compatible. Verify your version in production.

---

## 十、常见运维小结 / 運用(うんよう)ノート(のーと) / Common Ops Notes

| 场景 / シーン(しーん) / Scenario | 命令 / コマンド(こまんど) / Command |
|---|---|
| 忘记root密码 / パスワード(ぱすわーど)紛失(ふんしつ)時(じ) / Forgot root password | `mysqld_safe --skip-grant-tables &` 后重设密码 |
| 检查表是否损坏 / テーブル(てーぶる)破損(はそん)確認(かくにん) / Check table integrity | `CHECK TABLE table_name;` |
| 修复表 / テーブル(てーぶる)修復(しゅうふく) / Repair table | `REPAIR TABLE table_name;` |
| 优化表（回收空间）/ 最適化(さいてきか) / Optimize table | `OPTIMIZE TABLE table_name;` |
| 导出为CSV / CSV(しーえすぶい)出力(しゅつりょく) / Export to CSV | `SELECT ... INTO OUTFILE '/tmp/data.csv';` |

---

📌 温馨提示 / ワンポイント(わんぽいんと)アドバイス(あどばいす) / Tip：
生产环境执行 `DROP`、`TRUNCATE`、`GRANT ALL` 等高危命令前，务必先确认当前连接的是哪个环境（`SELECT DATABASE(), @@hostname;`），避免误操作。
本番(ほんばん)環境(かんきょう)で `DROP`、`TRUNCATE`、`GRANT ALL` などの危険(きけん)なコマンド(こまんど)を実行(じっこう)する前(まえ)には、必(かなら)ず接続先(せつぞくさき)の環境(かんきょう)を確認(かくにん)してください（`SELECT DATABASE(), @@hostname;`）。誤操作(ごそうさ)を防(ふせ)ぐためです。
Before running high-risk commands like `DROP`, `TRUNCATE`, or `GRANT ALL` in production, always confirm which environment you're connected to (`SELECT DATABASE(), @@hostname;`) to avoid mistakes.
