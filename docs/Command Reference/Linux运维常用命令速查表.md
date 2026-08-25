# Linux 运维常用命令速查表　Linuxコマンド早見表　Linux Ops Cheat Sheet

> 中文说明为主，标题附日文（假名）／英文，方便顺便记术语。

---

## 📍 位置与身份确认　現在地・ユーザー確認　where am I

| 命令 | 说明 |
|---|---|
| `pwd` | 显示当前工作目录的**绝对路径**（print working directory） |
| `pwd -P` | 若路径含软链接，显示解析后的真实物理路径 |
| `readlink -f .` | 效果类似 `pwd -P`，显示当前目录真实绝对路径 |
| `cd -` | 回到**上一个**所在目录 |
| `cd ~` / `cd` | 回到家目录 |
| `hostname` | 显示当前主机名（远程多机操作时先确认自己在哪台机器上！） |
| `whoami` | 显示当前登录用户名 |
| `id` | 显示当前用户 uid/gid 及所属组 |
| `who` / `w` | 显示当前登录到本机的所有用户及其操作 |
| `uname -a` | 显示内核版本、架构、主机名等系统信息 |
| `date` | 显示/设置系统日期时间 |
| `cal` | 显示日历 |
| `echo $PATH` | 查看可执行文件搜索路径 |
| `which cmd` | 查看命令对应的可执行文件路径 |
| `type cmd` | 判断命令是内建命令、别名还是外部程序 |
| `whereis cmd` | 查找命令的二进制/源码/man手册位置 |

> 💡 日本語：`pwd` は現在(げんざい)のディレクトリの絶対(ぜったい)パスを表示(ひょうじ)します。`hostname` で今(いま)どのサーバーにいるかも必ず確認(かくにん)しましょう——特(とく)に複数(ふくすう)のサーバーを同時(どうじ)にSSH接続(せつぞく)している時(とき)に重要(じゅうよう)です。

---

## 🔐 权限提升与切换用户　権限昇格・ユーザー切替　sudo / su

| 命令 | 说明 |
|---|---|
| `sudo cmd` | 以root权限执行一次命令 |
| `sudo -i` / `sudo -s` | 切换到root交互式shell（-i读取root环境变量，-s不读取） |
| `sudo -u user cmd` | 以指定用户身份执行命令 |
| `su - user` | 切换用户并加载其环境变量 |
| `visudo` | 安全编辑 `/etc/sudoers`（自带语法检查，比直接vim改更安全） |
| `sudo -l` | 查看当前用户可执行的sudo权限列表 |
| `sudo !!` | 对上一条命令追加sudo重新执行（忘记加sudo时的救命技巧） |

---

## 📁 文件与目录操作　ファイル・ディレクトリ操作　files

| 命令 | 说明 |
|---|---|
| `ls -lah` | 详细列表，含隐藏文件与易读大小 |
| `cp -r a b` | 递归复制目录；`-p`保留属性 `-v`显示过程 |
| `mv a b` | 移动/重命名 |
| `rm -rf dir` | 强制递归删除（**危险，先确认路径！**） |
| `mkdir -p a/b/c` | 递归创建多级目录 |
| `rsync -avz src dst` | 增量同步（本地/远程），保留属性并压缩传输 |
| `find . -name "*.log"` | 按名称查找文件 |
| `find . -mtime -1` | 查找24小时内修改过的文件 |
| `find . -size +100M` | 查找大于100MB的文件 |
| `find . -exec cmd {} \;` | 对查找结果逐个执行命令 |
| `ln -s target link` | 创建软链接（符号链接） |
| `tree -L 2` | 以树状显示目录结构（限2层） |
| `stat file` | 查看文件详细元信息（时间戳/inode等） |
| `touch file` | 创建空文件，或更新已有文件的时间戳 |
| `basename` / `dirname` | 提取路径中的文件名部分 / 目录部分 |

---

## 🔒 权限与所有者　パーミッション・所有者　chmod / chown

| 命令 | 说明 |
|---|---|
| `chmod 755 file` | `rwxr-xr-x`：属主全权，组与其他可读可执行 |
| `chmod 644 file` | `rw-r--r--`：常见文件权限 |
| `chmod -R 755 dir` | 递归修改整个目录权限 |
| `chmod +x file` | 添加可执行权限（不改变其他位） |
| `chown user:group f` | 修改属主与属组 |
| `chown -R u:g dir` | 递归修改目录属主属组 |
| `umask` | 查看/设置新建文件默认权限掩码 |
| `getfacl` / `setfacl` | 查看/设置ACL细粒度权限 |
| `chattr +i file` | 设置不可修改属性（连root都不能改，需先去除才能改回） |

