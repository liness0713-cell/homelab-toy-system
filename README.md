# homelab-toy-system

迷你保单管理系统（Mini Policy System）—— 用来把 [`docs/大型分布式系统_全景模块图.md`](docs/大型分布式系统_全景模块图.md)
里的中间件逐一练到的玩具工程。落地规划见 [`docs/homelab-toy-system-plan.md`](docs/homelab-toy-system-plan.md)。

## 当前进度

- [x] P0 — k3s 单节点 + Docker
- [x] P1 — `policy-service`（MySQL + Redis + Liquibase + MyBatis），本地CRUD跑通
- [x] P2 — `gateway-service`（JWT鉴权）+ `frontend`（登录+列表页），本地全链路跑通；
      k3s里卸载Traefik、换装 `ingress-nginx`（业务服务还没接进k3s，见 `infra/k8s/ingress-nginx-demo/README.md`）
- [ ] P3 — Kafka + `notification-service`
- [ ] P4 — Elasticsearch + `search-service`（CQRS）
- [ ] P5 及以后 —— 见规划文档第5节

## 目录结构

```
homelab-toy-system/
├── infra/
│   ├── docker-compose.dev.yml       # 本地开发中间件（MySQL/Redis，后续加Kafka/ES）
│   └── k8s/
│       └── ingress-nginx-demo/      # P2: 验证ingress-nginx本身能工作的demo，非业务
├── policy-service/                   # 核心写服务
├── gateway-service/                  # API网关（Spring Cloud Gateway + JWT）
├── frontend/                         # React SPA（登录 + 保单列表）
├── docs/                             # 设计文档
└── README.md
```

各服务的运行方式见其自己的 `README.md`。

## 本地开发环境启动

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

然后按顺序启动业务服务（都不进容器，方便调试）：

```bash
# 1. policy-service（默认8081）
cd policy-service && mvn spring-boot:run

# 2. gateway-service（默认8080，代理到policy-service）
cd gateway-service && mvn spring-boot:run

# 3. frontend（默认5173）
cd frontend && npm install && npm run dev
```

打开 `http://localhost:5173`，用 demo 账号 `admin / admin123` 登录。

## k3s集群现状

- 已用 `ingress-nginx` 替换k3s默认的Traefik（详见 `docs/homelab-toy-system-plan.md` 3.5节的决策背景）
- 目前只部署了一个验证用的demo（`infra/k8s/ingress-nginx-demo/`），业务服务（gateway-service/frontend等）
  要到P5阶段才会真正部署进k3s（届时才需要解决镜像分发问题，即Harbor，P7阶段）
