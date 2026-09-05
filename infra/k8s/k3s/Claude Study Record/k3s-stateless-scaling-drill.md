# 三节点k3s集群 · 无状态应用伸缩演练手册
# 3ノードk3sクラスター・ステートレスアプリ スケーリング演習手引き
# 3-Node k3s Cluster · Stateless App Scaling Drill Manual

集群 / クラスター / Cluster:
- `ziqiao-asm100`（control-plane，裸机 / ベアメタル / bare metal）
- `k3s-node2`（老MacBook Pro，Multipass+QEMU）
- `k3s-node3`（老iMac，Multipass+VirtualBox）

---

## 0. 准备：部署测试用无状态应用
## 0. 準備：テスト用ステートレスアプリのデプロイ
## 0. Setup: Deploy a test stateless app

### 中文
先建一个专门的命名空间和一个3副本的nginx Deployment，作为整个演练的靶子。

```bash
kubectl create namespace scaling-drill

cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: scaling-drill
  labels:
    app: web
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
          limits:
            cpu: "250m"
            memory: "128Mi"
EOF

kubectl expose deployment web -n scaling-drill --port=80 --target-port=80 --name=web-svc
```

**命令说明**：
- `resources.requests/limits` 从一开始就写上——后面第6节的调度压力测试需要它，现在先养成习惯
- `kubectl expose` 顺手建一个ClusterIP Service，方便后面测试Pod漂移时Service还能不能正确路由

**观察点**：
```bash
kubectl get pods -n scaling-drill -o wide
```
确认3个Pod分布在哪些node上——k3s默认调度器会尽量打散，但不保证，这是后面对比`podAntiAffinity`效果的基线（baseline）。

### 日本語
まず専用(せんよう)のnamespaceと、3(さん)レプリカのnginx Deploymentを一(ひと)つ作(つく)り、これを演習(えんしゅう)全体(ぜんたい)のターゲットにします。

```bash
kubectl create namespace scaling-drill

cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: scaling-drill
  labels:
    app: web
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
          limits:
            cpu: "250m"
            memory: "128Mi"
EOF

kubectl expose deployment web -n scaling-drill --port=80 --target-port=80 --name=web-svc
```

**コマンドの説明(せつめい)**：
- `resources.requests/limits`は最初(さいしょ)から書(か)いておきます——第(だい)6節(せつ)のスケジューリング負荷(ふか)テストで必要(ひつよう)になるので、今(いま)のうちに習慣(しゅうかん)にしておきましょう
- `kubectl expose`でついでにClusterIP Serviceを作(つく)っておき、後(あと)でPodが移動(いどう)してもServiceが正(ただ)しくルーティングできるか確認(かくにん)できるようにします

**観察(かんさつ)ポイント**：
```bash
kubectl get pods -n scaling-drill -o wide
```
3(さん)つのPodがどのノードに分散(ぶんさん)しているか確認(かくにん)してください——k3sのデフォルトスケジューラーはできるだけ分散(ぶんさん)させようとしますが、保証(ほしょう)はされません。これが後(あと)で`podAntiAffinity`の効果(こうか)を比較(ひかく)する際(さい)の基準(きじゅん)(baseline)になります。

### English
First, create a dedicated namespace and a 3-replica nginx Deployment as the target for the whole drill.

```bash
kubectl create namespace scaling-drill

cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web
  namespace: scaling-drill
  labels:
    app: web
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web
  template:
    metadata:
      labels:
        app: web
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
          limits:
            cpu: "250m"
            memory: "128Mi"
EOF

kubectl expose deployment web -n scaling-drill --port=80 --target-port=80 --name=web-svc
```

**Command notes**:
- `resources.requests/limits` are set from the start — needed for the scheduling pressure test in Section 6, good habit to build now
- `kubectl expose` creates a ClusterIP Service along the way, useful later for checking whether the Service still routes correctly when pods move around

**What to observe**:
```bash
kubectl get pods -n scaling-drill -o wide
```
Check which nodes the 3 pods landed on — k3s's default scheduler tries to spread them but doesn't guarantee it. This is your baseline for comparing against `podAntiAffinity` later.

---

## 1. 基础扩缩容 replicas
## 1. 基本的なスケーリング replicas
## 1. Basic scaling with replicas

### 中文
```bash
# 扩容到6
kubectl scale deployment web -n scaling-drill --replicas=6

# 实时观察调度过程
kubectl get pods -n scaling-drill -o wide -w
```
按`Ctrl+C`退出`-w`的watch模式。

```bash
# 缩容到2
kubectl scale deployment web -n scaling-drill --replicas=2
```

**命令说明**：
- `-w`（watch）让终端持续刷新，能看到Pod从`Pending`→`ContainerCreating`→`Running`的完整生命周期，比事后`kubectl get`只看快照更有教学价值
- 缩容时k8s会选择性地"杀掉"部分Pod，具体杀哪个由ReplicaSet controller决定，不受你控制

**观察点**：
- 扩容到6时，看这3个新Pod被分到了哪些node——3个node基本是"轮着来"，不会全挤在一个node上（除非某个node资源不够）
- 缩容到2时，用`kubectl get pods -n scaling-drill -o wide`看剩下的2个Pod是否还分布在不同node上，体会一下"缩容不会破坏已有的打散状态"这个隐含规则（但也不是强保证，第3节的`podAntiAffinity`才是强保证）

### 日本語
```bash
# 6にスケールアップ
kubectl scale deployment web -n scaling-drill --replicas=6

# スケジューリング過程(かてい)をリアルタイムで観察(かんさつ)
kubectl get pods -n scaling-drill -o wide -w
```
`Ctrl+C`で`-w`のwatchモードを終了(しゅうりょう)します。

```bash
# 2にスケールダウン
kubectl scale deployment web -n scaling-drill --replicas=2
```

**コマンドの説明(せつめい)**：
- `-w`(watch)でターミナルを継続的(けいぞくてき)に更新(こうしん)させ、Podが`Pending`→`ContainerCreating`→`Running`と変化(へんか)していく全過程(ぜんかてい)を見(み)られます。あとで`kubectl get`のスナップショットだけを見(み)るより学習効果(がくしゅうこうか)が高(たか)いです
- スケールダウン時(じ)、k8sはどのPodを「削除(さくじょ)する」か選択的(せんたくてき)に決(き)めます。具体的(ぐたいてき)にどれを削除(さくじょ)するかはReplicaSetコントローラーが決(き)め、あなたの制御下(せいぎょか)にはありません