> r=4 w=2 x=1；三位数字分别对应 属主 / 属组 / 其他用户

---

## 📝 文本查看与处理　テキスト表示・処理　grep / sed / awk

| 命令 | 说明 |
|---|---|
| `cat` / `tac file` | 正序 / 倒序输出全文 |
| `less file` | 分页查看（`/`搜索 `n`下一个 `q`退出） |
| `head -n 50` / `tail -n 50` | 查看开头 / 结尾50行 |
| `tail -f file` | 实时跟踪日志追加内容 |
| `grep -rn "kw" .` | 递归查找关键字并显示行号 |
| `grep -i` / `-v` / `-E` | 忽略大小写 / 反向匹配 / 正则扩展 |
| `sed -i 's/a/b/g' f` | 原地替换文件中所有a为b |
| `awk '{print $1}' f` | 按空格分列，打印第1列 |
| `sort` / `uniq -c` | 排序 / 去重并统计出现次数 |
| `wc -l file` | 统计行数 |
| `diff -u a b` | 对比两文件差异（统一格式） |
| `xargs` | 将标准输入转为命令参数，常配 `find | xargs` |
| `cut -d',' -f1` | 按分隔符切分并取指定列 |
| `tr 'a-z' 'A-Z'` | 字符转换/替换（如大小写转换） |

---

## 📦 压缩与归档　圧縮・アーカイブ　tar / zip

| 命令 | 说明 |
|---|---|
| `tar -czvf a.tar.gz dir` | 打包并gzip压缩目录 |
| `tar -xzvf a.tar.gz` | 解压 `.tar.gz` |
| `tar -tzvf a.tar.gz` | 仅查看压缩包内容不解压 |
| `zip -r a.zip dir` | 压缩为zip |
| `unzip a.zip -d dir` | 解压zip到指定目录 |
| `gzip` / `gunzip file` | 单文件压缩/解压 |

---

## ⚙️ 进程与作业管理　プロセス・ジョブ管理　process

| 命令 | 说明 |
|---|---|
| `ps aux` / `ps -ef` | 查看所有进程（BSD / System V风格） |
| `ps aux --sort=-%mem` | 按内存占用降序排列 |
| `top` / `htop` | 实时进程与资源监控 |
| `kill -9 PID` | 强制终止进程（SIGKILL，可能导致数据未保存） |
| `kill -15 PID` | 优雅终止进程（SIGTERM，默认，推荐优先尝试） |
| `pkill -f name` | 按名称/命令行模式批量终止 |
| `nohup cmd &` | 后台运行且不受终端关闭影响 |
| `jobs` / `fg` / `bg` | 查看 / 切前台 / 切后台 作业 |
| `Ctrl+Z`, `disown` | 挂起进程后脱离shell管理 |
| `lsof -p PID` | 查看进程打开的文件/端口 |
| `lsof -i :8080` | 查看谁占用了8080端口 |

---

## 📊 系统指标速览　システム指標確認　metrics

| 命令 | 说明 |
|---|---|
| `uptime` | 运行时长 + 1/5/15分钟负载均值 |
| `free -h` | 内存与swap使用情况（易读单位） |
| `vmstat 1` | 每秒刷新：CPU/内存/IO/上下文切换 |
| `mpstat -P ALL 1` | 各CPU核心使用率（需sysstat） |
| `iostat -xz 1` | 磁盘IO吞吐与等待延迟（需sysstat） |
| `sar -n DEV 1` | 网络接口历史/实时流量统计 |
| `dmesg -T \| tail` | 内核日志（含OOM/硬件事件） |
| `journalctl -xe` | systemd日志，含上下文详情 |
| `journalctl -u svc -f` | 实时跟踪指定服务日志 |
| `last` | 登录历史记录 |

---

## 💾 磁盘与存储　ディスク・ストレージ　disk

