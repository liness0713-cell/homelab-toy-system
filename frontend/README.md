# frontend

迷你保单系统的前端SPA。当前职责：
- 登录页（对接 `gateway-service` 的 `/auth/login`，拿JWT存到 `localStorage`）
- 保单列表页（含一个简单的创建表单 + 取消按钮），所有业务请求都带 `Authorization: Bearer <token>` 打到网关
- 搜索页（P4新增）：走 `search-service`（Elasticsearch）的只读搜索API，跟列表页读的
  `policy-service`/MySQL是两条完全独立的路径——CQRS读写分离在界面上的体现

登录后顶部有"保单列表"/"搜索"两个tab切换。

## 技术栈

React 19 + Vite，纯JS（不用TS，保持和这个阶段的复杂度匹配）。

## 依赖

- `gateway-service` 已经在跑（默认 `http://localhost:8080`），网关再代理到 `policy-service`
- demo登录账号：`admin / admin123`

## 本地单独运行

```bash
cd frontend
npm install
npm run dev
```

默认打开 `http://localhost:5173`。API地址通过环境变量配置（见 `.env.development`）：

| 环境变量 | 默认值 |
|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080`（gateway-service地址） |

## 目录结构

```
src/
├── api/client.js        # fetch封装：登录、CRUD请求、搜索请求、token存取
├── pages/LoginPage.jsx
├── pages/PolicyListPage.jsx
├── pages/SearchPage.jsx
└── App.jsx               # 未登录显示登录页；登录后顶部tab切换列表/搜索两个页面
```

## 已知的CORS坑（写给自己看的，别踩第二次）

`gateway-service` 一开始只在 `spring.cloud.gateway.globalcors` 里配了CORS，结果 `/auth/login`
一直报CORS错误——因为 `globalcors` 只对**被网关代理的路由**生效，对网关自己本地的
`@RestController`（比如 `/auth/login`）和 `/actuator/**` 完全不起作用，这两类请求走的是
普通 WebFlux `DispatcherHandler`，根本不经过网关路由过滤器链。后来改成在
`gateway-service` 里单独注册一个作用于 `"/**"` 的 `CorsWebFilter`（见
`gateway-service/src/main/java/com/toysystem/gateway/config/CorsConfig.java`）才彻底解决。

这个坑只有真的用浏览器跑一遍登录流程才会暴露——用curl测`/auth/login`和`/api/policies`
都是200/401，看不出CORS问题（curl不发`Origin`头，也不做preflight）。

## 搜索框输入中日文，一定要走 URLSearchParams（不是字符串拼接）

`api/client.js`里的`searchPolicies(q)`用`URLSearchParams`来拼查询字符串，不是直接
`` `?q=${q}` `` 拼接。原因：手动拼接会把"検索"这类多字节UTF-8字符原样塞进URL，
而`gateway-service`底层是Netty（Reactor Netty的HTTP请求行解析比较严格），收到没有
percent-encode的非ASCII字符会直接判定请求行不合法、返回 `400 Bad Request`——这一步
甚至发生在JWT鉴权过滤器之前，所以404/401都不会看到，直接了当的问题。`URLSearchParams`
会自动把这些字符正确编码成`%E6%A4%9C%E7%B4%A2`这种形式，浏览器的`fetch`本来也是这么处理的，
只是这里要确保自己拼URL的地方别绕过这个机制。
