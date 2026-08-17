# Kubernetes 命令大全 / kubectl コマンド完全リファレンス / K8s Command Reference

> 基于你正在搭建的 k3s homelab（gateway/policy/notification/search/frontend 五微服务）整理，覆盖从 P1 到 P11 全流程会用到的命令。按"使用场景"分类，而不是死记硬背 API 分类。

---

## 0. kubectl 基础与配置

```bash
# 查看当前上下文/集群配置
kubectl config view
kubectl config current-context
kubectl config get-contexts
kubectl config use-context <context-name>

# 查看 kubectl 客户端与服务端版本
kubectl version

# 集群基本信息
kubectl cluster-info
kubectl cluster-info dump    # 更详细的诊断信息（内容很多，慎用）

# 常用 alias（建议加进 .bashrc / .zshrc）
alias k=kubectl
source <(kubectl completion bash)   # 命令自动补全
```

**k3s 专属：**
```bash
# k3s 默认 kubeconfig 位置
sudo cat /etc/rancher/k3s/k3s.yaml

# 把 k3s 的 kubeconfig 合并到本地 ~/.kube/config，这样不用每次都加 --kubeconfig
sudo k3s kubectl config view --raw > ~/.kube/config
chmod 600 ~/.kube/config

# 查看 k3s 服务状态（k3s 本身是个 systemd 服务）
sudo systemctl status k3s
sudo journalctl -u k3s -f    # 实时看 k3s 自己的日志（排查集群本身问题用）
```

---

## 1. 资源查看类（get / describe）—— 排障 90% 靠这两个

```bash
# 基础查看
kubectl get pods
kubectl get pods -A                      # 所有 namespace（等价于 --all-namespaces）
kubectl get pods -n <namespace>
kubectl get pods -o wide                 # 多显示 Node、Pod IP 等信息
kubectl get pods --show-labels           # 显示标签，排查 selector 匹配问题时常用
kubectl get pods -l app=hello-demo       # 按 label 筛选
kubectl get pods --watch                 # 实时监控变化（Ctrl+C 退出）
kubectl get pods --sort-by=.metadata.creationTimestamp

# 一次看多种资源
kubectl get pods,svc,deploy,ingress -n <namespace>
kubectl get all -n <namespace>           # 该 namespace 下大部分核心资源

# describe：排障时最常用，比 get 信息详细得多，末尾的 Events 尤其关键
kubectl describe pod <pod-name>
kubectl describe svc <svc-name>
kubectl describe node <node-name>
kubectl describe deployment <deploy-name>
kubectl describe ingress <ingress-name>

# 输出格式控制
kubectl get pod <pod-name> -o yaml       # 完整 YAML（排障/学习字段结构必看）
kubectl get pod <pod-name> -o json
kubectl get pod <pod-name> -o jsonpath='{.status.podIP}'   # 只取某个字段
kubectl get pods -o custom-columns='NAME:.metadata.name,STATUS:.status.phase'

# 各类核心资源速查
kubectl get nodes -o wide
kubectl get namespaces
kubectl get deployments -n <ns>
kubectl get replicasets -n <ns>
kubectl get statefulsets -n <ns>
kubectl get daemonsets -n <ns>
kubectl get services -n <ns>
kubectl get endpoints -n <ns>            # Service 背后实际映射的 Pod IP 列表，排障利器
kubectl get ingress -n <ns>
kubectl get configmaps -n <ns>
kubectl get secrets -n <ns>
kubectl get pv                           # PersistentVolume（集群级，无 namespace）
kubectl get pvc -n <ns>                  # PersistentVolumeClaim
kubectl get storageclass
kubectl get events -n <ns> --sort-by='.lastTimestamp'   # 该 namespace 最近发生了什么
kubectl get crd                          # 查看有哪些自定义资源（比如 k3s 的 helmchart）
kubectl get helmchart -n kube-system     # 你删 Traefik 那次用过的资源类型
```

---

## 2. 日志与进容器排障