**観察(かんさつ)ポイント**：
- 6(ろく)にスケールアップした時(とき)、新(あたら)しい3(さん)つのPodがどのノードに割(わ)り当(あ)てられたか見(み)てみましょう——3(さん)つのノードにほぼ「順番(じゅんばん)に」割(わ)り当(あ)てられ、特定(とくてい)のノード一(ひと)つに集中(しゅうちゅう)することは(そのノードのリソースが不足(ふそく)していない限(かぎ)り)基本的(きほんてき)にありません
- 2(に)にスケールダウンした時(とき)、`kubectl get pods -n scaling-drill -o wide`で残(のこ)った2(ふた)つのPodが引(ひ)き続(つづ)き異(こと)なるノードに分散(ぶんさん)しているか確認(かくにん)しましょう。「スケールダウンは既存(きぞん)の分散状態(ぶんさんじょうたい)を壊(こわ)さない」という暗黙(あんもく)のルールを体感(たいかん)してください(ただしこれは強(つよ)い保証(ほしょう)ではなく、第(だい)3節(せつ)の`podAntiAffinity`こそが強(つよ)い保証(ほしょう)です)

### English
```bash
# Scale up to 6
kubectl scale deployment web -n scaling-drill --replicas=6

# Watch the scheduling process in real time
kubectl get pods -n scaling-drill -o wide -w
```
Press `Ctrl+C` to exit watch mode.

```bash
# Scale down to 2
kubectl scale deployment web -n scaling-drill --replicas=2
```

**Command notes**:
- `-w` (watch) keeps refreshing the terminal, letting you see pods go through `Pending` → `ContainerCreating` → `Running` — more instructive than a one-off `kubectl get` snapshot
- On scale-down, k8s selectively "kills" some pods — which ones get chosen is decided by the ReplicaSet controller, not something you control

**What to observe**:
- When scaling up to 6, check which nodes the 3 new pods landed on — they'll generally be spread round-robin across the 3 nodes (unless one node lacks resources)
- When scaling down to 2, use `kubectl get pods -n scaling-drill -o wide` to check whether the remaining 2 pods are still on different nodes — get a feel for the implicit rule that "scaling down doesn't undo existing spread" (though this isn't a hard guarantee — that's what `podAntiAffinity` in Section 3 provides)

---

## 2. 滚动更新与回滚 rolling update / rollback
## 2. ローリングアップデートとロールバック
## 2. Rolling update and rollback

### 中文
先把副本数恢复到3，方便观察：
```bash
kubectl scale deployment web -n scaling-drill --replicas=3
```

**触发一次滚动更新**（换镜像版本）：
```bash
kubectl set image deployment/web -n scaling-drill nginx=nginx:1.27-alpine

# 实时看滚动过程
kubectl rollout status deployment/web -n scaling-drill
```

**查看历史版本**：
```bash
kubectl rollout history deployment/web -n scaling-drill
```

**回滚到上一个版本**：
```bash
kubectl rollout undo deployment/web -n scaling-drill
```

**回滚到指定版本**（配合上面的history查到的revision号）：
```bash
kubectl rollout undo deployment/web -n scaling-drill --to-revision=1
```

**命令说明**：
- `kubectl set image`只改容器镜像，不用重新写整个yaml，日常最常用
- `rollout status`会一直阻塞在终端直到滚动更新完成或失败，适合脚本化CI流程里当"等待步骤"用
- 默认滚动更新策略是`RollingUpdate`，`maxUnavailable=25%`、`maxSurge=25%`（3副本时约等于"一次动1个"），这意味着更新过程中**服务不中断**，这是和后面"直接删Pod"最大的区别

**观察点**：
```bash
kubectl get pods -n scaling-drill -o wide -w
```
更新过程中会看到：新Pod先起来（`ContainerCreating`），等它`Running`且通过健康检查后，旧Pod才会被终止——这个"先加后减"的顺序，就是为什么滚动更新不会导致服务瞬断。可以同时开一个终端跑：
```bash
watch -n1 "kubectl get pods -n scaling-drill -o wide"
```
配合观察镜像版本变化：
```bash
kubectl get pods -n scaling-drill -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
```

### 日本語
まず観察(かんさつ)しやすいようにレプリカ数(すう)を3(さん)に戻(もど)します：
```bash
kubectl scale deployment web -n scaling-drill --replicas=3
```

**ローリングアップデートをトリガーする**(イメージバージョンを変更(へんこう))：
```bash
kubectl set image deployment/web -n scaling-drill nginx=nginx:1.27-alpine

# ローリング過程(かてい)をリアルタイムで見(み)る
kubectl rollout status deployment/web -n scaling-drill
```

**履歴(りれき)バージョンを確認(かくにん)する**：
```bash
kubectl rollout history deployment/web -n scaling-drill
```

**前(まえ)のバージョンにロールバックする**：
```bash
kubectl rollout undo deployment/web -n scaling-drill
```

**指定(してい)のバージョンにロールバックする**(上(うえ)のhistoryで調(しら)べたrevision番号(ばんごう)を使用(しよう))：
```bash
kubectl rollout undo deployment/web -n scaling-drill --to-revision=1
```

**コマンドの説明(せつめい)**：
- `kubectl set image`はコンテナイメージだけを変更(へんこう)し、yaml全体(ぜんたい)を書(か)き直(なお)す必要(ひつよう)がないため、日常的(にちじょうてき)に一番(いちばん)よく使(つか)われます
- `rollout status`はローリングアップデートが完了(かんりょう)または失敗(しっぱい)するまでターミナルをブロックし続(つづ)けるので、CIスクリプトの「待機(たいき)ステップ」として使(つか)うのに向(む)いています
- デフォルトのローリングアップデート戦略(せんりゃく)は`RollingUpdate`で、`maxUnavailable=25%`、`maxSurge=25%`(3(さん)レプリカ時(じ)はおおよそ「一度(いちど)に1(ひと)つずつ」動(うご)く計算(けいさん))です。つまり更新中(こうしんちゅう)も**サービスは中断(ちゅうだん)しません**——これが後(あと)で出(で)てくる「直接(ちょくせつ)Podを削除(さくじょ)する」との最大(さいだい)の違(ちが)いです

**観察(かんさつ)ポイント**：
```bash
kubectl get pods -n scaling-drill -o wide -w
```
更新中(こうしんちゅう)、まず新(あたら)しいPodが立(た)ち上(あ)がり(`ContainerCreating`)、それが`Running`になってヘルスチェックを通過(つうか)してから初(はじ)めて古(ふる)いPodが終了(しゅうりょう)される様子(ようす)が見(み)えます——この「先(さき)に増(ふ)やしてから減(へ)らす」順序(じゅんじょ)こそが、ローリングアップデートでサービスが瞬断(しゅんだん)しない理由(りゆう)です。別(べつ)のターミナルで同時(どうじ)に実行(じっこう)すると良(よ)いでしょう：
```bash
watch -n1 "kubectl get pods -n scaling-drill -o wide"
```
イメージバージョンの変化(へんか)も合(あ)わせて観察(かんさつ)します：
```bash
kubectl get pods -n scaling-drill -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
```

