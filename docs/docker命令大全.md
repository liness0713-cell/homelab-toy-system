# Docker 命令大全 / Dockerコマンド完全リファレンス / Docker Command Reference

> 配合你的 k3s homelab 整理——本地构建镜像、调试容器、管理 Compose 服务时会用到。注意：k3s 集群内部跑的是 **containerd**，不是 Docker，所以这份大全主要用于**本地开发/构建/调试**场景，跟集群内部排障是两条不同的路（集群内部用 `kubectl` + `sudo k3s crictl`，见你上一份 K8s 大全）。

---

## 0. 基础信息 / 版本 / 系统

```bash
docker version                      # 客户端+服务端版本
docker info                         # 详细系统信息（存储驱动、根目录、资源限制等）
docker --help
docker <subcommand> --help          # 具体子命令的帮助

# 磁盘占用（镜像/容器/卷/构建缓存分别占多少）
docker system df
docker system df -v                 # 更详细，按每个镜像/容器列出
```

---

## 1. 镜像（Image）相关

```bash
# 查看
docker images                       # 等价于 docker image ls
docker images -a                    # 包含中间层镜像
docker image ls --filter "dangling=true"   # 只看悬空镜像（<none> 标签的那些）
docker history <image>              # 看镜像的分层构建历史，排查体积过大问题很有用

# 拉取 / 推送
docker pull <image>:<tag>
docker pull <image>@sha256:<digest>          # 按摘要精确拉取（避免 tag 漂移）
docker push <registry>/<image>:<tag>
docker tag <local-image> <registry>/<image>:<tag>   # 打标签，push 前必做

# 登录私有仓库
docker login <registry-url>
docker logout <registry-url>

# 构建
docker build -t <image>:<tag> .
docker build -t <image>:<tag> -f Dockerfile.prod .          # 指定 Dockerfile
docker build --no-cache -t <image>:<tag> .                  # 不用缓存，彻底重建
docker build --build-arg KEY=value -t <image>:<tag> .       # 传构建参数
docker build --target <stage-name> -t <image>:<tag> .       # 多阶段构建，只构建某一阶段
docker build --platform linux/amd64,linux/arm64 -t <image>:<tag> .  # 多架构构建（配合 buildx）

# 删除
docker rmi <image>
docker rmi -f <image>                        # 强制删除（即使有容器在用，慎用）
docker image prune                           # 清理悬空镜像
docker image prune -a                        # 清理所有未被任何容器使用的镜像（慎用，会删挺多）

# 导出 / 导入（离线迁移镜像场景，比如 homelab 内网传输）
docker save -o image.tar <image>:<tag>
docker load -i image.tar
docker export <container> -o container.tar   # 导出容器文件系统（跟 save 不同，丢失分层和元数据）
docker import container.tar <new-image>:<tag>

# 查看镜像详细配置
docker inspect <image>
docker inspect <image> --format '{{.Config.Env}}'   # 只取某个字段
```

---

## 2. 容器（Container）相关 —— 日常用得最多