```bash
# 日志
kubectl logs <pod-name>
kubectl logs <pod-name> -n <namespace>
kubectl logs <pod-name> -c <container-name>     # Pod 里有多个容器时必须指定
kubectl logs <pod-name> -f                      # 实时跟踪（follow）
kubectl logs <pod-name> --previous              # 看上一次容器崩溃前的日志（排查 CrashLoopBackOff 神器）
kubectl logs <pod-name> --tail=100
kubectl logs <pod-name> --since=1h
kubectl logs -l app=hello-demo --all-containers  # 按标签批量看多个 Pod 的日志

# 进容器
kubectl exec -it <pod-name> -- /bin/sh
kubectl exec -it <pod-name> -c <container-name> -- /bin/bash
kubectl exec <pod-name> -- env                  # 不进 shell，直接执行单条命令看结果

# 端口转发（本地调试很常用，不用改 Service 类型就能临时访问）
kubectl port-forward pod/<pod-name> 8080:80
kubectl port-forward svc/<svc-name> 8080:80
kubectl port-forward deployment/<deploy-name> 8080:80

# 临时起一个调试用 Pod（集群内部网络连通性测试神器）
kubectl run tmp-shell --rm -it --image=busybox -- /bin/sh
kubectl run tmp-curl --rm -it --image=curlimages/curl -- sh
# 进去之后可以直接测试:
#   nslookup hello-demo-svc         → 验证 CoreDNS 解析
#   curl hello-demo-svc:80          → 验证 Service 转发
#   wget -O- <pod-ip>:80            → 验证直连 Pod IP

# 拷贝文件进出容器
kubectl cp <pod-name>:/path/in/pod ./local-path
kubectl cp ./local-file <pod-name>:/path/in/pod
```

---

## 3. 创建 / 应用 / 修改资源

```bash
# 声明式（生产推荐，YAML 存档，可追溯）
kubectl apply -f deployment.yaml
kubectl apply -f ./manifests/              # 整个目录下所有 yaml
kubectl apply -f https://raw.githubusercontent.com/.../xxx.yaml
kubectl apply -f deployment.yaml --dry-run=client -o yaml   # 先看会生成什么，不真的执行
kubectl diff -f deployment.yaml            # 对比集群现状和 yaml 文件的差异

# 命令式（快速测试用，不建议生产依赖）
kubectl create deployment hello --image=nginxdemos/hello --replicas=2
kubectl create namespace <ns-name>
kubectl create configmap <name> --from-file=config.txt
kubectl create configmap <name> --from-literal=key1=value1
kubectl create secret generic <name> --from-literal=password=xxx
kubectl expose deployment hello --port=80 --target-port=80 --type=ClusterIP

# 编辑（会打开默认编辑器，改完保存即生效）
kubectl edit deployment <deploy-name>
kubectl edit svc <svc-name>

# 打补丁（只改某个字段，不用改整个 yaml）
kubectl patch deployment <deploy-name> -p '{"spec":{"replicas":3}}'
kubectl patch svc <svc-name> -p '{"spec":{"type":"NodePort"}}'

# 打标签 / 注解
kubectl label pod <pod-name> env=prod
kubectl label pod <pod-name> env-              # 减号 = 删除该标签
kubectl annotate pod <pod-name> note="test"

# 删除
kubectl delete pod <pod-name>
kubectl delete -f deployment.yaml
kubectl delete deployment <deploy-name>
kubectl delete pods --all -n <namespace>
kubectl delete pods -l app=hello-demo
kubectl delete pod <pod-name> --grace-period=0 --force   # 强制立即删（Pod 卡 Terminating 时用）
```

---

## 4. Deployment / 扩缩容 / 滚动更新

```bash
# 扩缩容
kubectl scale deployment <deploy-name> --replicas=5
kubectl autoscale deployment <deploy-name> --min=2 --max=10 --cpu-percent=80   # 需要 metrics-server

# 滚动更新
kubectl set image deployment/<deploy-name> <container-name>=<image>:<new-tag>
kubectl rollout status deployment/<deploy-name>       # 实时看更新进度
kubectl rollout history deployment/<deploy-name>      # 看历史版本
kubectl rollout history deployment/<deploy-name> --revision=2
kubectl rollout undo deployment/<deploy-name>         # 回滚到上一个版本
kubectl rollout undo deployment/<deploy-name> --to-revision=2
kubectl rollout restart deployment/<deploy-name>      # 不改配置，强制重建所有 Pod（常用于刷新 Secret/ConfigMap 后生效）
kubectl rollout pause deployment/<deploy-name>
kubectl rollout resume deployment/<deploy-name>
```