### English
First reset replicas to 3 for easier observation:
```bash
kubectl scale deployment web -n scaling-drill --replicas=3
```

**Trigger a rolling update** (change image version):
```bash
kubectl set image deployment/web -n scaling-drill nginx=nginx:1.27-alpine

# Watch the rollout live
kubectl rollout status deployment/web -n scaling-drill
```

**Check revision history**:
```bash
kubectl rollout history deployment/web -n scaling-drill
```

**Roll back to the previous revision**:
```bash
kubectl rollout undo deployment/web -n scaling-drill
```

**Roll back to a specific revision** (using the revision number from history above):
```bash
kubectl rollout undo deployment/web -n scaling-drill --to-revision=1
```

**Command notes**:
- `kubectl set image` changes only the container image without rewriting the whole yaml — the most commonly used approach day-to-day
- `rollout status` blocks the terminal until the rollout completes or fails — useful as a "wait step" in scripted CI pipelines
- The default rolling update strategy is `RollingUpdate` with `maxUnavailable=25%` and `maxSurge=25%` (roughly "one pod at a time" with 3 replicas) — meaning the service **stays uninterrupted** during the update. This is the key difference from directly deleting pods, which we'll cover later.

**What to observe**:
```bash
kubectl get pods -n scaling-drill -o wide -w
```
During the update, you'll see new pods spin up first (`ContainerCreating`), and only after one becomes `Running` and passes health checks does an old pod get terminated — this "add-before-remove" order is exactly why rolling updates don't cause a service blip. Worth running this in a second terminal simultaneously:
```bash
watch -n1 "kubectl get pods -n scaling-drill -o wide"
```
And track image version changes with:
```bash
kubectl get pods -n scaling-drill -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[0].image}{"\n"}{end}'
```

---

## 3. Pod亲和性与反亲和性 affinity / anti-affinity
## 3. Podアフィニティとアンチアフィニティ
## 3. Pod affinity and anti-affinity

### 中文
先删掉之前的Deployment，换一个带`podAntiAffinity`的版本，方便对比效果：
```bash
kubectl delete deployment web -n scaling-drill
```

```bash
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-anti
  namespace: scaling-drill
  labels:
    app: web-anti
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web-anti
  template:
    metadata:
      labels:
        app: web-anti
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - web-anti
            topologyKey: kubernetes.io/hostname
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
EOF
```

**命令说明**：
- `topologyKey: kubernetes.io/hostname`表示"以node为单位做反亲和"——每个node每次最多只能有1个匹配`app=web-anti`的Pod
- `requiredDuringSchedulingIgnoredDuringExecution`是"硬约束"：调度时必须满足，不满足就一直`Pending`；如果想要"尽量满足但不强制"，把`required`换成`preferred`（并加`weight`字段）
- 因为是硬约束、且你正好有3个node，`replicas: 4`会导致第4个Pod永远`Pending`——这是个很好的实验，下面会专门验证

**观察点**：
```bash
kubectl get pods -n scaling-drill -o wide
```
这次应该能看到**严格的一node一Pod**，不再是"大概率打散"而是"强制打散"。

**验证硬约束的边界**（故意超过node数）：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
第4个Pod会卡在`Pending`，用下面命令看具体原因：
```bash
kubectl describe pod <第4个Pod名字> -n scaling-drill
```
在`Events`里应该能看到类似`0/3 nodes are available: 3 node(s) didn't match pod anti-affinity rules`的信息——这就是"硬约束导致调度失败"的第一手证据。看完记得缩回3：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

### 日本語
まず前回(ぜんかい)のDeploymentを削除(さくじょ)し、`podAntiAffinity`付(つ)きのバージョンに差(さ)し替(か)えて効果(こうか)を比較(ひかく)できるようにします：
```bash
kubectl delete deployment web -n scaling-drill
```

```bash
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-anti
  namespace: scaling-drill
  labels:
    app: web-anti
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web-anti
  template:
    metadata:
      labels:
        app: web-anti
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - web-anti
            topologyKey: kubernetes.io/hostname
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
EOF
```

**コマンドの説明(せつめい)**：
- `topologyKey: kubernetes.io/hostname`は「ノード単位(たんい)でアンチアフィニティを適用(てきよう)する」ことを意味(いみ)します——各(かく)ノードには`app=web-anti`にマッチするPodが最大(さいだい)1(いち)つまでしか置(お)けません
- `requiredDuringSchedulingIgnoredDuringExecution`は「ハード制約(せいやく)」です：スケジューリング時(じ)に必(かなら)ず満(み)たさなければならず、満(み)たせない場合(ばあい)はずっと`Pending`のままになります。「できるだけ満(み)たすが強制(きょうせい)しない」場合(ばあい)は`required`を`preferred`に変(か)え(`weight`フィールドも追加(ついか))ます
- ハード制約(せいやく)で、かつちょうど3(さん)ノードしかないため、`replicas: 4`にすると4(よん)つ目(め)のPodは永遠(えいえん)に`Pending`のままになります——これは良(よ)い実験(じっけん)なので、下(した)で専用(せんよう)に検証(けんしょう)します

**観察(かんさつ)ポイント**：
```bash
kubectl get pods -n scaling-drill -o wide
```
今回(こんかい)は**厳密(げんみつ)に1(いち)ノード1(いち)Pod**になっているはずです。「だいたい分散(ぶんさん)される」ではなく「強制的(きょうせいてき)に分散(ぶんさん)される」ことが確認(かくにん)できます。

**ハード制約(せいやく)の境界(きょうかい)を検証(けんしょう)する**(わざとノード数(すう)を超(こ)える)：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
4(よん)つ目(め)のPodは`Pending`のままになります。具体的(ぐたいてき)な原因(げんいん)は以下(いか)で確認(かくにん)できます：
```bash
kubectl describe pod <4つ目のPod名> -n scaling-drill
```
`Events`欄(らん)に`0/3 nodes are available: 3 node(s) didn't match pod anti-affinity rules`のようなメッセージが表示(ひょうじ)されるはずです——これが「ハード制約(せいやく)によってスケジューリングが失敗(しっぱい)する」ことの直接的(ちょくせつてき)な証拠(しょうこ)です。確認(かくにん)が終(お)わったら3(さん)に戻(もど)しておきましょう：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

### English
First delete the previous Deployment and replace it with a version that has `podAntiAffinity`, so we can compare the effect:
```bash
kubectl delete deployment web -n scaling-drill
```

```bash
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: web-anti
  namespace: scaling-drill
  labels:
    app: web-anti
spec:
  replicas: 3
  selector:
    matchLabels:
      app: web-anti
  template:
    metadata:
      labels:
        app: web-anti
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values:
                - web-anti
            topologyKey: kubernetes.io/hostname
      containers:
      - name: nginx
        image: nginx:alpine
        resources:
          requests:
            cpu: "100m"
            memory: "64Mi"
EOF
```