```bash
# 运行
docker run <image>
docker run -d <image>                        # 后台运行（detached）
docker run -it <image> /bin/sh               # 交互式（常用于调试镜像）
docker run --rm <image>                      # 退出后自动删除容器（临时测试首选）
docker run --name my-container <image>
docker run -p 8080:80 <image>                # 端口映射：主机:容器
docker run -p 127.0.0.1:8080:80 <image>      # 只绑定本地回环，不对外网开放
docker run -v /host/path:/container/path <image>       # 挂载卷（bind mount）
docker run -v my-volume:/container/path <image>        # 挂载命名卷
docker run -e KEY=value <image>              # 传环境变量
docker run --env-file .env <image>
docker run --network <network-name> <image>
docker run --restart=unless-stopped <image>  # 重启策略：no / on-failure / always / unless-stopped
docker run --memory=512m --cpus=1.0 <image>  # 资源限制
docker run -d -p 8080:80 --name web --restart unless-stopped nginx   # 综合示例

# 查看
docker ps                                     # 只看运行中的
docker ps -a                                  # 包含已停止的
docker ps -q                                  # 只输出容器 ID（配合其他命令批量操作用）
docker ps --filter "status=exited"
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 生命周期管理
docker start <container>
docker stop <container>
docker restart <container>
docker pause <container>
docker unpause <container>
docker kill <container>                       # 强制杀死（发 SIGKILL，不给优雅关闭机会）
docker rm <container>
docker rm -f <container>                      # 强制删（容器还在跑也直接删）
docker rm $(docker ps -aq)                    # 删除所有容器（慎用！）
docker container prune                        # 清理所有已停止的容器

# 进容器 / 执行命令
docker exec -it <container> /bin/sh
docker exec -it <container> /bin/bash
docker exec <container> env
docker attach <container>                     # 附加到容器主进程的标准输入输出（跟 exec 不同，退出可能导致容器停止，慎用）

# 日志
docker logs <container>
docker logs -f <container>                    # 实时跟踪
docker logs --tail 100 <container>
docker logs --since 1h <container>
docker logs -t <container>                    # 带时间戳

# 查看详情
docker inspect <container>
docker inspect <container> --format '{{.NetworkSettings.IPAddress}}'   # 拿容器 IP
docker inspect <container> --format '{{.State.Status}}'

# 资源监控
docker stats                                   # 实时看所有容器的 CPU/内存/网络占用
docker stats <container> --no-stream           # 只看一次，不持续刷新

# 复制文件
docker cp <container>:/path/in/container ./local-path
docker cp ./local-file <container>:/path/in/container

# 容器改成镜像（调试后想固化状态时用，生产不推荐，应该改 Dockerfile）
docker commit <container> <new-image>:<tag>

# 重命名 / 更新配置
docker rename <old-name> <new-name>
docker update --restart=always <container>
docker update --memory=1g <container>
```

---

## 3. 网络（Network）

```bash
docker network ls
docker network inspect <network-name>
docker network create <network-name>
docker network create --driver bridge --subnet 172.20.0.0/16 <network-name>
docker network connect <network-name> <container>
docker network disconnect <network-name> <container>
docker network rm <network-name>
docker network prune                          # 清理未被使用的网络

# 常见网络驱动类型速记：
#   bridge  → 默认模式，单机内容器互通，NAT 出外网
#   host    → 容器直接用宿主机网络，没有网络隔离
#   none    → 完全没有网络
#   overlay → 跨多台 Docker 主机的容器互通（Swarm 模式用）
```

---

## 4. 数据卷（Volume）

```bash
docker volume ls
docker volume inspect <volume-name>
docker volume create <volume-name>
docker volume rm <volume-name>
docker volume prune                           # 清理未被任何容器使用的卷（会真删数据，慎用！）

# bind mount vs volume 的关键区别：
#   bind mount (-v /host/path:/container/path) → 直接映射宿主机某个目录，Docker 不管理
#   named volume (-v my-vol:/container/path)   → Docker 管理的存储，位置在 /var/lib/docker/volumes/
#   tmpfs (--tmpfs /path)                       → 纯内存存储，容器停止即消失
```

---

## 5. Docker Compose（多容器编排，homelab 里最常用的日常工具）

```bash
# 启动 / 停止
docker compose up                    # 前台启动（看日志方便，Ctrl+C 停止）
docker compose up -d                 # 后台启动
docker compose up -d --build         # 启动前先重新构建镜像
docker compose up -d --force-recreate    # 强制重建容器（即使配置没变）
docker compose down                  # 停止并删除容器、网络（不删卷）
docker compose down -v               # 连数据卷也一起删（慎用！会丢数据）
docker compose down --rmi all        # 连镜像也一起删

# 查看状态
docker compose ps
docker compose logs
docker compose logs -f <service-name>
docker compose logs --tail=100 -f

# 单独操作某个服务
docker compose start <service-name>
docker compose stop <service-name>
docker compose restart <service-name>
docker compose exec <service-name> /bin/sh
docker compose run --rm <service-name> <command>   # 临时跑一次性命令（比如跑数据库 migration）

# 扩缩容（compose 版本的简易扩容，非 K8s 那种智能调度）
docker compose up -d --scale <service-name>=3

# 配置检查
docker compose config                # 校验并打印最终生效的完整配置（多个 -f 合并后的结果）
docker compose config --services     # 只列出所有服务名

# 拉取/构建
docker compose pull
docker compose build
docker compose build --no-cache <service-name>

# 指定文件
docker compose -f docker-compose.yml -f docker-compose.override.yml up -d
docker compose -p <project-name> up -d       # 指定项目名（多套 compose 环境隔离用）
```

