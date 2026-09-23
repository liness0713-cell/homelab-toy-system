grafana pwd
admin
ZWxhc3RpYw

装 Prometheus 本体，用 kube-prometheus-stack 这个 Helm chart 一次性搞定 Prometheus+Grafana+Alertmanager

## Step 1：Helm 装 kube-prometheus-stack 到 toy-infra
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update

# grafana换成真实的Grafana管理员密码
helm install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  -n toy-infra \
  -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/Prometheus-Grafana-Alertmanager/kube-prometheus-stack-values.yaml \
  --set grafana.adminPassword="ZWxhc3RpYw"

# 返回
NAME: kube-prometheus-stack
LAST DEPLOYED: Wed Sep 23 11:44:59 2026
NAMESPACE: toy-infra
STATUS: deployed
REVISION: 1
TEST SUITE: None
NOTES:
kube-prometheus-stack has been installed. Check its status by running:
  kubectl --namespace toy-infra get pods -l "release=kube-prometheus-stack"

Get Grafana 'admin' user password by running:

  kubectl --namespace toy-infra get secrets kube-prometheus-stack-grafana -o jsonpath="{.data.admin-password}" | base64 -d ; echo

Access Grafana local instance:

  export POD_NAME=$(kubectl --namespace toy-infra get pod -l "app.kubernetes.io/name=grafana,app.kubernetes.io/instance=kube-prometheus-stack" -oname)
  kubectl --namespace toy-infra port-forward $POD_NAME 3000

Get your grafana admin user password by running:

  kubectl get secret --namespace toy-infra -l app.kubernetes.io/component=admin-secret -o jsonpath="{.items[0].data.admin-password}" | base64 --decode ; echo


Visit https://github.com/prometheus-operator/kube-prometheus for instructions on how to create & configure Alertmanager and Prometheus instances using the Operator.


装完确认一下（这个 chart 会拉起不少 Pod，第一次跑要等一会）：
kubectl get pods -n toy-infra | grep -E "prometheus|grafana|alertmanager"


## Step 2：告诉 Prometheus 去抓谁——ServiceMonitor 是这个 chart 特有的抓取声明方式
它依赖svc或pod设置port名字，而不是port数字本身。kafka是直接看pod，其他是svc

kubectl apply -f servicemonitors-k3s.yaml
kubectl apply -f host-exporters-monitoring.yaml