---

## 5. Service / Ingress / 网络排障（你这几天问的重点）

```bash
# Service
kubectl get svc -A
kubectl get endpoints <svc-name>          # 关键！看这个 Service 背后实际关联了哪些 Pod IP
                                           # 如果这里是空的，selector 大概率没匹配上任何 Pod

kubectl expose deployment <deploy-name> --type=NodePort --port=80

# Ingress
kubectl get ingress -A
kubectl describe ingress <ingress-name>   # 看它认领的是哪个 IngressClass、路由到哪个 Service

# IngressClass（决定这条 Ingress 归哪个 controller 管）
kubectl get ingressclass

# DNS 排障（CoreDNS 相关）
kubectl get pods -n kube-system | grep coredns
kubectl get svc -n kube-system | grep kube-dns
kubectl exec -it <pod-name> -- nslookup <svc-name>
kubectl exec -it <pod-name> -- cat /etc/resolv.conf

# kube-proxy 排障
kubectl get pods -n kube-system | grep kube-proxy   # k3s 可能查不到（内嵌实现）
kubectl logs -n kube-system <kube-proxy-pod-name>

# 网络策略（NetworkPolicy，限制 Pod 间访问）
kubectl get networkpolicy -n <ns>
kubectl describe networkpolicy <name> -n <ns>

# 端口检查思路（组合命令，排障 Ingress 502/504 常用套路）
kubectl get svc -n ingress-nginx                        # 先确认 controller 的 NodePort/LB 端口
kubectl get pods -n ingress-nginx -o wide                # 确认 controller Pod 具体在哪个 Node
kubectl logs -n ingress-nginx <nginx-controller-pod>      # 看 controller 自己的日志，通常能看到转发失败原因
```

---

## 6. ConfigMap / Secret（配置与敏感信息）

```bash
kubectl create configmap app-config --from-file=./config/
kubectl create configmap app-config --from-env-file=.env
kubectl get configmap <name> -o yaml
kubectl describe configmap <name>

kubectl create secret generic db-secret \
  --from-literal=username=admin \
  --from-literal=password='xxx'
kubectl create secret docker-registry regcred \
  --docker-server=<registry> --docker-username=<u> --docker-password=<p>
kubectl get secret <name> -o jsonpath='{.data.password}' | base64 -d   # 解码看明文（本地调试用，注意安全）
```

---

## 7. StatefulSet / 存储（你学过的部分，Kafka/ES 这类有状态服务会用到）

```bash
kubectl get statefulset -n <ns>
kubectl scale statefulset <name> --replicas=3
kubectl delete statefulset <name> --cascade=orphan    # 只删 StatefulSet 定义，保留底下的 Pod（谨慎用）

kubectl get pv
kubectl get pvc -n <ns>
kubectl describe pvc <pvc-name>
kubectl get storageclass
```

---

## 8. Node 管理与调度

```bash
kubectl get nodes
kubectl get nodes -o wide
kubectl describe node <node-name>          # 看资源余量（Allocatable）、已调度的 Pod、Taints 等

kubectl top node                            # 需要 metrics-server，看实时资源占用
kubectl top pod
kubectl top pod -n <ns> --sort-by=cpu

# 驱逐 / 维护模式（做节点维护前的标准操作）
kubectl cordon <node-name>                  # 标记不可调度，但不影响已在跑的 Pod
kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data   # 把 Pod 都撵走
kubectl uncordon <node-name>                # 维护完恢复调度

# Taint / Toleration（控制哪些 Pod 能调度到哪些 Node）
kubectl taint nodes <node-name> key=value:NoSchedule
kubectl taint nodes <node-name> key=value:NoSchedule-    # 减号 = 移除
```

---

## 9. RBAC / 权限（配合你在做的 SLI-USR001 RBAC 知识）