**Command notes**:
- `topologyKey: kubernetes.io/hostname` means "anti-affinity at the node level" — each node can host at most 1 pod matching `app=web-anti`
- `requiredDuringSchedulingIgnoredDuringExecution` is a "hard constraint": it must be satisfied at scheduling time, or the pod stays `Pending` indefinitely. For "best-effort but not mandatory," swap `required` for `preferred` (with a `weight` field added)
- Since this is a hard constraint and you have exactly 3 nodes, setting `replicas: 4` will leave the 4th pod permanently `Pending` — a great experiment, which we'll verify explicitly below

**What to observe**:
```bash
kubectl get pods -n scaling-drill -o wide
```
This time you should see **strictly one pod per node** — not "usually spread" but "forcibly spread."

**Verify the hard constraint's limit** (deliberately exceed node count):
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
The 4th pod will be stuck `Pending`. See the exact reason with:
```bash
kubectl describe pod <4th-pod-name> -n scaling-drill
```
Under `Events` you should see something like `0/3 nodes are available: 3 node(s) didn't match pod anti-affinity rules` — direct evidence of "hard constraint causing scheduling failure." Once confirmed, scale back to 3:
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

---

## 4. 节点选择器与污点容忍 nodeSelector / taints & tolerations
## 4. ノードセレクターとtaint/toleration
## 4. nodeSelector and taints/tolerations

### 中文
**先给node2打个标签**，模拟"这个node专门跑某类应用"的场景：
```bash
kubectl label node k3s-node2 hardware=low-power
```

**用nodeSelector把Pod强制固定到打了标签的node**：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: pinned-pod
  namespace: scaling-drill
spec:
  nodeSelector:
    hardware: low-power
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod pinned-pod -n scaling-drill -o wide
```
应该稳定落在`k3s-node2`上。

**给node3打一个taint**，模拟"这个node有特殊限制，默认不接受Pod"：
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule
```

**验证taint生效**——起一个普通Pod，观察它是否会避开node3：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=6
kubectl get pods -n scaling-drill -o wide
```
应该发现新Pod只分布在`ziqiao-asm100`和`k3s-node2`上，`k3s-node3`不再接收新Pod（哪怕它反亲和规则原本允许）。

**用toleration让特定Pod能"容忍"这个taint、被调度到node3上**：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: tolerant-pod
  namespace: scaling-drill
spec:
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "lowpower-testing"
    effect: "NoSchedule"
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod tolerant-pod -n scaling-drill -o wide
```

**命令说明**：
- `nodeSelector`是"简单粗暴版亲和性"，只支持精确匹配标签，没有affinity那么灵活（不支持`In`/`NotIn`这类操作符），但语法简单,常用于快速固定
- `taint`是"从node一侧拒绝"，`toleration`是"从Pod一侧声明我能接受这个拒绝"——两者要配对使用，taint不删,普通Pod永远进不去这个node
- `NoSchedule`只影响新调度，已经在node3上跑着的旧Pod不会被驱逐；如果想要"连已有Pod也赶走"，taint的effect要改成`NoExecute`

**观察点**：
```bash
kubectl describe node k3s-node3 | grep -A2 Taints
```
确认taint确实生效。演练完成后记得清理，否则以后忘记这里有taint,会导致新Pod莫名其妙调度不到node3上：
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule-
kubectl label node k3s-node2 hardware-
kubectl delete pod pinned-pod tolerant-pod -n scaling-drill
```
（注意`taint`和`label`删除命令末尾的`-`号,这是kubectl的减号语法,代表"移除"）

### 日本語
**まずnode2にラベルを付(つ)ける**、「このノードは特定(とくてい)の種類(しゅるい)のアプリ専用(せんよう)」というシナリオをシミュレートします：
```bash
kubectl label node k3s-node2 hardware=low-power
```

**nodeSelectorでPodをそのラベル付(つ)きノードに強制固定(きょうせいこてい)する**：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: pinned-pod
  namespace: scaling-drill
spec:
  nodeSelector:
    hardware: low-power
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod pinned-pod -n scaling-drill -o wide
```
安定的(あんていてき)に`k3s-node2`に配置(はいち)されるはずです。

**node3にtaintを付(つ)ける**、「このノードには特別(とくべつ)な制限(せいげん)があり、デフォルトではPodを受(う)け付(つ)けない」状況(じょうきょう)をシミュレートします：
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule
```

**taintが機能(きのう)しているか検証(けんしょう)する**——通常(つうじょう)のPodを起動(きどう)し、node3を避(さ)けるか観察(かんさつ)します：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=6
kubectl get pods -n scaling-drill -o wide
```
新(あたら)しいPodは`ziqiao-asm100`と`k3s-node2`にしか配置(はいち)されず、`k3s-node3`は新(あたら)しいPodを受(う)け付(つ)けなくなっているはずです(アンチアフィニティルール上(じょう)は本来(ほんらい)許可(きょか)されていても)。

**tolerationで特定(とくてい)のPodがこのtaintを「容認(ようにん)」し、node3にスケジュールされるようにする**：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: tolerant-pod
  namespace: scaling-drill
spec:
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "lowpower-testing"
    effect: "NoSchedule"
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod tolerant-pod -n scaling-drill -o wide
```

**コマンドの説明(せつめい)**：
- `nodeSelector`は「シンプルなアフィニティ」で、ラベルの完全一致(かんぜんいっち)のみサポートし、affinityほど柔軟(じゅうなん)ではありません(`In`/`NotIn`のような演算子(えんざんし)はサポートされません)が、構文(こうぶん)がシンプルなので手早(てばや)く固定(こてい)したい時(とき)によく使(つか)われます
- `taint`は「ノード側(がわ)からの拒否(きょひ)」、`toleration`は「Pod側(がわ)からその拒否(きょひ)を受(う)け入(い)れられると宣言(せんげん)すること」——両方(りょうほう)セットで使(つか)う必要(ひつよう)があり、taintを削除(さくじょ)しない限(かぎ)り、通常(つうじょう)のPodは永遠(えいえん)にこのノードに入(はい)れません
- `NoSchedule`は新規(しんき)スケジューリングにのみ影響(えいきょう)し、すでにnode3で動(うご)いている既存(きぞん)のPodは追(お)い出(だ)されません。「既存(きぞん)のPodも追(お)い出(だ)したい」場合(ばあい)は、taintのeffectを`NoExecute`に変(か)えます

**観察(かんさつ)ポイント**：
```bash
kubectl describe node k3s-node3 | grep -A2 Taints
```
taintが実際(じっさい)に効(き)いているか確認(かくにん)します。演習(えんしゅう)が終(お)わったら忘(わす)れずに片付(かたづ)けてください。そうしないと後(あと)で「なぜかnode3に新(あたら)しいPodがスケジュールされない」という原因(げんいん)不明(ふめい)の問題(もんだい)に繋(つな)がります：
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule-
kubectl label node k3s-node2 hardware-
kubectl delete pod pinned-pod tolerant-pod -n scaling-drill
```
(`taint`と`label`削除(さくじょ)コマンドの末尾(まつび)の`-`記号(きごう)に注意(ちゅうい)——これはkubectlのマイナス構文(こうぶん)で、「削除(さくじょ)」を意味(いみ)します)