---

## 6. Docker Buildx（多架构构建，如果你的 homelab 有 ARM 设备如树莓派会用到）

```bash
docker buildx ls
docker buildx create --name mybuilder --use
docker buildx inspect --bootstrap

docker buildx build --platform linux/amd64,linux/arm64 -t <image>:<tag> --push .
docker buildx build --platform linux/arm64 -t <image>:<tag> --load .   # --load 只能单平台
```

---

## 7. 清理类命令（磁盘满了先看这个）

```bash
docker system df                              # 先看占用分布，别盲目清
docker system prune                           # 清理：停止的容器 + 未用网络 + 悬空镜像 + 构建缓存
docker system prune -a                        # 加上：所有未被使用的镜像（不只是悬空的），更狠
docker system prune -a --volumes              # 连未使用的卷也清（最狠，务必确认没有重要数据）
docker builder prune                          # 只清构建缓存
docker builder prune --all                    # 清所有构建缓存（包括还在用的镜像相关缓存）
```

---

## 8. Dockerfile 常用指令速查（写镜像时对照）

```dockerfile
FROM <image>:<tag>                  # 基础镜像
FROM <image>:<tag> AS builder       # 多阶段构建，给阶段起名
WORKDIR /app                        # 设置工作目录
COPY src/ ./src/                    # 复制文件（构建上下文里的文件）
COPY --from=builder /app/dist ./dist   # 从其他构建阶段拷文件
ADD archive.tar.gz /app/            # 类似 COPY 但会自动解压 tar，一般优先用 COPY
RUN apt-get update && apt-get install -y curl   # 构建时执行命令，注意合并 RUN 减少层数
ENV KEY=value                       # 环境变量，运行时也生效
ARG BUILD_VERSION                   # 仅构建时可用的变量
EXPOSE 8080                         # 声明监听端口（仅文档作用，不会自动映射）
VOLUME /data                        # 声明挂载点
USER appuser                        # 切换运行用户（安全最佳实践，别用 root 跑）
ENTRYPOINT ["./entrypoint.sh"]      # 容器启动时固定执行的命令
CMD ["--config", "/app/config.yaml"]   # 默认参数，可被 docker run 后面的参数覆盖
HEALTHCHECK --interval=30s CMD curl -f http://localhost/health || exit 1
```

---

## 9. 调试思路速查表

```bash
# 容器起不来 / 立刻退出
docker ps -a                                  # 看 STATUS/Exited 状态码
docker logs <container>
docker inspect <container> --format '{{.State.ExitCode}}'
docker run -it --entrypoint /bin/sh <image>   # 覆盖 entrypoint，直接进 shell 排查环境本身

# 端口访问不通
docker ps                                     # 确认端口映射（PORTS 那一列）
docker inspect <container> --format '{{json .NetworkSettings.Ports}}'
docker exec -it <container> curl localhost:<port>   # 先在容器内部自测

# 磁盘占满
docker system df -v
docker system prune -a --volumes              # 确认无重要数据后再执行

# 镜像构建慢 / 体积过大
docker history <image>                        # 看哪一层最占体积
docker build --no-cache ...                   # 排除缓存导致的假象问题

# 容器间网络不通（同一 compose 项目内）
docker network ls
docker network inspect <network-name>         # 看容器是否都在同一网络里
docker compose exec <service-a> ping <service-b>   # compose 网络内可以直接用服务名互相 ping
```

---

*生成于 2026-08-15，跟你的 K8s 命令大全配套使用：本地开发/构建用这份 Docker 命令，k3s 集群内部排障用那份 kubectl 命令。*
