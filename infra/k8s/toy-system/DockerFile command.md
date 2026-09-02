## 打镜像前，先执行这个算tag
TAG=$(date +%Y%m%d%H%M)-$(git rev-parse --short HEAD)
echo $TAG

## 打包推送 frontend
cd /home/ziqiao/Documents/homelab-toy-system/frontend

docker build --build-arg VITE_API_BASE_URL=https://api.homelab.local -t localhost:5000/frontend:$TAG .
这里ARG给了默认值https://api.homelab.local，所以就算你以后忘了显式传--build-arg，也不会退回到localhost:8080这种明显错误的值，是个安全网。

docker push localhost:5000/frontend:$TAG



## 打包推送 gateway-service
cd /home/ziqiao/Documents/homelab-toy-system/gateway-service

docker build -t localhost:5000/gateway-service:$TAG .

docker push localhost:5000/gateway-service:$TAG



## 打包推送 notification-service,policy-service,search-service前，先cd到项目根目录
cd /home/ziqiao/Documents/homelab-toy-system

### 打包推送 notification-service
docker build -f notification-service/Dockerfile \
  -t localhost:5000/notification-service:$TAG \
  .

docker push localhost:5000/notification-service:$TAG

### 打包推送 policy-service
docker build -f policy-service/Dockerfile \
  -t localhost:5000/policy-service:$TAG \
  .

docker push localhost:5000/policy-service:$TAG

### 打包推送 search-service
docker build -f search-service/Dockerfile \
  -t localhost:5000/search-service:$TAG \
  .

docker push localhost:5000/search-service:$TAG

### 看推送有没有成功
curl http://localhost:5000/v2/_catalog
curl http://localhost:5000/v2/notification-service/tags/list
curl http://localhost:5000/v2/policy-service/tags/list
curl http://localhost:5000/v2/search-service/tags/list
curl http://localhost:5000/v2/frontend/tags/list
curl http://localhost:5000/v2/gateway-service/tags/list