### English
**First, label node2**, simulating "this node is dedicated to a specific type of app":
```bash
kubectl label node k3s-node2 hardware=low-power
```

**Use nodeSelector to pin a pod to the labeled node**:
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: pinned-pod
  namespace: scaling-drill
spec:
  nodeSelector:
    hardware: low-power
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod pinned-pod -n scaling-drill -o wide
```
Should consistently land on `k3s-node2`.

**Taint node3**, simulating "this node has special restrictions and rejects pods by default":
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule
```

**Verify the taint takes effect** — spin up ordinary pods and observe whether they avoid node3:
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=6
kubectl get pods -n scaling-drill -o wide
```
New pods should only land on `ziqiao-asm100` and `k3s-node2` — `k3s-node3` stops accepting new pods (even though the anti-affinity rule alone would have allowed it).

**Use a toleration so a specific pod can "tolerate" this taint and get scheduled onto node3**:
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: tolerant-pod
  namespace: scaling-drill
spec:
  tolerations:
  - key: "dedicated"
    operator: "Equal"
    value: "lowpower-testing"
    effect: "NoSchedule"
  containers:
  - name: nginx
    image: nginx:alpine
EOF

kubectl get pod tolerant-pod -n scaling-drill -o wide
```

**Command notes**:
- `nodeSelector` is the "blunt version" of affinity — only exact label matches, no operators like `In`/`NotIn`, but simpler syntax, commonly used for quick pinning
- `taint` is "rejection from the node's side"; `toleration` is "the pod declaring it can accept that rejection" — the two must be paired; without removing the taint, ordinary pods can never land on that node
- `NoSchedule` only affects new scheduling — existing pods already running on node3 won't be evicted. If you want existing pods evicted too, change the taint's effect to `NoExecute`

**What to observe**:
```bash
kubectl describe node k3s-node3 | grep -A2 Taints
```
Confirm the taint is actually in effect. Remember to clean up after the drill, or you'll later run into mysterious "why won't new pods schedule onto node3" issues:
```bash
kubectl taint node k3s-node3 dedicated=lowpower-testing:NoSchedule-
kubectl label node k3s-node2 hardware-
kubectl delete pod pinned-pod tolerant-pod -n scaling-drill
```
(Note the trailing `-` on the `taint` and `label` deletion commands — that's kubectl's minus syntax, meaning "remove")

---

## 5. cordon / drain / uncordon
## 5. cordon / drain / uncordon
## 5. cordon / drain / uncordon

### 中文
先确保`web-anti`有3副本、分布在3个node上：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
kubectl get pods -n scaling-drill -o wide
```

**cordon**（标记节点不可调度，但不驱逐现有Pod）：
```bash
kubectl cordon k3s-node3
kubectl get nodes
```
`STATUS`列会显示`Ready,SchedulingDisabled`。

**验证cordon效果**——scale up，看新Pod是否会跑到node3：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
因为`web-anti`有硬反亲和,3个可用node（cordon不算被排除,只是不接受新调度）里已经各有1个旧Pod占位,新Pod会调度失败——这里会看到cordon和反亲和两个机制叠加的效果,这是个不错的复合场景。缩回3方便下一步观察：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

**drain**（真正的节点下线演练：驱逐现有Pod并阻止新调度）：
```bash
kubectl drain k3s-node3 --ignore-daemonsets --delete-emptydir-data
```

**命令说明**：
- `--ignore-daemonsets`：k3s系统组件里有些是DaemonSet（比如`kube-proxy`），它们设计上就是"每个node必须有一个"，drain不应该也不能把它们赶走，加这个参数忽略它们，否则命令会报错卡住
- `--delete-emptydir-data`：如果Pod用了`emptyDir`类型的临时卷，drain默认会因为"怕丢数据"而拒绝执行，这个参数强制允许（我们这里是测试Pod,没有重要数据,可以放心加）

**观察点**：
```bash
kubectl get pods -n scaling-drill -o wide -w
```
原本在node3上的Pod会被终止,然后**因为反亲和硬约束**,新Pod没地方可去(另外两个node已经各有1个),会卡在`Pending`——这是drain和反亲和策略冲突的真实体现,生产环境做节点维护前就该考虑这种冲突。

**uncordon**（恢复节点，让Pending的Pod能重新调度进来）：
```bash
kubectl uncordon k3s-node3
kubectl get pods -n scaling-drill -o wide -w
```
之前卡住的Pod应该很快被调度到node3上，变回3个node各1个的状态。

### 日本語
まず`web-anti`が3(さん)レプリカで、3(さん)つのノードに分散(ぶんさん)していることを確認(かくにん)します：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
kubectl get pods -n scaling-drill -o wide
```

**cordon**(ノードをスケジューリング不可(ふか)にマークするが、既存(きぞん)のPodは追(お)い出(だ)さない)：
```bash
kubectl cordon k3s-node3
kubectl get nodes
```
`STATUS`列(れつ)に`Ready,SchedulingDisabled`と表示(ひょうじ)されます。

**cordonの効果(こうか)を検証(けんしょう)する**——scale upして、新(あたら)しいPodがnode3に行(い)くか確認(かくにん)します：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
`web-anti`にはハードなアンチアフィニティがあり、3(さん)つの利用可能(りようかのう)なノード(cordonは「除外(じょがい)」ではなく「新規(しんき)スケジューリングを受(う)け付(つ)けない」だけ)にはすでにそれぞれ1(いち)つずつ古(ふる)いPodが占(し)めているため、新(あたら)しいPodはスケジューリングに失敗(しっぱい)します——ここでcordonとアンチアフィニティという二(ふた)つの仕組(しく)みが重(かさ)なった効果(こうか)が見(み)られる、良(よ)い複合(ふくごう)シナリオです。次(つぎ)の観察(かんさつ)のために3(さん)に戻(もど)します：
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

**drain**(本当(ほんとう)のノード撤去(てっきょ)演習(えんしゅう)：既存(きぞん)のPodを追(お)い出(だ)し、新規(しんき)スケジューリングも阻止(そし)する)：
```bash
kubectl drain k3s-node3 --ignore-daemonsets --delete-emptydir-data
```

