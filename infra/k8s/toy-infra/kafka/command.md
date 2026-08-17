## Step 1：确认当前node名字
kubectl get nodes -o wide

## Step 2：在宿主机上建数据目录
sudo mkdir -p /data/kafka-0
sudo chmod 777 /data/kafka-0
（先用777图省事，跑通后可以收紧权限，不是这一步的重点）

## Step 3：创建一个新的StorageClass（专门给local PV用）
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/local-storage-sc.yaml

## Step 4：手动创建PV（把node名字换成Step 1看到的）
kubectl apply -f /home/ziqiao/Documents/homelab-toy-system/infra/k8s/toy-infra/kafka/kafka-pv-0.yaml
kubectl get pv --show-labels


## Step 5：加Bitnami仓库，准备装Kafka
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

## Step 6：装Kafka（先1个broker，单节点没必要开3个）
helm install kafka bitnami/kafka \
  -n toy-infra \
  --set replicaCount=1 \
  --set persistence.storageClass=local-storage \
  --set persistence.size=8Gi \
  --set persistence.selector.matchLabels.app=kafka