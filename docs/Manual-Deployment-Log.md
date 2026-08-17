## 2026-08-17：宿主机WiFi静态IP配置

- 问题背景：JCOM/Kaon路由器管理界面为只读（无法设置DHCP静态绑定），
  转为在Ubuntu Desktop侧（NetworkManager管理）固定IP
- 网卡：wlp4s0（WiFi）
- 连接名：JCOM_VETV
- 网关：192.168.40.1
- 固定IP：192.168.40.23/24

命令：
sudo nmcli connection modify "JCOM_VETV" \
  ipv4.addresses 192.168.40.23/24 \
  ipv4.gateway 192.168.40.1 \
  ipv4.dns "192.168.40.1 8.8.8.8" \
  ipv4.method manual
sudo nmcli connection down "JCOM_VETV" && sudo nmcli connection up "JCOM_VETV"

验证：dynamic标记消失，确认为静态生效
  修改前: inet 192.168.40.23/24 ... scope global dynamic noprefixroute wlp4s0
  修改后: inet 192.168.40.23/24 ... scope global noprefixroute wlp4s0

后续影响：k3s的 /etc/rancher/k3s/registries.yaml 中registry mirror地址
  可安心写死为 192.168.40.23:5000，不会因DHCP续租漂移


## Namespace 建好
bash
kubectl create namespace toy-system
kubectl create namespace toy-infra

## 解决镜像分发——起一个临时私有仓库
docker run -d -p 5000:5000 --restart=unless-stopped --name local-registry registry:2
实际在infra/docker-compose.dev.yml中加了registry片段