```bash
kubectl get serviceaccount -n <ns>
kubectl get role,rolebinding -n <ns>
kubectl get clusterrole,clusterrolebinding

kubectl create serviceaccount <name> -n <ns>
kubectl create role <role-name> --verb=get,list,watch --resource=pods -n <ns>
kubectl create rolebinding <name> --role=<role-name> --serviceaccount=<ns>:<sa-name> -n <ns>

# 权限自查（排查"为什么我操作不了这个资源"很实用）
kubectl auth can-i create deployments -n <ns>
kubectl auth can-i delete pods --as=system:serviceaccount:<ns>:<sa-name>
kubectl auth can-i --list -n <ns>
```

---

## 10. Helm 常用命令

```bash
helm repo add <repo-name> <repo-url>
helm repo update
helm search repo <keyword>

helm install <release-name> <repo>/<chart> -n <ns> --create-namespace
helm install <release-name> <repo>/<chart> -f values.yaml
helm install <release-name> <repo>/<chart> --set key=value

helm list -A                                # 看所有已安装的 release
helm status <release-name> -n <ns>
helm get values <release-name> -n <ns>      # 看实际生效的 values
helm get manifest <release-name> -n <ns>    # 看它实际渲染出的所有 K8s YAML

helm upgrade <release-name> <repo>/<chart> -f values.yaml
helm upgrade --install <release-name> <repo>/<chart>   # 不存在则装，存在则升级，脚本里很常用
helm rollback <release-name> <revision-number>
helm history <release-name>

helm uninstall <release-name> -n <ns>

# k3s 特有：处理内置的 HelmChart CRD（不是普通 helm 命令）
kubectl get helmchart -A
kubectl delete helmchart <name> -n kube-system
```

---

## 11. k3s 专属命令

```bash
# 集群 token（多节点加入用）
sudo cat /var/lib/rancher/k3s/server/node-token

# 添加 Worker 节点（在 Worker 机器上执行）
curl -sfL https://get.k3s.io | K3S_URL=https://<master-ip>:6443 K3S_TOKEN=<token> sh -

# 卸载 k3s（server 和 agent 脚本不同）
sudo /usr/local/bin/k3s-uninstall.sh        # server 节点
sudo /usr/local/bin/k3s-agent-uninstall.sh  # agent/worker 节点

# k3s 自带的 ctr / crictl（容器运行时层面排障，比 kubectl 更底层）
sudo k3s crictl ps                          # 看当前节点上实际的容器（不经过 API Server）
sudo k3s crictl images
sudo k3s ctr images list
```

---

## 12. 调试思路速查表（遇到问题按这个顺序查）

```bash
# Pod 起不来 / Pending / CrashLoopBackOff
kubectl get pods -o wide                    # 先看状态和所在 Node
kubectl describe pod <pod-name>              # 看 Events，90% 问题这里能看出线索
kubectl logs <pod-name>
kubectl logs <pod-name> --previous           # 上次崩溃日志

# Service 访问不通
kubectl get endpoints <svc-name>             # 空的话 = selector 没匹配到 Pod，检查 label
kubectl describe svc <svc-name>
kubectl run tmp --rm -it --image=busybox -- nslookup <svc-name>   # 验证 DNS
kubectl run tmp --rm -it --image=curlimages/curl -- curl <svc-name>:<port>  # 验证转发

# Ingress 502/504/无法访问
kubectl describe ingress <ingress-name>      # 确认路由规则、后端 Service 名字有没有写错
kubectl get svc -n ingress-nginx             # 确认 NodePort/LB 端口
kubectl logs -n ingress-nginx <controller-pod-name>
kubectl get endpoints <backend-svc-name>     # 确认后端 Service 真的关联到了 Pod

# 资源不够 / 调度失败
kubectl describe node <node-name>            # 看 Allocatable vs Allocated
kubectl top node
kubectl get events -A --sort-by='.lastTimestamp' | tail -30
```

---

*生成于 2026-08-15，配合你的 P1-P11 k3s homelab 进度整理，建议存到你的 homelab 文档仓库里持续补充。*
