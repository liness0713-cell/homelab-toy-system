# homelab-toy-system

迷你保单管理系统（Mini Policy System）—— 用来把 [`docs/大型分布式系统_全景模块图.md`](docs/大型分布式系统_全景模块图.md)
里的中间件逐一练到的玩具工程。落地规划见 [`docs/homelab-toy-system-plan.md`](docs/homelab-toy-system-plan.md)。

## 当前进度

- [x] P0 — k3s 单节点 + Docker
- [x] P1 — `policy-service`（MySQL + Redis + Liquibase + MyBatis），本地CRUD跑通
- [ ] P2 — `gateway-service` + JWT 鉴权 + `frontend`
- [ ] P3 — Kafka + `notification-service`
- [ ] P4 — Elasticsearch + `search-service`（CQRS）
- [ ] P5 及以后 —— 见规划文档第5节

## 目录结构

```
homelab-toy-system/
├── infra/
│   └── docker-compose.dev.yml   # 本地开发中间件（MySQL/Redis，后续加Kafka/ES）
├── policy-service/               # 核心写服务
├── docs/                         # 设计文档
└── README.md
```

各服务的运行方式见其自己的 `README.md`。

## 本地开发环境启动

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

然后参考各服务的 README 单独启动（不进容器，方便调试）。
