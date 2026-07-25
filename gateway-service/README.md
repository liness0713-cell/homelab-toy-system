# gateway-service

迷你保单系统的API网关。当前阶段（P2）职责：
- 反向代理 `/api/policies/**` 到 `policy-service`
- JWT鉴权：除 `/auth/login`、`/actuator/**` 外，所有请求必须携带合法的 `Authorization: Bearer <token>`
- 校验通过后把用户名透传给下游服务（`X-Auth-User` 头），下游信任网关，不用重复验证token

限流（Sentinel等）不在P2范围内，留作后续加练。

## 技术栈

Spring Boot 3.3 + Spring Cloud Gateway（WebFlux）+ jjwt，Java 21。

## 依赖

- 无独立DB/Redis；依赖 `policy-service` 已经在跑（默认 `http://localhost:8081`）
- 没有独立的用户服务，登录账号硬编码在 `InMemoryUserStore`：`admin / admin123`（仅demo用，见 `security/InMemoryUserStore.java` 顶部注释）

## 本地单独运行

```bash
source ~/.sdkman/bin/sdkman-init.sh
cd gateway-service
mvn spring-boot:run
```

默认配置（可用环境变量覆盖，见 `application.yml`）：

| 环境变量 | 默认值 |
|---|---|
| `SERVER_PORT` | `8080` |
| `POLICY_SERVICE_URI` | `http://localhost:8081` |
| `JWT_SECRET` | 内置开发用默认值，生产必须覆盖 |
| `JWT_EXPIRATION_MINUTES` | `60` |
| `CORS_ALLOWED_ORIGIN_1` | `http://localhost:5173`（frontend本地开发端口） |
| `CORS_ALLOWED_ORIGIN_2` | `http://127.0.0.1:5173`（同上，浏览器把它当成不同origin，两个都放开） |

## 暴露的端口 / API

服务端口：`8080`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/login` | `{"username","password"}` → `{"token","expiresInSeconds"}` |
| GET/POST/PUT/DELETE | `/api/policies/**` | 代理到 `policy-service`，需要 `Authorization: Bearer <token>` |
| GET | `/actuator/health` | 健康检查 |

## curl 示例

```bash
# 登录拿token
TOKEN=$(curl -s -X POST localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

# 带token访问业务接口（网关代理到policy-service）
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/api/policies

# 不带token 应该 401
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/policies
```