**コマンドの説明(せつめい)**：
- `--ignore-daemonsets`：k3sのシステムコンポーネントの中(なか)には、`kube-proxy`のようなDaemonSetがあります。これらは設計上(せっけいじょう)「各(かく)ノードに必(かなら)ず1(いち)つ存在(そんざい)する」べきもので、drainがこれらを追(お)い出(だ)すべきでも、できるわけでもありません。このパラメータを付(つ)けて無視(むし)しないと、コマンドがエラーで止(と)まります
- `--delete-emptydir-data`：Podが`emptyDir`タイプの一時(いちじ)ボリュームを使(つか)っている場合(ばあい)、drainはデフォルトで「データ喪失(そうしつ)を恐(おそ)れて」実行(じっこう)を拒否(きょひ)します。このパラメータで強制的(きょうせいてき)に許可(きょか)します(今回(こんかい)はテスト用(よう)Podで重要(じゅうよう)なデータはないので、安心(あんしん)して付(つ)けて構(かま)いません)

**観察(かんさつ)ポイント**：
```bash
kubectl get pods -n scaling-drill -o wide -w
```
もともとnode3にあったPodが終了(しゅうりょう)され、その後(ご)**アンチアフィニティのハード制約(せいやく)のため**、新(あたら)しいPodは行(い)き場(ば)がなく(残(のこ)りの二(ふた)つのノードにはすでにそれぞれ1(いち)つずつある)、`Pending`のまま止(と)まります——これがdrainとアンチアフィニティ戦略(せんりゃく)の衝突(しょうとつ)の実際(じっさい)の姿(すがた)です。本番環境(ほんばんかんきょう)でノードメンテナンスを行(おこな)う前(まえ)に、こういった衝突(しょうとつ)を考慮(こうりょ)しておくべきです。

**uncordon**(ノードを復旧(ふっきゅう)させ、Pendingになっていたpodが再(さい)スケジュールできるようにする)：
```bash
kubectl uncordon k3s-node3
kubectl get pods -n scaling-drill -o wide -w
```
先(さき)ほど止(と)まっていたPodがすぐにnode3にスケジュールされ、3(さん)つのノードにそれぞれ1(いち)つずつという状態(じょうたい)に戻(もど)るはずです。

