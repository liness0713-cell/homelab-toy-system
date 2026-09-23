装 Prometheus 本体，用 kube-prometheus-stack 这个 Helm chart 一次性搞定 Prometheus+Grafana+Alertmanager

## Step 1：Helm 装 kube-prometheus-stack 到 toy-infra
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# grafana换成真实的Grafana管理员密码
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n toy-infra \
  -f kube-prometheus-stack-values.yaml \
  --set grafana.adminPassword="ZWxhc3RpYw"

装完确认一下（这个 chart 会拉起不少 Pod，第一次跑要等一会）：
kubectl get pods -n toy-infra | grep -E "prometheus|grafana|alertmanager"