| 命令 | 说明 |
|---|---|
| `df -h` | 各挂载点磁盘空间使用率 |
| `df -i` | inode使用率（inode耗尽也会导致写入失败） |
| `du -sh *` | 当前目录下各项占用大小汇总 |
| `du -sh . \| sort -rh` | 按体积从大到小排序目录 |
| `lsblk` | 查看块设备与分区树状结构 |
| `mount` / `umount` | 挂载/卸载文件系统 |
| `fdisk -l` | 查看磁盘分区表 |
| `fsck /dev/sdX` | 检查/修复文件系统（需先卸载） |

---

## 🌐 网络相关　ネットワーク関連　networking

| 命令 | 说明 |
|---|---|
| `ip a` / `ip addr` | 查看网卡与IP地址 |
| `ip route` | 查看路由表 |
| `ss -tulnp` | 查看监听端口及对应进程（netstat替代） |
| `ping -c 4 host` | 测试连通性（发4个包） |
| `traceroute` / `mtr host` | 追踪路由路径；mtr持续监测每跳丢包 |
| `curl -I url` | 只看响应头，快速探测服务状态 |
| `curl -o f url` | 下载文件到指定文件名 |
| `wget url` | 下载文件 |
| `nc -zv host port` | 测试指定端口是否可达 |
| `dig` / `nslookup domain` | DNS解析查询 |
| `ssh user@host` | 远程登录 |
| `scp f user@host:/path` | 通过ssh协议复制文件到远程 |
| `ssh-copy-id user@host` | 上传公钥实现免密登录 |

---

## 🔧 服务与开机自启　サービス・自動起動　systemd

| 命令 | 说明 |
|---|---|
| `systemctl status svc` | 查看服务状态 |
| `systemctl start/stop/restart svc` | 启动/停止/重启服务 |
| `systemctl enable/disable svc` | 设置/取消开机自启 |
| `systemctl daemon-reload` | 修改unit文件后重新加载配置 |
| `systemctl list-units --failed` | 列出启动失败的服务 |

---

## 👤 用户与软件包　ユーザー・パッケージ管理　users / pkg

| 命令 | 说明 |
|---|---|
| `useradd -m user` | 创建用户并生成家目录 |
| `passwd user` | 设置/修改密码 |
| `usermod -aG grp user` | 将用户追加到某个组（切勿漏 `-a`，否则会覆盖原有组） |
| `groups` | 查看用户所属的所有组 |
| `apt update && apt upgrade` | Debian/Ubuntu 更新软件源与升级 |
| `apt install/remove pkg` | 安装/卸载软件包 |
| `dnf` / `yum install pkg` | RHEL/CentOS系安装软件包 |
| `dpkg -l \| grep pkg` | 查询已安装的deb包 |

---

## 🛠️ 其他常用小技巧　その他の小技　misc

| 命令 | 说明 |
|---|---|
| `history \| grep kw` | 搜索历史命令 |
| `alias ll='ls -lah'` | 创建命令别名（写入`~/.bashrc`生效持久） |
| `crontab -e` / `-l` | 编辑/查看定时任务 |
| `watch -n 2 cmd` | 每2秒重复执行并刷新显示 |
| `env` / `export VAR=x` | 查看/设置环境变量 |
| `source ~/.bashrc` | 立即重新加载配置文件，无需重开终端 |
| `man cmd` / `cmd --help` | 查阅命令手册/简要帮助 |

---

## ⚠️ 危险命令，务必三思　危険なコマンド　handle with care

| 命令 | 危险点 |
|---|---|
| `rm -rf /` 及其变体 | 无回收站、无确认，路径打错等于清空系统（参见GitLab 2017事故） |
| `dd if=... of=...` | `of=`指错设备会无声覆盖整块磁盘，操作前务必用`lsblk`确认设备名 |
| `chmod -R 777 /` | 从根目录递归改权限会破坏SSH/systemd等的权限检查，系统直接瘫痪 |
| `mkfs` 用错设备号 | 格式化到正在使用的系统盘，瞬间销毁文件系统元数据 |
| `:(){ :|:& };:` （fork炸弹） | 自我复制耗尽进程与内存，机器秒卡死 |
| `git push --force` 到共享分支 | 覆盖远程历史，团队其他人未推送的提交可能永久消失 |

> 💡 习惯：危险命令前先 `pwd` / `hostname` 确认自己在哪；重要操作先 `echo` 出来看看会影响什么；生产环境终端建议用不同配色区分。

---
*Front／正面: 权限・文件・文本处理・压缩・进程　|　Back／背面: 位置确认・系统指标・磁盘・网络・服务・用户・危险命令*
