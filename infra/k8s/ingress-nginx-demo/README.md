# ingress-nginx 验证demo

**这个目录不是业务服务，纯粹用来验证P2阶段"卸载Traefik、换装ingress-nginx"这一步本身是通的。**
`gateway-service`/`frontend` 目前仍然在本地跑（`mvn spring-boot:run` / `npm run dev`），
还没有部署进k3s、也没有接到这个Ingress上——那是P5阶段"所有业务服务部署到k3s"时才做的事。

## 做了什么

1. `kubectl delete helmchart traefik traefik-crd -n kube-system` 卸载k3s自带的Traefik
2. `helm install ingress-nginx ingress-nginx/ingress-nginx -n ingress-nginx --create-namespace` 装官方chart
3. 本目录部署一个最小demo（`nginxdemos/hello` 镜像）+ 一条Ingress规则，验证外部流量能通过nginx-ingress-controller打到集群内的Pod

## 部署 / 验证

```bash
kubectl apply -f infra/k8s/ingress-nginx-demo/

# 拿到 ingress-nginx-controller 的外部IP（k3s自带的ServiceLB分配）
kubectl get svc -n ingress-nginx ingress-nginx-controller

# 用 --resolve 模拟"demo.toy-system.local 解析到这个IP"，不用改本机 /etc/hosts
curl --resolve demo.toy-system.local:80:<上面拿到的EXTERNAL-IP> http://demo.toy-system.local/
```

预期能看到 `nginxdemos/hello` 返回的一段包含Pod名字/请求信息的文本。

## 已知限制：重启后Traefik会不会自己回来

这台机器上执行 Claude Code 的用户没有免密sudo权限，卸载Traefik目前只是通过
`kubectl delete helmchart` 从集群里删掉了对应资源，**没有从根上让k3s不再重新部署它**——
真正"根治"的方式是给k3s server启动参数加 `--disable=traefik`（写在
`/etc/systemd/system/k3s.service` 或 `/etc/rancher/k3s/config.yaml` 里），这需要root权限改
系统文件+重启k3s服务，留给你自己手动执行一次：

```bash
sudo mkdir -p /etc/rancher/k3s
echo "disable: traefik" | sudo tee -a /etc/rancher/k3s/config.yaml
sudo systemctl restart k3s
```

在你执行这一步之前，如果这台机器重启或者 `k3s.service` 被重启，Traefik有可能从
`/var/lib/rancher/k3s/server/manifests/traefik.yaml` 这个内置manifest重新被应用回来，
届时重新执行一次 `kubectl delete helmchart traefik traefik-crd -n kube-system` 即可。