### English
First ensure `web-anti` has 3 replicas spread across the 3 nodes:
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
kubectl get pods -n scaling-drill -o wide
```

**cordon** (mark the node unschedulable, but don't evict existing pods):
```bash
kubectl cordon k3s-node3
kubectl get nodes
```
The `STATUS` column will show `Ready,SchedulingDisabled`.

**Verify cordon's effect** — scale up and see if new pods land on node3:
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=4
kubectl get pods -n scaling-drill -o wide
```
Since `web-anti` has hard anti-affinity, all 3 available nodes (cordon doesn't "exclude" a node, it just refuses new scheduling) already have 1 old pod each, so the new pod will fail to schedule — a nice compound scenario showing cordon and anti-affinity stacking together. Scale back to 3 for the next observation:
```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3
```

**drain** (a genuine node-decommission drill: evict existing pods and block new scheduling):
```bash
kubectl drain k3s-node3 --ignore-daemonsets --delete-emptydir-data
```

**Command notes**:
- `--ignore-daemonsets`: some of k3s's system components are DaemonSets (e.g., `kube-proxy`), designed so "one must exist per node" — drain shouldn't and can't evict them. Add this flag to skip them, or the command will error out and hang.
- `--delete-emptydir-data`: if pods use `emptyDir` ephemeral volumes, drain refuses by default out of "fear of data loss." This flag forces it through (fine here since these are test pods with no important data).

**What to observe**:
```bash
kubectl get pods -n scaling-drill -o wide -w
```
The pod originally on node3 gets terminated, and then **because of the hard anti-affinity constraint**, the new pod has nowhere to go (the other two nodes already have one each) and gets stuck `Pending` — a real demonstration of drain conflicting with anti-affinity policy. This is exactly the kind of conflict worth considering before doing node maintenance in production.

**uncordon** (restore the node so the pending pod can reschedule):
```bash
kubectl uncordon k3s-node3
kubectl get pods -n scaling-drill -o wide -w
```
The previously stuck pod should quickly get scheduled onto node3, returning to one pod per node.

---

## 6. 资源请求/限制与调度压力 resource requests/limits
## 6. リソースリクエスト/リミットとスケジューリング負荷
## 6. Resource requests/limits and scheduling pressure

### 中文
先看看每个node实际可分配的资源上限：
```bash
kubectl describe nodes | grep -A5 "Allocatable"
```

**故意申请一个超大资源需求的Pod**，模拟"资源不够、调度失败"：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: oversized-pod
  namespace: scaling-drill
spec:
  containers:
  - name: nginx
    image: nginx:alpine
    resources:
      requests:
        cpu: "100"
        memory: "500Gi"
EOF

kubectl get pod oversized-pod -n scaling-drill
kubectl describe pod oversized-pod -n scaling-drill
```

**命令说明**：
- `requests`是"预定席位"——调度器只看这个数字决定Pod能不能放到某个node上，不看`limits`
- `limits`是"运行时上限"——真正超过这个数字，容器会被OOMKilled（内存）或被限流（CPU）
- 这个Pod申请的`100`核CPU和`500Gi`内存，远超你三台机器里最强的那台（i7-8750H也就12线程/16GB），所以**永远不会被调度成功**,这是故意设计的失败场景

**观察点**：
`describe`的`Events`里应该能看到`0/3 nodes are available: 3 Insufficient cpu, 3 Insufficient memory`这类信息——跟第3节`podAntiAffinity`导致的`Pending`原因完全不同,这里能学会**用`describe`的Events区分"调度失败"的具体原因类别**（资源不足 vs 亲和性冲突 vs taint排斥）,这是排障最核心的技能。

清理：
```bash
kubectl delete pod oversized-pod -n scaling-drill
```

**更真实的资源压力测试**（把三个node的资源基本占满，观察调度器如何"挑node"）：
```bash
# 用一个apply同时起3个"半个node资源"大小的Pod，故意让调度器必须做选择
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pressure-test
  namespace: scaling-drill
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pressure-test
  template:
    metadata:
      labels:
        app: pressure-test
    spec:
      containers:
      - name: stress
        image: polinux/stress
        command: ["stress"]
        args: ["--cpu", "1", "--vm", "1", "--vm-bytes", "1500M"]
        resources:
          requests:
            cpu: "1"
            memory: "1500Mi"
EOF

kubectl get pods -n scaling-drill -o wide
```

**观察点**：
用`kubectl top nodes`（如果还没装metrics-server会提示找不到,这也提醒你P6要装的监控组件里`metrics-server`是这类命令的前提）看资源占用变化：
```bash
kubectl top nodes 2>/dev/null || echo "metrics-server尚未安装，这条命令需要它"
```
即使没有`top`，也能通过Pod调度到了哪台配置更弱的iMac(`k3s-node3`只有2核/3GB分配)上看它是否也扛住了压力,还是`Pending`——这是检验"资源分配是否精打细算"的直观场景。

清理：
```bash
kubectl delete deployment pressure-test -n scaling-drill
```

### 日本語
まず各(かく)ノードの実際(じっさい)に割(わ)り当(あ)て可能(かのう)なリソース上限(じょうげん)を確認(かくにん)します：
```bash
kubectl describe nodes | grep -A5 "Allocatable"
```

**わざと過大(かだい)なリソース要求(ようきゅう)のPodを申請(しんせい)する**、「リソース不足(ぶそく)でスケジューリング失敗(しっぱい)」をシミュレートします：
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: oversized-pod
  namespace: scaling-drill
spec:
  containers:
  - name: nginx
    image: nginx:alpine
    resources:
      requests:
        cpu: "100"
        memory: "500Gi"
EOF

kubectl get pod oversized-pod -n scaling-drill
kubectl describe pod oversized-pod -n scaling-drill
```

**コマンドの説明(せつめい)**：
- `requests`は「予約席(よやくせき)」です——スケジューラーはこの数値(すうち)だけを見(み)てPodをどのノードに置(お)けるか決(き)め、`limits`は見(み)ません
- `limits`は「実行時(じっこうじ)の上限(じょうげん)」です——実際(じっさい)にこれを超(こ)えると、コンテナはOOMKilled(メモリ)されるか、スロットリング(CPU)されます
- このPodが要求(ようきゅう)している`100`コアCPUと`500Gi`メモリは、三(さん)台(だい)の中(なか)で一番(いちばん)強(つよ)いマシン(i7-8750Hでも12スレッド/16GB)をはるかに超(こ)えているため、**永遠(えいえん)にスケジューリングは成功(せいこう)しません**——これはわざと設計(せっけい)した失敗(しっぱい)シナリオです

**観察(かんさつ)ポイント**：
`describe`の`Events`欄(らん)に`0/3 nodes are available: 3 Insufficient cpu, 3 Insufficient memory`のようなメッセージが出(で)るはずです——これは第(だい)3節(せつ)の`podAntiAffinity`による`Pending`とはまったく異(こと)なる原因(げんいん)です。ここで**`describe`のEventsを使(つか)って「スケジューリング失敗(しっぱい)」の具体的(ぐたいてき)な原因(げんいん)のカテゴリー**(リソース不足(ふそく) vs アフィニティの衝突(しょうとつ) vs taintによる拒否(きょひ))を見分(みわ)ける方法(ほうほう)を学(まな)べます。これはトラブルシューティングの最(もっと)も核心的(かくしんてき)なスキルです。

片付(かたづ)け：
```bash
kubectl delete pod oversized-pod -n scaling-drill
```

**より現実的(げんじつてき)なリソース負荷(ふか)テスト**(三(さん)つのノードのリソースをほぼ埋(う)め尽(つ)くし、スケジューラーがどう「ノードを選(えら)ぶ」か観察(かんさつ)する)：
```bash
# applyを一(ひと)つ使(つか)って「ノード半分(はんぶん)相当(そうとう)」のサイズのPodを3(さん)つ同時(どうじ)に起動(きどう)し、わざとスケジューラーに選択(せんたく)を迫(せま)る
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pressure-test
  namespace: scaling-drill
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pressure-test
  template:
    metadata:
      labels:
        app: pressure-test
    spec:
      containers:
      - name: stress
        image: polinux/stress
        command: ["stress"]
        args: ["--cpu", "1", "--vm", "1", "--vm-bytes", "1500M"]
        resources:
          requests:
            cpu: "1"
            memory: "1500Mi"
EOF

kubectl get pods -n scaling-drill -o wide
```

**観察(かんさつ)ポイント**：
`kubectl top nodes`(もしmetrics-serverがまだ入(はい)っていなければ見(み)つからないと表示(ひょうじ)されます。これはP6でインストールする監視(かんし)コンポーネントの中(なか)の`metrics-server`が、この手(て)のコマンドの前提(ぜんてい)であることを示唆(しさ)しています)でリソース使用状況(しようじょうきょう)の変化(へんか)を見(み)ます：
```bash
kubectl top nodes 2>/dev/null || echo "metrics-serverがまだインストールされていません。このコマンドにはそれが必要です"
```
`top`がなくても、性能(せいのう)の弱(よわ)いiMac(`k3s-node3`は2(に)コア/3GBの割(わ)り当(あ)てのみ)にPodがスケジュールされたかどうか、それとも`Pending`のままかを見(み)ることで、「リソース割(わ)り当(あ)てが本当(ほんとう)にきちんと計算(けいさん)されているか」を直感的(ちょっかんてき)に検証(けんしょう)できます。

片付(かたづ)け：
```bash
kubectl delete deployment pressure-test -n scaling-drill
```

### English
First check each node's actual allocatable resource limits:
```bash
kubectl describe nodes | grep -A5 "Allocatable"
```

**Deliberately request a pod with oversized resource requirements**, simulating "insufficient resources, scheduling fails":
```bash
cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Pod
metadata:
  name: oversized-pod
  namespace: scaling-drill
spec:
  containers:
  - name: nginx
    image: nginx:alpine
    resources:
      requests:
        cpu: "100"
        memory: "500Gi"
EOF

kubectl get pod oversized-pod -n scaling-drill
kubectl describe pod oversized-pod -n scaling-drill
```

**Command notes**:
- `requests` is a "reserved seat" — the scheduler only looks at this number to decide whether a pod fits on a node; it ignores `limits`
- `limits` is the "runtime ceiling" — actually exceeding this gets the container OOMKilled (memory) or throttled (CPU)
- This pod's request of `100` CPU cores and `500Gi` memory far exceeds even your strongest machine (the i7-8750H is only 12 threads/16GB), so it will **never schedule successfully** — a deliberately designed failure scenario

**What to observe**:
`describe`'s `Events` should show something like `0/3 nodes are available: 3 Insufficient cpu, 3 Insufficient memory` — completely different from the `Pending` cause in Section 3's `podAntiAffinity` case. This teaches you to **use `describe`'s Events to distinguish specific scheduling-failure categories** (insufficient resources vs. affinity conflicts vs. taint rejection) — one of the core troubleshooting skills.

Clean up:
```bash
kubectl delete pod oversized-pod -n scaling-drill
```

**A more realistic resource pressure test** (fill up roughly half of each node's resources, and watch how the scheduler "picks" nodes):
```bash
# One apply spins up 3 pods each sized "half a node's worth," deliberately forcing the scheduler to make choices
cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: pressure-test
  namespace: scaling-drill
spec:
  replicas: 3
  selector:
    matchLabels:
      app: pressure-test
  template:
    metadata:
      labels:
        app: pressure-test
    spec:
      containers:
      - name: stress
        image: polinux/stress
        command: ["stress"]
        args: ["--cpu", "1", "--vm", "1", "--vm-bytes", "1500M"]
        resources:
          requests:
            cpu: "1"
            memory: "1500Mi"
EOF

kubectl get pods -n scaling-drill -o wide
```

**What to observe**:
Use `kubectl top nodes` (if metrics-server isn't installed yet, this will error out — a hint that `metrics-server`, part of the P6 monitoring setup, is a prerequisite for this kind of command) to watch resource usage change:
```bash
kubectl top nodes 2>/dev/null || echo "metrics-server isn't installed yet — this command needs it"
```
Even without `top`, you can get a direct sense of "how tightly resources are being packed" by checking whether a pod got scheduled onto the weaker iMac (`k3s-node3`, allocated only 2 cores/3GB) or ended up `Pending`.

Clean up:
```bash
kubectl delete deployment pressure-test -n scaling-drill
```

---

## 7. PodDisruptionBudget（可选进阶）
## 7. PodDisruptionBudget(オプション・応用編(おうようへん))
## 7. PodDisruptionBudget (optional, advanced)

### 中文
这个是为drain场景加一层保护——避免"一次维护把某个服务的所有副本全部驱逐光"。

```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3

cat <<EOF | kubectl apply -f -
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: web-anti-pdb
  namespace: scaling-drill
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: web-anti
EOF
```

**命令说明**：
- `minAvailable: 2`意思是"任何时候，至少要有2个匹配这个标签的Pod保持可用"——`kubectl drain`这类"主动驱逐"操作会尊重这个约束，达不到就拒绝继续drain

**观察点**——drain一个跑着`web-anti`的node，看PDB是不是真的能拦住：
```bash
kubectl get pods -n scaling-drill -l app=web-anti -o wide
kubectl drain <当前跑着某个web-anti副本的node名> --ignore-daemonsets --delete-emptydir-data
```
如果这个node上的这1个Pod一旦被驱逐会导致可用副本数跌破2（比如只有3副本、已经因为之前实验少了1个的情况下），drain命令会**卡住并报错**，提示`Cannot evict pod as it would violate the pod's disruption budget`——这是PDB在真实生产维护场景里最核心的价值：防止人为操作意外打穿服务可用性红线。

清理：
```bash
kubectl delete pdb web-anti-pdb -n scaling-drill
kubectl uncordon <刚才drain的node>   # drain会自动cordon，记得恢复
```

### 日本語
これはdrainシナリオに保護(ほご)層(そう)を一(ひと)つ加(くわ)えるもので——「一回(いっかい)のメンテナンスであるサービスの全(すべ)てのレプリカを追(お)い出(だ)してしまう」ことを防(ふせ)ぎます。

```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3

cat <<EOF | kubectl apply -f -
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: web-anti-pdb
  namespace: scaling-drill
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: web-anti
EOF
```

**コマンドの説明(せつめい)**：
- `minAvailable: 2`は「いつでも、このラベルにマッチするPodが最低(さいてい)2(に)つは利用可能(りようかのう)な状態(じょうたい)を保(たも)つ」という意味(いみ)です——`kubectl drain`のような「能動的(のうどうてき)な追(お)い出(だ)し」操作(そうさ)はこの制約(せいやく)を尊重(そんちょう)し、満(み)たせない場合(ばあい)はdrainの継続(けいぞく)を拒否(きょひ)します

**観察(かんさつ)ポイント**——`web-anti`が動(うご)いているノードを一(ひと)つdrainし、PDBが本当(ほんとう)に阻止(そし)できるか見(み)てみましょう：
```bash
kubectl get pods -n scaling-drill -l app=web-anti -o wide
kubectl drain <web-antiのレプリカが動いているノード名> --ignore-daemonsets --delete-emptydir-data
```
このノード上(じょう)の1(いち)つのPodが追(お)い出(だ)されることで利用可能(りようかのう)なレプリカ数(すう)が2(に)を下回(したまわ)る場合(ばあい)(例(たと)えば3(さん)レプリカのうち、前回(ぜんかい)の実験(じっけん)ですでに1(いち)つ減(へ)っている状況(じょうきょう)など)、drainコマンドは**止(と)まってエラーになり**、`Cannot evict pod as it would violate the pod's disruption budget`と表示(ひょうじ)されるはずです——これがPDBの本番運用(ほんばんうんよう)における最(もっと)も核心的(かくしんてき)な価値(かち)です：人為的(じんいてき)な操作(そうさ)がサービスの可用性(かようせい)の下限(かげん)を誤(あやま)って突破(とっぱ)してしまうのを防(ふせ)ぎます。

片付(かたづ)け：
```bash
kubectl delete pdb web-anti-pdb -n scaling-drill
kubectl uncordon <さきほどdrainしたノード>   # drainは自動的(じどうてき)にcordonするので、忘(わす)れずに復旧(ふっきゅう)させること
```

### English
This adds a layer of protection to the drain scenario — preventing "one maintenance operation evicting all replicas of a service at once."

```bash
kubectl scale deployment web-anti -n scaling-drill --replicas=3

cat <<EOF | kubectl apply -f -
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: web-anti-pdb
  namespace: scaling-drill
spec:
  minAvailable: 2
  selector:
    matchLabels:
      app: web-anti
EOF
```

**Command notes**:
- `minAvailable: 2` means "at any time, at least 2 pods matching this label must remain available" — "active eviction" operations like `kubectl drain` respect this constraint and refuse to continue if it can't be met

**What to observe** — drain a node running a `web-anti` replica, and see if the PDB actually blocks it:
```bash
kubectl get pods -n scaling-drill -l app=web-anti -o wide
kubectl drain <node-running-a-web-anti-replica> --ignore-daemonsets --delete-emptydir-data
```
If evicting this one pod would drop available replicas below 2 (e.g., only 3 replicas total, with one already reduced from an earlier experiment), the drain command will **hang and error out**, showing `Cannot evict pod as it would violate the pod's disruption budget` — this is the core value of PDBs in real production maintenance: preventing a manual operation from accidentally breaking through the service's availability floor.

Clean up:
```bash
kubectl delete pdb web-anti-pdb -n scaling-drill
kubectl uncordon <the-node-you-just-drained>   # drain auto-cordons the node, remember to restore it
```

---

## 收尾清理 / 片付(かたづ)け / Final cleanup

```bash
kubectl delete namespace scaling-drill
kubectl get nodes   # 确认所有cordon都已恢复，taint都已清除
```
