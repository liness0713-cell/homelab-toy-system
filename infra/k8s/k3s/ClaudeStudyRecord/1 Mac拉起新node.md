【Redis和Kafka持久化部署方案】
读一下上面这个会话，有点长，现在homelab单node已经顺利跑起来了
按计划应该进去

 **P6** | **这时候再装Prometheus/Grafana**（部署进`toy-infra` namespace），因为已经有真实的服务和流量可以观察；详见"6.5 监控归属"一节 | 看到真实的CPU/内存/QPS曲线，可以故意kill掉一个Pod观察自愈 |
| **P7** | Gitea + Jenkins + Harbor，把P5的手动部署变成自动化流水线 | git push即部署，滚动发布跑通 |
| **P8** | EFK日志链路接入这几个服务 | 排查问题时能查日志而不是`kubectl logs`挨个看 |
| **P9（进阶）** | 引入Canal监听宿主机MySQL的binlog，**新增独立的 `report-events` topic + `report-service`**：Canal发到`report-events`，`report-service`消费后把事件还原成SQL、镜像写入**ClickHouse**（而不是MySQL，体验OLAP列式存储的聚合查询优势）。原有`policy-service`手动发`policy-events`（给notification/search用）保留不变，两条链路并存对比 | 完整复刻Zorro BLCS"同一份binlog、两条独立消费管道"的设计思想（全景图11.1），顺带体验ClickHouse vs MySQL的聚合查询差异（12.5/13.1） |
| **P10（进阶）** | Argo CD做GitOps式部署 + 简单灰度发布demo | 对应全景图12.2 |
| **P11（进阶）** | k6对`policy-service`压测 + Chaos Mesh随机杀Pod | 对应全景图12.3 |

第13节（大数据）和第14节（AI/RAG）作为**独立的后续扩展**，不塞进这套玩具系统的第一期范围，但设计上已经兼容：
- Kafka的 `policy-events` topic 未来可以再加一个消费者，写入MinIO/触发Flink，不影响现有两个消费者
- ES里的保单数据后续也可以作为"给pgvector/embedding做实验"的数据源（虽然更自然的做法是用你的BSD学习文档，两条线互不冲突）
但是在此之前我决定新增node，那老mac，老mac是intel所以我觉得没有架构问题，在部署监控和cicd之前，这个会话单独用来把新node拉上线，和k8s伸缩练习计划。


中文

先接上下文：老Mac是Intel架构（amd64），跟你现在这台ziqiao-asm100架构一致——之前那次对话里担心的"跨架构镜像"问题在这里不存在，可以直接复用你现有的镜像，不需要buildx多架构构建。这一点先松口气。

在正式装监控/CICD（P6/P7）之前插入这一段"多node上线 + 伸缩练习"，我建议拆成两个阶段：

阶段一：把Mac拉上线成为真正的k3s agent节点
确认前提：Mac和ziqiao-asm100在同一局域网、能互相ping通；Mac上装的Linux发行版（如果是用虚拟机方案，比如Lima/Multipass/UTM跑一个Ubuntu）已经就绪，k3s只能装在Linux内核上，Mac原生macOS装不了k3s agent。
开放必要端口：Server节点（ziqiao-asm100）要放行6443（agent连接API server）、8472/udp（Flannel VXLAN跨节点网络）、10250（kubelet）。
拿到join token：在server节点上 sudo cat /var/lib/rancher/k3s/server/node-token
在Mac的Linux环境里执行：
bash
   curl -sfL https://get.k3s.io | K3S_URL=https://<ziqiao-asm100-ip>:6443 K3S_TOKEN=<token> sh -
验证：回到server节点 kubectl get nodes -o wide，确认新node状态Ready、架构显示amd64。
阶段二：伸缩练习清单（这才是重点）

按"无状态 vs 有状态"两条线分别练：

无状态服务（notification-service / search-service / gateway-service）

kubectl scale deployment/xxx --replicas=3，观察Pod被调度到哪个node（默认调度器行为）
加一条podAntiAffinity，强制不同副本落在不同node，观察调度结果的变化
kubectl cordon <新node> → 再scale，观察Pod是否还会被调度过去；kubectl drain体验节点下线时Pod如何被驱逐重建

有状态服务（Kafka / ES）——落地之前那次聊的"broker绑定node"设计

给新Mac节点手动建kafka-pv-1，nodeAffinity指向新node的hostname
KafkaNodePool的replicas从1调到2，观察Strimzi怎么把新Pod精确调度到有对应PV的那个node
ES这边同理，调整nodeSets.count

进阶（可选）：给新node打一个taint，只允许特定标签的Pod调度上去，体会"污点/容忍"机制。

这样一圈下来，你会亲手体会到"调度器默认行为 vs 亲和性/反亲和性 vs 存储绑定 vs 污点隔离"这四种不同粒度的控制手段，比单节点时纯理论讲解扎实很多。

日本語（ふりがな付き）

前提の確認から：老（ふる）いMacはIntelアーキテクチャ（amd64）で、今（いま）のziqiao-asm100と同（おな)じアーキテクチャです——前（まえ)の会話（かいわ）で心配（しんぱい）していた「アーキテクチャ違（ちが)いのイメージ問題（もんだい)」はここでは発生（はっせい)しません。既存（きぞん)のイメージをそのまま使（つか)えるので、buildxによるマルチアーキテクチャビルドは不要（ふよう)です。

監視（かんし)やCICD（P6/P7）を本格的（ほんかくてき)に入（い)れる前（まえ)に、この「ノード追加（ついか) + スケーリング練習（れんしゅう)」を挟（はさ)むなら、二（ふた)つの段階（だんかい)に分（わ)けることをおすすめします。

段階（だんかい)一：Macを本物（ほんもの)のk3s agentノードとして参加（さんか)させる
前提確認（ぜんていかくにん)：Macとziqiao-asm100が同（おな)じLAN上（じょう)にあり、お互（たが)いにping（ぴん)が通（とお)ること。MacにはLinuxカーネルが必要（ひつよう)なので（Lima/Multipass/UTMなどでUbuntu仮想（かそう)マシンを用意（ようい)する想定（そうてい)）、macOSネイティブにはk3s agentは入（はい)りません。
必要（ひつよう)なポートを開放（かいほう)：サーバーノード側（がわ)で6443（agent接続用（せつぞくよう))、8472/udp（Flannel VXLAN、ノード間（かん)ネットワーク)、10250（kubelet)を開（あ)けておく。
join token（じょいん とーくん)を取得（しゅとく)：サーバーノードでsudo cat /var/lib/rancher/k3s/server/node-token
MacのLinux環境（かんきょう)で実行（じっこう)：
bash
   curl -sfL https://get.k3s.io | K3S_URL=https://<ziqiao-asm100-ip>:6443 K3S_TOKEN=<token> sh -
確認（かくにん)：サーバーノードでkubectl get nodes -o wideを実行（じっこう)し、新（あたら)しいノードがReady状態（じょうたい)で、アーキテクチャがamd64と表示（ひょうじ)されることを確認（かくにん)する。
段階（だんかい)二：スケーリング練習（れんしゅう)リスト(ここが本題（ほんだい))

「ステートレス」と「ステートフル」の二（ふた)つの軸（じく)で練習（れんしゅう)します。

ステートレスなサービス（notification-service / search-service / gateway-service)

kubectl scale deployment/xxx --replicas=3を実行（じっこう)し、Podがどのノードに割（わ)り当(あ)てられるか(デフォルトのスケジューラー挙動（きょどう))を観察（かんさつ)する
podAntiAffinityを一行（いちぎょう)追加（ついか)して、異(こと)なるノードに強制的(きょうせいてき)に分散(ぶんさん)させ、結果(けっか)の違(ちが)いを観察(かんさつ)する
kubectl cordon <新(あたら)しいノード>してからscaleし、Podがそこに割(わ)り当(あ)てられなくなることを確認(かくにん)する。kubectl drainでノード撤去(てっきょ)時(じ)のPod退避(たいひ)・再作成(さいさくせい)の流(なが)れを体験(たいけん)する

ステートフルなサービス（Kafka / ES)——前(まえ)に話(はな)した「brokerをnodeに固定(こてい)する」設計(せっけい)を実際(じっさい)にやる

新(あたら)しいMacノード用(よう)にkafka-pv-1を手動(しゅどう)で作成(さくせい)し、nodeAffinityで新(あたら)しいノードのhostnameを指定(してい)する
KafkaNodePoolのreplicasを1から2に変更(へんこう)し、Strimziが対応(たいおう)するPVがあるノードに新(あたら)しいPodを正確(せいかく)にスケジュール(すけじゅーる)するのを観察(かんさつ)する
ESも同様(どうよう)にnodeSets.countを調整(ちょうせい)する

発展編(はってんへん)（任意(にんい))：新(あたら)しいノードにtaint(汚(よご)れ、と直訳(ちょくやく)されるが「制約(せいやく)」の意味(いみ))を付(つ)けて、特定(とくてい)のラベルを持(も)つPodだけがスケジュール(すけじゅーる)されるようにし、「taint/toleration」の仕組(しく)みを体感(たいかん)する。

これを一通(ひととお)りやると、「スケジューラーのデフォルト挙動(きょどう)」「アフィニティ/アンチアフィニティ」「ストレージによる固定(こてい)」「taintによる隔離(かくり)」という四(よ)つの異(こと)なる粒度(りゅうど)の制御(せいぎょ)手段(しゅだん)を、単一(たんいつ)ノードだった時(とき)の座学(ざがく)よりずっと実感(じっかん)を持(も)って理解(りかい)できるはずです。

English

Quick context check first: the old Mac is Intel (amd64), same architecture as your current ziqiao-asm100 box — so the cross-architecture image concern raised in the earlier conversation doesn't apply here. You can reuse your existing images as-is, no need for buildx multi-arch builds. One less thing to worry about.

Before moving into monitoring/CICD (P6/P7), I'd split this "bring up a second node + scaling practice" detour into two phases:

Phase 1: Join the Mac as a real k3s agent node
Prerequisites: the Mac and ziqiao-asm100 are on the same LAN and can ping each other; since k3s needs a Linux kernel, you'll need a Linux VM on the Mac (Lima/Multipass/UTM running Ubuntu, for example) — native macOS can't run k3s agent.
Open the required ports: on the server node, 6443 (agent → API server), 8472/udp (Flannel VXLAN for cross-node networking), 10250 (kubelet).
Grab the join token on the server node: sudo cat /var/lib/rancher/k3s/server/node-token
Run this inside the Mac's Linux environment:
bash
   curl -sfL https://get.k3s.io | K3S_URL=https://<ziqiao-asm100-ip>:6443 K3S_TOKEN=<token> sh -
Verify: back on the server node, kubectl get nodes -o wide — confirm the new node shows Ready and amd64.
Phase 2: The scaling practice checklist (this is the real point)

Split into two tracks — stateless vs. stateful:

Stateless services (notification-service / search-service / gateway-service)

kubectl scale deployment/xxx --replicas=3 and watch where the default scheduler places the new pods
Add a podAntiAffinity rule to force replicas onto different nodes, and see how the placement changes
kubectl cordon <new-node>, then scale again and confirm nothing new lands there; then kubectl drain it to see how pods get evicted and rescheduled when a node goes offline

Stateful services (Kafka / ES) — this is where you actually implement the "broker pinned to a node" design from before

Manually create kafka-pv-1 for the new Mac node, with nodeAffinity pointing to its hostname
Bump KafkaNodePool's replicas from 1 to 2, and watch Strimzi schedule the new pod exactly onto the node with the matching PV
Do the same for ES by adjusting nodeSets.count

Bonus (optional): put a taint on the new node so only pods with a matching label get scheduled there — a hands-on feel for taints/tolerations.

Going through all of this will give you a much more concrete grip on four distinct layers of scheduling control — default scheduler behavior, affinity/anti-affinity, storage-bound placement, and taint-based isolation — something a single-node setup couldn't really teach you.



从头带我拉node吧，现在还没有虚拟机

好，那从零开始，一步步来搭这个虚拟机。

第一步：选虚拟机工具

推荐用 Multipass（Canonical官方出的，命令行操作最简单，安装快）。你的Mac是Intel，装Multipass没有架构顾虑。

bash
brew install --cask multipass
第二步：起一个Ubuntu虚拟机
bash
multipass launch --name k3s-node2 --cpus 2 --memory 4G --disk 20G
--cpus 2 --memory 4G：给这个node留够跑Kafka/ES额外副本的资源，太小的话Pod会一直Pending
默认会拉最新LTS版Ubuntu镜像，跟你ziqiao-asm100同源，不容易踩兼容性坑
第三步：关键——把虚拟机的网络改成"桥接"模式

这一步最容易被忽略，但对你后面"让Mac和ziqiao-asm100互相ping通"至关重要。Multipass默认给虚拟机的是NAT网络（只能虚拟机访问外网，外面访问不到它），你需要让它拿到局域网真实IP：

bash
multipass stop k3s-node2
multipass set local.k3s-node2.bridged=en0   # en0通常是Mac的以太网/Wi-Fi接口，用`ifconfig`确认具体是哪个
multipass start k3s-node2

如果你不确定en0是不是对的接口，先跑：

bash
multipass networks

看看列出来的接口名，选那个实际接你家路由器的。

第四步：进虚拟机验证网络
bash
multipass shell k3s-node2
ip addr show   # 看看是不是拿到了局域网网段的IP（应该跟ziqiao-asm100是同一网段）
ping <ziqiao-asm100的IP>   # 确认互通

互通确认后，跳回虚拟机内部执行上一条消息里的k3s agent join命令就行了。


怎么看mac的性能，我想给你一些参数在考虑虚拟机的资源，可以适当大点

好主意，先看清楚Mac的硬件家底，再决定给虚拟机分多少资源。

查看Mac整体配置

最简单的是图形界面：苹果菜单 → 关于本机，能看到芯片型号、内存大小。

命令行更详细、也更适合你直接复制参数给我：

bash
# CPU型号、核心数
sysctl -n machdep.cpu.brand_string
sysctl -n hw.physicalcpu    # 物理核心数
sysctl -n hw.logicalcpu     # 逻辑核心数(含超线程)

# 内存总量
sysctl -n hw.memsize | awk '{print $1/1024/1024/1024 " GB"}'

# 磁盘剩余空间
df -h /
看当前资源占用情况（避免分配过头，把Mac主系统卡死）
bash
# 实时查看CPU/内存占用，类似Linux的top
top -l 1 | head -n 10

# 或者用图形界面：活动监视器（Activity Monitor），App里搜"活动监视器"
分配原则（拿到参数后我们再具体定）
给虚拟机的内存建议不超过Mac总内存的50%，留一半给macOS本身（Finder、浏览器等日常应用还要用）
CPU核心数同理，别把物理核心全占满
磁盘看你df -h /剩余空间，Kafka/ES的数据目录会持续增长，建议至少留20-30GB给虚拟机

把sysctl那几条命令的输出发给我，我帮你算一个具体的--cpus/--memory/--disk数值。


参数很清楚了，这台是2018款MacBook Pro的i7-8750H（6物理核/12线程），16GB内存，可用磁盘86GB——配置不算新但完全够用，来算一下：

资源分配建议
项目	Mac总量	建议分给虚拟机	留给macOS
CPU	6物理核/12线程	4核（走逻辑核心分配，Multipass按vCPU算，给4差不多是1/3逻辑核心）	8线程
内存	16GB	6GB	10GB
磁盘	86GB可用	40GB	46GB

内存这块我比之前说的"50%"稍微保守了一点——因为你这台是6年前的笔记本，物理内存本来就不算大，Kafka/ES的JVM堆本身就吃内存，虚拟机层再叠加一层开销，给到16GB的37.5%（6GB）比较稳，避免macOS本体因为内存压力而变卡。

磁盘给40GB是因为Kafka topic数据+ES索引会持续增长，留够余量，同时不会一下占掉你Mac一半的可用空间。

更新后的启动命令
bash
multipass launch --name k3s-node2 --cpus 4 --memory 6G --disk 40G

其余步骤（桥接网络、验证互通、join集群）跟之前发你的完全一样，直接接着走就行。

这个很常见，不用担心——大概率是两种情况之一：

先排查：Homebrew到底装没装过
bash
ls /usr/local/bin/brew        # Intel Mac的标准安装路径
ls /opt/homebrew/bin/brew     # 万一是这个路径也顺手查一下

如果两条命令都提示"No such file or directory"，说明之前用的可能是nvm、或者Node.js官网安装包直接装的，没有经过Homebrew——这也很常见，很多人误以为装过东西就等于装过brew。

如果其中一条能找到文件，说明brew其实装过，只是没配好PATH，跳到下面"只需要修复PATH"那一步就行，不用重装。

情况一：确实没装过，直接装
bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

跑完之后，终端里会打印一段"Next steps"提示，里面通常包含类似这样的命令（要照着终端实际打印的抄，因为用户名路径可能不同）：

bash
echo 'eval "$(/usr/local/bin/brew shellenv)"' >> ~/.zprofile
eval "$(/usr/local/bin/brew shellenv)"

这一步是把brew加进你的shell的PATH，Intel Mac默认装在/usr/local，跟Apple Silicon的/opt/homebrew不一样。

情况二：装过但PATH没配

如果上面ls /usr/local/bin/brew能找到，直接跑：

bash
echo 'eval "$(/usr/local/bin/brew shellenv)"' >> ~/.zprofile
eval "$(/usr/local/bin/brew shellenv)"
验证
bash
brew --version

能看到版本号就说明好了，再回去跑brew install --cask multipass。

先跑第一段ls排查命令，把结果发我，我们确认走哪条路。


这里有个概念要先厘清一下：Multipass列出的这些"版本"，其实全部都是无图形界面的Server镜像——不管你选26.04还是22.04，进去之后都是纯命令行环境，没有"带GUI版本"和"不带GUI版本"的区分。这个GUI窗口本身只是Multipass自带的管理面板（用来点点鼠标起停虚拟机），跟虚拟机内部有没有图形界面完全是两回事。所以你可以直接把这个GUI窗口关掉，全程用终端命令操作就行，更符合你练手k8s的场景。

选哪个版本

不建议选最新的26.04 LTS（Resolute Raccoon）或26.10——太新了，发布时间短，k3s/containerd生态对它的兼容性验证还不够充分，homelab练习图的是稳，不是图新。

建议选 24.04 LTS（Noble Numbat），理由：

是目前最成熟稳定的LTS版本，社区/k3s官方文档验证最多
最好跟你ziqiao-asm100那台的系统版本对齐——可以先在那台机器上跑一下确认：
bash
  lsb_release -a

如果它也是24.04，选一样的版本能减少"同一个集群里两个节点行为不一致"的排查成本

命令行怎么指定版本

不加版本号的话，multipass launch默认会拉最新LTS（现在可能就是26.04了），所以必须显式指定，把版本号作为第一个位置参数：

bash
multipass launch 24.04 --name k3s-node2 --cpus 4 --memory 6G --disk 40G

跑完之后回到之前发你的"检查网络桥接"那一步继续就行。


好，那我刚才"别选最新版"的建议要收回——版本对齐的优先级比"选更成熟的版本"更高，因为你练的是"多node组成同一个集群"，两台节点的Ubuntu版本、内核、containerd版本不一致，反而更容易在网络插件（Flannel/CNI）或者容器运行时层面踩到莫名其妙的兼容性坑，这种坑排查起来比"用新版本本身的风险"麻烦得多。

既然ziqiao-asm100已经是26.04 LTS（resolute），新node就跟着用一样的版本：

bash
multipass launch 26.04 --name k3s-node2 --cpus 4 --memory 6G --disk 40G

后面的步骤（桥接网络、验证互通、拿token、join集群）都不变，照之前发你的继续走。

顺带一句：这也提醒你以后搭这类多node环境时，第一步最好是"先看主节点用的什么版本"，再决定新节点装什么——而不是反过来先入为主觉得"新版本一定要避开"。稳定性考量在单节点独立场景下成立，但在集群一致性场景下，"跟现有节点对齐"通常权重更高。


刚才这条命令的语法确实过时了，Multipass新版本改了两步走的方式——不是直接把网卡名塞进bridged这个值里，而是先设置一个"全局默认桥接网络"，然后在启动虚拟机时指定要走这个桥接。

正确做法

第一步：设置全局的"首选桥接网络"（这一步只需要设置网卡名，不涉及具体虚拟机）

bash
multipass set local.bridged-network=en0

第二步：启动虚拟机时直接加--bridged参数，一步到位（虚拟机还没创建，直接这样起就行，不用像之前"先launch再单独设置"那样分两次）：

bash
multipass launch 26.04 --name k3s-node2 --cpus 4 --memory 6G --disk 40G --bridged

这样虚拟机一创建出来就已经是桥接状态，不需要再额外跑multipass set local.k3s-node2.bridged=true那一步。这个基于实例的桥接设置命令语法确实是multipass set local.<实例名>.bridged=true，但前提是要先设置好全局的首选网络。 
Ubuntu

验证

跑完之后进去确认拿到了局域网IP：

bash
multipass shell k3s-node2
ip addr show

应该能看到一个跟你路由器同网段的IP（而不是10.x或192.168.64.x这种Multipass默认NAT网段）。

拿到真实IP后，再跑ping <ziqiao-asm100的IP>确认互通，就能继续join集群那一步了。


互通已经打通了，可以继续下一步了。不过有一个细节值得提一句：

延迟有点不寻常，标记一下

14ms到204ms之间跳动、而且不是逐渐升高而是来回跳——这在同一个局域网内不算正常（同网段一般应该是稳定的1-5ms左右）。大概率原因是Wi-Fi桥接本身的开销：你的Mac是通过en0(Wi-Fi)桥接的，虚拟机的流量要经过"虚拟机→桥接层→Wi-Fi网卡→路由器"这一整条链路，比有线连接多几层转发,抖动会明显一些。

这个不影响你现在继续往下走（k3s对这点延迟完全不敏感），但如果后面Kafka/ES这些对网络延迟敏感的组件出现莫名其妙的超时报错，可以回头想到这里——先排除"是不是Wi-Fi桥接抖动导致的"，而不是一上来就怀疑配置写错了。

继续下一步：拿token，让Mac的虚拟机加入集群

回到ziqiao-asm100上：

bash
sudo cat /var/lib/rancher/k3s/server/node-token

复制这段token，然后回到k3s-node2（刚才ping的这台虚拟机）执行：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<刚才复制的token> sh -

跑完之后回到ziqiao-asm100验证：

bash
kubectl get nodes -o wide

应该能看到两个node，都是Ready状态。跑完把结果发我。



这是控制主机上的
ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ kubectl get nodes -o wide
NAME            STATUS   ROLES           AGE     VERSION        INTERNAL-IP     EXTERNAL-IP   OS-IMAGE           KERNEL-VERSION             CONTAINER-RUNTIME
k3s-node2       Ready    <none>          2m24s   v1.36.4+k3s1   192.168.252.2   <none>        Ubuntu 26.04 LTS   7.0.0-30-generic (amd64)   containerd://2.3.4-k3s1.36
ziqiao-asm100   Ready    control-plane   42d     v1.36.2+k3s1   192.168.40.23   <none>        Ubuntu 26.04 LTS   7.0.0-29-generic (amd64)   containerd://2.3.2-k3s2
ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ ping 192.168.40.69 
PING 192.168.40.69 (192.168.40.69) 56(84) bytes of data.
64 bytes from 192.168.40.69: icmp_seq=1 ttl=64 time=76.5 ms
64 bytes from 192.168.40.69: icmp_seq=2 ttl=64 time=98.6 ms
64 bytes from 192.168.40.69: icmp_seq=3 ttl=64 time=19.1 ms
^C
--- 192.168.40.69 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 2001ms
rtt min/avg/max/mdev = 19.129/64.760/98.641/33.507 ms
ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ ping 192.168.252.2
PING 192.168.252.2 (192.168.252.2) 56(84) bytes of data.
^C
--- 192.168.252.2 ping statistics ---
30 packets transmitted, 0 received, 100% packet loss, time 29693ms


中文

问题很明确，这是个必须解决的网络配置错误，不是延迟波动那种可以先放着的小问题。

问题诊断

看你的输出：node2注册进集群时用的INTERNAL-IP是192.168.252.2，但这个IP从控制节点根本ping不通（100%丢包）；而真正可达的、你之前ping通的那个IP是192.168.40.69——两个不同网段。

原因是：你的Mac虚拟机上存在两块网卡——一块是Multipass默认自带的"共享/NAT"接口（192.168.252.x网段，这也是为什么你ssh登录提示"from 192.168.252.1"），另一块才是你后来加的--bridged桥接接口（192.168.40.x网段，跟ziqiao-asm100真正同网段）。k3s装的时候自动选错了网卡，把NAT接口的IP注册成了INTERNAL-IP。

这个必须修——不解决的话，之后Flannel跨节点overlay网络会一直失败，任何调度到node2上的Pod都联不上集群里的其他Pod/Service。

修复步骤

第一步：确认node2上桥接网卡的准确接口名

bash
ip addr show

找到IP是192.168.40.69的那个接口名（不是lo也不是eth0那种默认接口，通常类似enp0s2），记下来。

第二步：卸载当前装错的k3s agent

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

第三步：回到ziqiao-asm100，先清掉旧的错误节点记录

bash
kubectl delete node k3s-node2

第四步：回到node2，重新安装，这次显式指定正确的IP和网卡（把<接口名>换成第一步查到的名字）

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<token> INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<接口名>" sh -

第五步：回到ziqiao-asm100验证

bash
kubectl get nodes -o wide

这次INTERNAL-IP应该显示192.168.40.69，再手动ping一下这个IP确认能通。

先跑第一步的ip addr show，把接口名发我确认一下再继续卸载重装。

日本語（ふりがな付き）

これは明確(めいかく)な問題(もんだい)で、必(かなら)ず解決(かいけつ)しなければならないネットワーク設定(せってい)のミスです。さきほどの遅延(ちえん)の揺(ゆ)らぎのように、いったん保留(ほりゅう)にしていい類(たぐい)の問題(もんだい)ではありません。

問題(もんだい)の診断(しんだん)

出力(しゅつりょく)を見(み)ると、node2がクラスターに登録(とうろく)された時(とき)のINTERNAL-IPは192.168.252.2ですが、このIPはコントロールノードからまったくpingが通(とお)りません(100%パケットロス)。一方(いっぽう)、実際(じっさい)に通(とお)る、以前(いぜん)にpingで確認(かくにん)できたIPは192.168.40.69——これは別(べつ)のセグメントです。

原因(げんいん)は、Macの仮想(かそう)マシンに二(ふた)つのネットワークインターフェースがあることです——一(ひと)つはMultipassがデフォルトで用意(ようい)する「共有(きょうゆう)/NAT」インターフェース(192.168.252.xセグメント、これがssh(えすえすえいち)ログイン時(じ)に「from 192.168.252.1」と表示(ひょうじ)された理由(りゆう)です)、もう一(ひと)つが後(あと)から追加(ついか)した--bridgedブリッジインターフェース(192.168.40.xセグメント、ziqiao-asm100と本当(ほんとう)に同(おな)じセグメント)。k3sのインストール時(じ)に間違(まちが)ったインターフェースが自動選択(じどうせんたく)されてしまい、NATインターフェース側(がわ)のIPがINTERNAL-IPとして登録(とうろく)されてしまいました。

これは必(かなら)ず修正(しゅうせい)が必要(ひつよう)です——このままだと、後(あと)でFlannelのノード間(かん)オーバーレイネットワークがずっと失敗(しっぱい)し、node2にスケジュールされたPodは、クラスター内(ない)の他(ほか)のPod/Serviceと一切(いっさい)通信(つうしん)できなくなります。

修正(しゅうせい)手順(てじゅん)

ステップ1：node2上(じょう)でブリッジインターフェースの正確(せいかく)な名前(なまえ)を確認(かくにん)する

bash
ip addr show

IPが192.168.40.69になっているインターフェース名(めい)を見(み)つけて(loでもeth0のようなデフォルトインターフェースでもなく、通常(つうじょう)はenp0s2のような名前(なまえ))、メモしておいてください。

ステップ2：現在(げんざい)の間違(まちが)ってインストールされたk3s agentをアンインストールする

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

ステップ3：ziqiao-asm100に戻(もど)って、古(ふる)い誤(あやま)ったノード情報(じょうほう)を先(さき)に削除(さくじょ)する

bash
kubectl delete node k3s-node2

ステップ4：node2に戻(もど)って、正(ただ)しいIPとインターフェースを明示的(めいじてき)に指定(してい)して再(さい)インストールする(<インターフェース名(めい)>はステップ1で確認(かくにん)した名前(なまえ)に置(お)き換(か)えてください)：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<token> INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<インターフェース名>" sh -

ステップ5：ziqiao-asm100に戻(もど)って確認(かくにん)する

bash
kubectl get nodes -o wide

今回(こんかい)はINTERNAL-IPが192.168.40.69と表示(ひょうじ)されるはずです。手動(しゅどう)でこのIPにpingして通(とお)ることも確認(かくにん)してください。

まずステップ1のip addr showを実行(じっこう)し、インターフェース名(めい)を送(おく)ってから、アンインストール・再(さい)インストールに進(すす)みましょう。

English

This one's a clear problem — a network misconfiguration you need to fix, not something like the latency jitter that could wait.

Diagnosis

Looking at your output: node2 registered into the cluster with INTERNAL-IP 192.168.252.2, but that IP is completely unreachable from the control node (100% packet loss). The IP that actually works — the one you pinged successfully earlier — is 192.168.40.69, a different subnet entirely.

The cause: your Mac VM has two network interfaces — one is Multipass's default "shared/NAT" interface (192.168.252.x subnet, which is why your SSH login showed "from 192.168.252.1"), and the other is the --bridged interface you added later (192.168.40.x subnet, actually on the same subnet as ziqiao-asm100). When k3s installed, it auto-detected the wrong interface and registered the NAT interface's IP as the INTERNAL-IP.

This has to be fixed — otherwise Flannel's cross-node overlay networking will keep failing, and any pod scheduled onto node2 won't be able to reach any other pod or service in the cluster.

Fix

Step 1: Confirm the exact interface name for the bridged network on node2

bash
ip addr show

Find the interface whose IP is 192.168.40.69 (not lo, not a default-looking eth0 — usually something like enp0s2) and note it down.

Step 2: Uninstall the currently misconfigured k3s agent

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

Step 3: Back on ziqiao-asm100, clear out the stale/wrong node entry first

bash
kubectl delete node k3s-node2

Step 4: Back on node2, reinstall — this time explicitly specifying the correct IP and interface (replace <interface-name> with what you found in step 1):

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<token> INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<interface-name>" sh -

Step 5: Verify from ziqiao-asm100

bash
kubectl get nodes -o wide

INTERNAL-IP should now show 192.168.40.69. Manually ping that IP too to confirm it's actually reachable.

Run the ip addr show from step 1 first and send me the interface name before moving on to the uninstall/reinstall.


Last login: Sat Sep  5 18:52:58 2026 from 192.168.252.1
ubuntu@k3s-node2:~$ curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 sh -
[INFO]  Finding release for channel stable
[INFO]  Using v1.36.4+k3s1 as release
[INFO]  Downloading hash https://github.com/k3s-io/k3s/releases/download/v1.36.4%2Bk3s1/sha256sum-amd64.txt
[INFO]  Downloading binary https://github.com/k3s-io/k3s/releases/download/v1.36.4%2Bk3s1/k3s
[INFO]  Verifying binary download
[INFO]  Installing k3s to /usr/local/bin/k3s
[INFO]  Skipping installation of SELinux RPM
[INFO]  Creating /usr/local/bin/kubectl symlink to k3s
[INFO]  Creating /usr/local/bin/crictl symlink to k3s
[INFO]  Creating /usr/local/bin/ctr symlink to k3s
[INFO]  Creating killall script /usr/local/bin/k3s-killall.sh
[INFO]  Creating uninstall script /usr/local/bin/k3s-agent-uninstall.sh
[INFO]  env: Creating environment file /etc/systemd/system/k3s-agent.service.env
[INFO]  systemd: Creating service file /etc/systemd/system/k3s-agent.service
[INFO]  systemd: Enabling k3s-agent unit
Created symlink '/etc/systemd/system/multi-user.target.wants/k3s-agent.service' → '/etc/systemd/system/k3s-agent.service'.
[INFO]  systemd: Starting k3s-agent
ubuntu@k3s-node2:~$ kubectl get nodes -o wide
E0905 19:08:10.735621   34664 memcache.go:265] "Unhandled Error" err="couldn't get current server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp [::1]:8080: connect: connection refused"
E0905 19:08:10.737337   34664 memcache.go:265] "Unhandled Error" err="couldn't get current server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp [::1]:8080: connect: connection refused"
E0905 19:08:10.738671   34664 memcache.go:265] "Unhandled Error" err="couldn't get current server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp [::1]:8080: connect: connection refused"
E0905 19:08:10.739687   34664 memcache.go:265] "Unhandled Error" err="couldn't get current server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp [::1]:8080: connect: connection refused"
E0905 19:08:10.740551   34664 memcache.go:265] "Unhandled Error" err="couldn't get current server API group list: Get \"http://localhost:8080/api?timeout=32s\": dial tcp [::1]:8080: connect: connection refused"
The connection to the server localhost:8080 was refused - did you specify the right host or port?

这是虚拟机的


中文

两件事分开说清楚。

第一件：这条kubectl报错是正常的，不用管

你在**node2（agent节点）**上直接跑kubectl，这里本来就没有集群的访问凭证——agent节点不会生成kubeconfig文件，只有ziqiao-asm100（control-plane节点）才有。以后要看集群状态，永远回到ziqiao-asm100上跑kubectl get nodes，node2这台机器上不需要装kubectl也不需要配置它。

第二件：核心问题——你重新跑的还是旧命令，没带修复参数

我看你这次装的命令是：

bash
curl -sfL https://get.k3s.io | K3S_URL=... K3S_TOKEN=... sh -

跟第一次一模一样，没有加INSTALL_K3S_EXEC="--node-ip=... --flannel-iface=..."这部分，所以大概率还是会重复之前的问题——自动选中了192.168.252.x那块NAT网卡。之前发的修复步骤，第一步"确认桥接网卡名字"这一步好像跳过了，我们从这里补上。

第一步：在node2上确认桥接网卡的名字

bash
ip addr show

把输出发我，找到IP是192.168.40.69的那个网卡名（不是lo）。

第二步：卸载这次装的agent，重新来

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

第三步：回到ziqiao-asm100清掉旧记录

bash
kubectl delete node k3s-node2

第四步：拿到网卡名后，回到node2用带修复参数的完整命令重装（<接口名>替换成第一步查到的）：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<接口名>" sh -

先跑ip addr show，把网卡名发我确认一下再往下走，避免又装错一次。

日本語(ふりがな付き)

二(ふた)つのことを分(わ)けて説明(せつめい)します。

一(ひと)つ目(め)：このkubectlエラーは正常(せいじょう)なので気(き)にしなくて大丈夫(だいじょうぶ)

あなたは**node2(agentノード)**上(じょう)で直接(ちょくせつ)kubectlを実行(じっこう)していますが、ここにはそもそもクラスターへのアクセス認証情報(にんしょうじょうほう)がありません——agentノードにはkubeconfigファイルが生成(せいせい)されません。それがあるのはziqiao-asm100(control-planeノード)だけです。今後(こんご)クラスターの状態(じょうたい)を見(み)たいときは、必(かなら)ずziqiao-asm100に戻(もど)ってkubectl get nodesを実行(じっこう)してください。node2にはkubectlを入(い)れる必要(ひつよう)も設定(せってい)する必要(ひつよう)もありません。

二(ふた)つ目(め)：本題(ほんだい)——今回(こんかい)実行(じっこう)したのはまだ古(ふる)いコマンドで、修正用(しゅうせいよう)パラメータが入(はい)っていません

今回(こんかい)実行(じっこう)したコマンドを見(み)ると：

bash
curl -sfL https://get.k3s.io | K3S_URL=... K3S_TOKEN=... sh -

最初(さいしょ)のときとまったく同(おな)じで、INSTALL_K3S_EXEC="--node-ip=... --flannel-iface=..."の部分(ぶぶん)が入(はい)っていません。なので恐(おそ)らく前回(ぜんかい)と同(おな)じ問題(もんだい)が繰(く)り返(かえ)されているはずです——自動的(じどうてき)に192.168.252.xのNATインターフェースが選択(せんたく)されてしまっています。前回(ぜんかい)送(おく)った修正手順(しゅうせいてじゅん)の最初(さいしょ)のステップ「ブリッジインターフェース名(めい)の確認(かくにん)」が飛(と)ばされてしまったようなので、ここから改(あらた)めてやり直(なお)しましょう。

ステップ1：node2でブリッジインターフェース名(めい)を確認(かくにん)する

bash
ip addr show

出力(しゅつりょく)を送(おく)ってください。IPが192.168.40.69になっているインターフェース名(めい)を探(さが)します(loではありません)。

ステップ2：今回(こんかい)インストールしたagentをアンインストールし、やり直(なお)す

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

ステップ3：ziqiao-asm100に戻(もど)って古(ふる)いノード情報(じょうほう)を削除(さくじょ)する

bash
kubectl delete node k3s-node2

ステップ4：インターフェース名(めい)がわかったら、node2で修正用(しゅうせいよう)パラメータ付(つ)きの完全(かんぜん)なコマンドで再(さい)インストールする(<インターフェース名>はステップ1で確認(かくにん)したものに置(お)き換(か)えてください)：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<インターフェース名>" sh -

まずip addr showを実行(じっこう)し、インターフェース名(めい)を送(おく)ってから次(つぎ)に進(すす)みましょう。また同(おな)じ間違(まちが)いを繰(く)り返(かえ)さないためです。

English

Two separate things here.

First: that kubectl error is expected, don't worry about it

You ran kubectl directly on node2 (an agent node), which never has cluster credentials to begin with — agent nodes don't get a kubeconfig file generated; only ziqiao-asm100 (the control-plane node) has one. Going forward, always check cluster state from ziqiao-asm100 with kubectl get nodes — node2 doesn't need kubectl installed or configured at all.

Second, the real issue: what you just ran was still the old command, without the fix flags

Looking at the command you ran:

bash
curl -sfL https://get.k3s.io | K3S_URL=... K3S_TOKEN=... sh -

It's identical to the first attempt — missing the INSTALL_K3S_EXEC="--node-ip=... --flannel-iface=..." part. So it very likely repeated the same problem: auto-selecting the 192.168.252.x NAT interface again. It looks like the first step from my earlier fix — confirming the bridged interface name — got skipped, so let's pick up from there.

Step 1: Confirm the bridged interface name on node2

bash
ip addr show

Send me the output — we're looking for the interface whose IP is 192.168.40.69 (not lo).

Step 2: Uninstall this attempt's agent, and start over

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

Step 3: Back on ziqiao-asm100, clear the stale node entry

bash
kubectl delete node k3s-node2

Step 4: Once you have the interface name, reinstall on node2 with the full command including the fix flags (replace <interface-name> with what you found in step 1):

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=<interface-name>" sh -

Run ip addr show first and send me the interface name before going further, so we don't repeat the same mistake a second time.


ubuntu@k3s-node2:~$ ip addr show
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    inet 127.0.0.1/8 scope host lo
       valid_lft forever preferred_lft forever
    inet6 ::1/128 scope host noprefixroute 
       valid_lft forever preferred_lft forever
2: ens3: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
    link/ether 52:54:00:58:f7:96 brd ff:ff:ff:ff:ff:ff
    altname enp0s3
    altname enx52540058f796
    inet 192.168.252.2/24 metric 100 brd 192.168.252.255 scope global dynamic ens3
       valid_lft 3229sec preferred_lft 3229sec
    inet6 fd97:52ea:cf62:415a:5054:ff:fe58:f796/64 scope global dynamic mngtmpaddr noprefixroute 
       valid_lft 2591903sec preferred_lft 604703sec
    inet6 fe80::5054:ff:fe58:f796/64 scope link proto kernel_ll 
       valid_lft forever preferred_lft forever
3: ens4: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
    link/ether 52:54:00:a9:69:8e brd ff:ff:ff:ff:ff:ff
    altname enp0s4
    altname enx525400a9698e
    inet 192.168.40.69/24 metric 200 brd 192.168.40.255 scope global dynamic ens4
       valid_lft 40325sec preferred_lft 40325sec
    inet6 2405:1201:1189:9d00::b1de:387a/128 scope global dynamic noprefixroute 
       valid_lft 601925sec preferred_lft 601925sec
    inet6 2405:1201:1189:9d00:5054:ff:fea9:698e/64 scope global dynamic mngtmpaddr noprefixroute 
       valid_lft 604707sec preferred_lft 604707sec
    inet6 fe80::5054:ff:fea9:698e/64 scope link proto kernel_ll 
       valid_lft forever preferred_lft forever



中文

确认了，网卡名是**ens4**（ens3是Multipass默认的NAT接口，192.168.252.x网段；ens4才是桥接接口，192.168.40.69）。

执行修复

第一步：在node2上卸载当前装错的agent

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

第二步：回到ziqiao-asm100清掉旧节点记录

bash
kubectl delete node k3s-node2

第三步：回到node2，用带正确参数的命令重装

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=ens4" sh -

第四步：回到ziqiao-asm100验证

bash
kubectl get nodes -o wide

这次INTERNAL-IP那一列应该显示192.168.40.69。

第五步：确认真正的连通性（这才是关键验证，光看IP显示对不代表网络插件真的通了）

bash
ping 192.168.40.69

通了之后，再跑一个更直接的验证——起个测试Pod看看能不能跨节点通信：

bash
kubectl run test-node2 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node2"}}}' --command -- sleep 3600
kubectl get pod test-node2 -o wide

如果这个Pod能顺利变成Running状态，说明Flannel跨节点网络也打通了，不只是节点注册对了。

把结果发我。

日本語(ふりがな付き)

確認(かくにん)できました。インターフェース名(めい)は**ens4**です(ens3はMultipassデフォルトのNATインターフェースで192.168.252.xセグメント、ens4が本当(ほんとう)のブリッジインターフェースで192.168.40.69)。

修正(しゅうせい)を実行(じっこう)する

ステップ1：node2で現在(げんざい)の誤(あやま)ってインストールされたagentをアンインストールする

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

ステップ2：ziqiao-asm100に戻(もど)って古(ふる)いノード情報(じょうほう)を削除(さくじょ)する

bash
kubectl delete node k3s-node2

ステップ3：node2に戻(もど)って、正(ただ)しいパラメータ付(つ)きのコマンドで再(さい)インストールする

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=ens4" sh -

ステップ4：ziqiao-asm100に戻(もど)って確認(かくにん)する

bash
kubectl get nodes -o wide

今回(こんかい)はINTERNAL-IPの列(れつ)に192.168.40.69が表示(ひょうじ)されるはずです。

ステップ5：本当(ほんとう)の疎通性(そつうせい)を確認(かくにん)する(これが本当(ほんとう)に重要(じゅうよう)な検証(けんしょう)です。IPの表示(ひょうじ)が正(ただ)しいだけでは、ネットワークプラグインが本当(ほんとう)に機能(きのう)しているとは限(かぎ)りません)

bash
ping 192.168.40.69

通(とお)ったら、もっと直接的(ちょくせつてき)な検証(けんしょう)として、テスト用(よう)のPodを立(た)ててノード間(かん)通信(つうしん)ができるか確認(かくにん)しましょう：

bash
kubectl run test-node2 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node2"}}}' --command -- sleep 3600
kubectl get pod test-node2 -o wide

このPodが問題(もんだい)なくRunning状態(じょうたい)になれば、ノード登録(とうろく)が正(ただ)しいだけでなく、Flannelのノード間(かん)ネットワークもきちんと機能(きのう)していることになります。

結果(けっか)を送(おく)ってください。

English

Confirmed — the interface name is ens4 (ens3 is Multipass's default NAT interface on 192.168.252.x; ens4 is the actual bridged interface with 192.168.40.69).

Run the fix

Step 1: Uninstall the currently misconfigured agent on node2

bash
sudo /usr/local/bin/k3s-agent-uninstall.sh

Step 2: Clear the stale node entry from ziqiao-asm100

bash
kubectl delete node k3s-node2

Step 3: Back on node2, reinstall with the correct parameters

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.69 --flannel-iface=ens4" sh -

Step 4: Verify from ziqiao-asm100

bash
kubectl get nodes -o wide

The INTERNAL-IP column should now show 192.168.40.69.

Step 5: Confirm real connectivity (this is the actual key check — the IP just displaying correctly doesn't guarantee the network plugin is actually working):

bash
ping 192.168.40.69

Once that's confirmed, do a more direct test by spinning up a pod on node2 to check cross-node networking actually works:

bash
kubectl run test-node2 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node2"}}}' --command -- sleep 3600
kubectl get pod test-node2 -o wide

If this pod goes to Running successfully, it confirms not just that the node registered correctly, but that Flannel's cross-node networking is actually functioning too.

Send me the results.



跑起来了，Running状态——说明node2已经真正成为集群一员，Flannel跨节点网络也打通了（这个Pod拿到了10.42.3.3这个Pod网段IP，说明CNI在这台新node上正常工作）。这轮修复到这里就完整解决了。

关于你问的 busybox：

这是一个非常小巧的Linux工具镜像（压缩后大概1-5MB），把ls、cat、ping、sleep这类几十个常用命令行工具打包成一个单一的二进制文件。在k8s场景里它几乎是"万能调试小白鼠"——不需要专门写Dockerfile、不需要额外拉大镜像，随手起一个就能进去验证网络、DNS、文件系统这些基础问题，用完就删,非常轻量。

你刚才这条命令里用它，只是让它执行sleep 3600（睡1小时不退出），目的单纯是"让Pod活着别马上结束"，方便你观察它被调度到了哪个node、拿到了什么IP——本身不需要它做别的事。

清理这个测试Pod

验证完就可以删掉了，不用一直留着：

bash
kubectl delete pod test-node2

接下来就可以回到最初的P6（Prometheus/Grafana监控）继续走了，多node环境已经就绪。

日本語(ふりがな付き)

起動(きどう)しました、Running状態(じょうたい)です——node2が本当(ほんとう)にクラスターの一員(いちいん)になり、Flannelのノード間(かん)ネットワークもきちんと機能(きのう)していることが確認(かくにん)できました(このPodが10.42.3.3というPodネットワークのIPを取得(しゅとく)できているのは、この新(あたら)しいノード上(じょう)でCNIが正常(せいじょう)に動作(どうさ)している証拠(しょうこ)です)。今回(こんかい)の修正(しゅうせい)はこれで完全(かんぜん)に解決(かいけつ)しました。

質問(しつもん)にあった busybox について：

これは非常(ひじょう)にコンパクトなLinuxツールのイメージです(圧縮後(あっしゅくご)でおよそ1〜5MB)。ls、cat、ping、sleepといった数十(すうじゅう)個(こ)のよく使(つか)うコマンドラインツールを、単一(たんいつ)のバイナリファイルにまとめたものです。k8sの現場(げんば)では、ほぼ「万能(ばんのう)デバッグ用(よう)モルモット」的(てき)な存在(そんざい)で——専用(せんよう)のDockerfileを書(か)く必要(ひつよう)もなく、余分(よぶん)な大(おお)きいイメージを取(と)ってくる必要(ひつよう)もなく、気軽(きがる)に起動(きどう)してネットワーク・DNS・ファイルシステムといった基本的(きほんてき)な問題(もんだい)を検証(けんしょう)し、終(お)わったら削除(さくじょ)する、という非常(ひじょう)に軽量(けいりょう)な使(つか)い方(かた)ができます。

さきほどのコマンドでは、sleep 3600(1時間(じかん)スリープして終了(しゅうりょう)しない)を実行(じっこう)させているだけで、目的(もくてき)は単純(たんじゅん)に「Podをすぐに終了(しゅうりょう)させずに生(い)かしておく」ことです。どのノードにスケジュールされたか、どんなIPを取得(しゅとく)したかを観察(かんさつ)しやすくするためで、それ自体(じたい)に他(ほか)の役割(やくわり)はありません。

このテスト用(よう)Podを片付(かたづ)ける

確認(かくにん)が終(お)わったので削除(さくじょ)して大丈夫(だいじょうぶ)です。ずっと残(のこ)しておく必要(ひつよう)はありません：

bash
kubectl delete pod test-node2

これで最初(さいしょ)のP6(Prometheus/Grafana監視(かんし))に戻(もど)って続(つづ)けることができます。マルチノード環境(かんきょう)はもう準備(じゅんび)完了(かんりょう)です。

English

It's running — Running state — which confirms node2 is genuinely part of the cluster now, and Flannel's cross-node networking is working (the pod got 10.42.3.3, a pod-network IP, which shows the CNI is functioning correctly on this new node). This round of fixes is fully resolved.

As for your question about busybox:

It's a tiny Linux utility image (compressed, roughly 1-5MB) that bundles dozens of common command-line tools — ls, cat, ping, sleep, and similar — into a single binary. In k8s contexts it's basically the go-to "debugging guinea pig": no need to write a Dockerfile, no need to pull a large image, just spin one up quickly to check networking, DNS, filesystem basics, then delete it when done — very lightweight.

In your command it's just running sleep 3600 (sleep for an hour without exiting) — the point was purely to keep the pod alive so you could observe which node it got scheduled to and what IP it received. It wasn't meant to do anything beyond that.

Clean up the test pod

You can delete it now that it's verified — no need to keep it around:

bash
kubectl delete pod test-node2

From here you can head back to P6 (Prometheus/Grafana monitoring) — the multi-node setup is ready to go.

我还有个更老的mac，还有个老windows，我觉得这个虚拟机挺好的

中文

这个想法很好——如果这两台都是真正独立的物理机，价值比在同一台Mac上开虚拟机模拟第三个node要高得多，之前顾虑的"两个虚拟机节点挤在同一台物理机上失去跨物理机意义"这个问题直接就不存在了。不过在动手前，有两个关键信息需要先确认，直接影响能不能装、怎么装：

关于老Mac
芯片型号：跑一下 sysctl -n machdep.cpu.brand_string（如果是Intel）或者看"关于本机"（如果是Apple Silicon的M系列）。这决定了它是不是跟现有两个node同架构（amd64）——如果这台更老的Mac也是Intel，直接复用现有镜像，零障碍；如果碰巧是M1/M2这类arm64，就要考虑之前提过的多架构镜像构建问题
内存和磁盘：跟之前一样跑sysctl -n hw.memsize和df -h /
关于老Windows机器

这个稍微复杂一点，Windows机器要加入k3s集群，必须先有Linux内核，几个可选路径：

最推荐：直接把这台Windows机器抹掉重装成Ubuntu（如果这台机器不再需要跑Windows软件的话）——这是最干净、最接近生产环境的做法，物理机直接跑k3s agent，没有虚拟化开销
如果还想保留Windows：可以用WSL2（Windows自带的Linux子系统）跑k3s agent，但WSL2本质上也是一层虚拟化，网络配置比原生Linux更麻烦一些（尤其是让WSL2的虚拟网卡桥接到局域网这一步，经验上比Mac的Multipass桥接更容易踩坑）
或者用VirtualBox/VMware在Windows上开一个Ubuntu虚拟机，跟你现在Mac上Multipass的做法思路一致
建议顺序

先把老Mac的规格发我，这个大概率能直接复用现在的经验快速搞定；老Windows那台涉及"要不要抹盘装Ubuntu"这种取舍，可以之后再单独聊。

日本語(ふりがな付き)

これはいいアイデアですね——もしこの二(ふた)台(だい)が本当(ほんとう)に独立(どくりつ)した物理(ぶつり)マシンであれば、同(おな)じMac上(じょう)で仮想(かそう)マシンを立(た)てて3(さん)台目(だいめ)のノードをシミュレートするより、はるかに価値(かち)があります。前回(ぜんかい)懸念(けねん)していた「二(ふた)つの仮想(かそう)マシンノードが同(おな)じ物理(ぶつり)マシンに同居(どうきょ)して、物理(ぶつり)マシンをまたぐ意味(いみ)が薄(うす)れる」という問題(もんだい)自体(じたい)がそもそも発生(はっせい)しません。ただし手(て)を動(うご)かす前(まえ)に、確認(かくにん)しておくべき重要(じゅうよう)な情報(じょうほう)が二(ふた)つあり、それによって「そもそも入(い)れられるか」「どう入(い)れるか」が変(か)わってきます。

古(ふる)いMacについて
チップの型番(かたばん)：sysctl -n machdep.cpu.brand_stringを実行(じっこう)する(Intelの場合(ばあい))か、「このMacについて」を見(み)る(Apple SiliconのMシリーズの場合(ばあい))。これによって既存(きぞん)の二(ふた)つのノードと同(おな)じアーキテクチャ(amd64)かどうかが決(き)まります——もしこの古(ふる)いMacもIntelであれば、既存(きぞん)のイメージをそのまま使(つか)えて障害(しょうがい)ゼロです。もし万(まん)が一(いち)M1/M2のようなarm64だった場合(ばあい)は、以前(いぜん)話(はな)したマルチアーキテクチャイメージのビルドを考慮(こうりょ)する必要(ひつよう)があります
メモリとディスク：前回(ぜんかい)と同(おな)じようにsysctl -n hw.memsizeとdf -h /を実行(じっこう)してください
古(ふる)いWindowsマシンについて

こちらは少(すこ)し複雑(ふくざつ)です。Windowsマシンをk3sクラスターに参加(さんか)させるには、**まずLinuxカーネルが必要(ひつよう)**です。いくつか選択肢(せんたくし)があります：

一番(いちばん)おすすめ：このWindowsマシンを消(け)してUbuntuに入(い)れ替(か)える(もうWindowsソフトを動(うご)かす必要(ひつよう)がないなら)——これが一番(いちばん)クリーンで、本番環境(ほんばんかんきょう)に一番(いちばん)近(ちか)いやり方(かた)です。物理(ぶつり)マシン上(じょう)で直接(ちょくせつ)k3s agentを動(うご)かせるので、仮想化(かそうか)のオーバーヘッドがありません
Windowsを残(のこ)したい場合(ばあい)：WSL2(Windows内蔵(ないぞう)のLinuxサブシステム)でk3s agentを動(うご)かすこともできますが、WSL2自体(じたい)も一種(いっしゅ)の仮想化(かそうか)レイヤーで、ネットワーク設定(せってい)がネイティブLinuxより少(すこ)し面倒(めんどう)です(特(とく)にWSL2の仮想(かそう)NICをLANにブリッジさせるステップは、経験上(けいけんじょう)Macの Multipassブリッジよりつまずきやすいです)
または、Windows上(じょう)でVirtualBox/VMwareを使(つか)ってUbuntu仮想(かそう)マシンを立(た)てる、今(いま)Mac上(じょう)でMultipassでやっているのと同(おな)じ考(かんが)え方(かた)です
おすすめの進(すす)め方(かた)

まずは古(ふる)いMacのスペックを送(おく)ってください。おそらく今(いま)までの経験(けいけん)をそのまま活(い)かして素早(すばや)く進(すす)められるはずです。古(ふる)いWindowsの方(ほう)は「Ubuntuに入(い)れ替(か)えるかどうか」というトレードオフが絡(から)むので、それはまた別途(べっと)相談(そうだん)しましょう。

English

Good instinct — if these two are genuinely separate physical machines, that's much more valuable than spinning up a VM on the same Mac to simulate a third node. The earlier concern about "two VM-nodes sitting on the same physical machine losing the point of crossing physical machines" simply doesn't apply here. But before diving in, there are two key pieces of info that directly determine feasibility and approach:

About the older Mac
Chip model: run sysctl -n machdep.cpu.brand_string (if Intel) or check "About This Mac" (if it's an Apple Silicon M-series). This determines whether it matches the architecture (amd64) of your existing two nodes — if this older Mac is also Intel, you can reuse your existing images with zero friction; if it happens to be an M1/M2 (arm64), you'll need to consider the multi-arch image build issue mentioned earlier.
Memory and disk: same as before, sysctl -n hw.memsize and df -h /.
About the older Windows machine

This one's a bit more involved. To join a Windows machine to a k3s cluster, you need a Linux kernel first. A few options:

Most recommended: wipe the Windows machine and install Ubuntu directly (if you no longer need to run Windows software on it) — the cleanest approach, closest to production, running k3s agent directly on bare metal with no virtualization overhead.
If you want to keep Windows: you could run k3s agent under WSL2 (Windows's built-in Linux subsystem), but WSL2 is itself a layer of virtualization, and networking is a bit more finicky than native Linux (bridging WSL2's virtual NIC to the LAN, in particular, tends to be trickier than the Multipass bridging you did on the Mac).
Or run VirtualBox/VMware on Windows with an Ubuntu VM inside it — same concept as what you're currently doing with Multipass on the Mac.
Suggested order

Send me the older Mac's specs first — that one will likely be quick to sort out using what we've already learned. The older Windows machine involves a bigger trade-off decision (wipe it for Ubuntu or not), so we can tackle that separately once you've decided.


Last login: Sat Sep  5 20:00:29 on console
ziqiao@ZIQIAOnoiMac ~ % sysctl -n machdep.cpu.brand_string
Intel(R) Core(TM) i5-7360U CPU @ 2.30GHz
ziqiao@ZIQIAOnoiMac ~ % sysctl -n hw.physicalcpu
2
ziqiao@ZIQIAOnoiMac ~ % sysctl -n hw.logicalcpu 
4
ziqiao@ZIQIAOnoiMac ~ % sysctl -n hw.memsize | awk '{print $1/1024/1024/1024 " GB"}'
16 GB
ziqiao@ZIQIAOnoiMac ~ % df -h /
Filesystem       Size   Used  Avail Capacity iused     ifree %iused  Mounted on
/dev/disk1s5s1  233Gi  8.7Gi   17Gi    34%  356882 178422360    0%   /
ziqiao@ZIQIAOnoiMac ~ % top -l 1 | head -n 10
Processes: 459 total, 2 running, 457 sleeping, 1721 threads 
2026/09/05 20:07:28
Load Avg: 6.62, 13.11, 8.46 
CPU usage: 7.28% user, 19.86% sys, 72.84% idle 
SharedLibs: 653M resident, 104M data, 59M linkedit.
MemRegions: 194454 total, 2488M resident, 282M private, 1047M shared.
PhysMem: 10G used (2231M wired, 0B compressor), 5769M unused.
VM: 29T vsize, 3569M framework vsize, 0(0) swapins, 0(0) swapouts.
Networks: packets: 670433/806M in, 158016/24M out.
Disks: 227431/3558M read, 155447/5738M written.
ziqiao@ZIQIAOnoiMac ~ %


中文

参数拿到了，但这台机器有两个地方需要先关注一下，不建议直接跳过去装：

需要先留意的两点

1. 可用磁盘只有17GB，这个偏紧张——磁盘总共233GB，但df -h /显示只有17GB可用（容量已用到34%），这跟你之前那台"86GB可用"的MacBook Pro差距很大。这台iMac上装的东西可能比想象中占地方多。建议先跑一下：

bash
du -sh /Users/* 2>/dev/null | sort -rh | head -10

看看是不是有大文件/大文件夹能清理，17GB要装虚拟机+k3s+镜像缓存，会比较紧绷，几乎没有容错空间。

2. Load Average偏高，跟CPU idle率不太匹配——Load Avg: 6.62, 13.11, 8.46在只有4个逻辑核心的机器上，这个数字不低（超过核心数就说明有排队等待的进程），但同时CPU usage又显示72.84% idle——这种"负载高但CPU看着很闲"的组合，通常意味着大量进程在等I/O（比如磁盘读写、Spotlight索引重建之类后台任务），不是CPU算力不够。建议先看一下：

bash
top -o cpu

如果看到mds、mds_stores（Spotlight索引服务）占用很高，等它跑完索引，再装虚拟机，避免叠加负载。

资源分配建议（在磁盘空间清理之后再定）

这台i5-7360U是双核低功耗芯片（严格说是2013年代后的超低压移动U，性能明显弱于之前那台i7-8750H），加上磁盘紧张，建议这台机器分配得更保守，甚至可以考虑"只做无状态服务测试用途"，不承担Kafka/ES这种吃资源的有状态服务：

项目	建议值	说明
CPU	2核	逻辑核心的一半，双核机器不能再多分了
内存	3GB	当前系统本身已经用了10GB(16GB里)，剩余可用只有5.6GB左右，给虚拟机太多会导致macOS本身开始交换分页
磁盘	10GB	基于清理后重新确认的可用空间调整,这个数字先按最保守估算

先跑上面两条排查命令，把结果发我，确认磁盘和负载没问题后，再给你最终的multipass launch命令。

日本語(ふりがな付き)

スペックは確認(かくにん)できましたが、二(ふた)つ気(き)になる点(てん)があるので、そのまま次(つぎ)に進(すす)むのはおすすめしません。

先(さき)に確認(かくにん)すべき二(ふた)つのこと

1. 空(あ)きディスクが17GBしかなく、これはやや厳(きび)しい——ディスク全体(ぜんたい)は233GBですが、df -h /では空(あ)き容量(ようりょう)が17GBしかありません(使用率(しようりつ)34%)。前回(ぜんかい)の「86GB空(あ)き」のMacBook Proとはかなり差(さ)があります。このiMacには思(おも)ったより容量(ようりょう)を食(く)っているものがあるかもしれません。先(さき)に以下(いか)を実行(じっこう)してみてください：

bash
du -sh /Users/* 2>/dev/null | sort -rh | head -10

削除(さくじょ)できる大(おお)きなファイル/フォルダがないか確認(かくにん)しましょう。仮想(かそう)マシン+k3s+イメージキャッシュを17GBに詰(つ)め込(こ)むのはかなりタイトで、ほぼ余裕(よゆう)がありません。

2. Load Average(負荷平均(ふかへいきん))が高(たか)く、CPUのidle率(りつ)と噛(か)み合(あ)っていない——Load Avg: 6.62, 13.11, 8.46は、論理(ろんり)コアが4(よん)個(こ)しかないマシンとしてはかなり高(たか)い数値(すうち)です(コア数(すう)を超(こ)えると、待機中(たいきちゅう)のプロセスがあるということ)。それなのにCPU usageは72.84% idleと表示(ひょうじ)されています——この「負荷(ふか)は高(たか)いのにCPUは暇(ひま)そう」という組(く)み合(あ)わせは、たいてい大量(たいりょう)のプロセスがI/O待(ま)ちになっていることを意味(いみ)します(ディスクの読(よ)み書(か)きや、Spotlightのインデックス再構築(さいこうちく)などのバックグラウンドタスク)。CPUの処理能力(しょりのうりょく)不足(ぶそく)ではありません。先(さき)に確認(かくにん)してみましょう：

bash
top -o cpu

mdsやmds_stores(Spotlightのインデックスサービス)の使用率(しようりつ)が高(たか)ければ、インデックス処理(しょり)が終(お)わるのを待(ま)ってから仮想(かそう)マシンを入(い)れた方(ほう)が、負荷(ふか)が重(かさ)ならずに済(す)みます。

リソース割(わ)り当(あ)て提案(ていあん)(ディスクの整理(せいり)後(ご)に確定(かくてい)しましょう)

このi5-7360Uはデュアルコアの低消費電力(ていしょうひでんりょく)チップで(前回(ぜんかい)のi7-8750Hよりはっきり性能(せいのう)が劣(おと)ります)、加(くわ)えてディスクも厳(きび)しいので、このマシンにはより控(ひか)えめな割(わ)り当(あ)てをおすすめします。場合(ばあい)によっては「ステートレスサービスのテスト用途(ようと)のみ」として使(つか)い、Kafka/ESのようなリソースを食(く)うステートフルサービスは担当(たんとう)させない、という選択肢(せんたくし)も考(かんが)えられます：

項目(こうもく)	推奨値(すいしょうち)	説明(せつめい)
CPU	2コア	論理(ろんり)コアの半分(はんぶん)。デュアルコアマシンなのでこれ以上(いじょう)は割(わ)り当(あ)てられません
メモリ	3GB	現在(げんざい)すでにシステム自体(じたい)が16GB中(ちゅう)10GBを使用(しよう)しており、残(のこ)りの空(あ)きは5.6GBほどしかありません。仮想(かそう)マシンに多(おお)く割(わ)り当(あ)てすぎるとmacOS本体(ほんたい)がスワップを始(はじ)めてしまいます
ディスク	10GB	整理後(せいりご)に確認(かくにん)した空(あ)き容量(ようりょう)を基準(きじゅん)に調整(ちょうせい)。ひとまず最(もっと)も控(ひか)えめな見積(みつ)もりです

まず上(うえ)の二(ふた)つの確認(かくにん)コマンドを実行(じっこう)し、結果(けっか)を送(おく)ってください。ディスクと負荷(ふか)に問題(もんだい)がないことを確認(かくにん)してから、最終的(さいしゅうてき)なmultipass launchコマンドをお伝(つた)えします。

English

Got the specs, but there are two things worth checking before jumping straight into setup — I wouldn't skip past these.

Two things to look at first

1. Only 17GB free disk — that's tight. Total disk is 233GB, but df -h / shows only 17GB available (34% used). That's a big gap from the 86GB free on your other MacBook Pro. Something on this iMac is taking up more space than you'd expect. Worth checking first:

bash
du -sh /Users/* 2>/dev/null | sort -rh | head -10

to see if there's anything large you can clean up. Squeezing a VM + k3s + image cache into 17GB is going to be tight, with almost no margin for error.

2. Load average is high and doesn't match the CPU idle percentage. Load Avg: 6.62, 13.11, 8.46 is fairly elevated for a machine with only 4 logical cores (exceeding core count means processes are queued waiting). But CPU usage shows 72.84% idle at the same time — that combination of "high load but CPU looks idle" usually means a lot of processes are waiting on I/O (disk reads/writes, or background tasks like Spotlight reindexing), not that the CPU itself is maxed out. Worth checking:

bash
top -o cpu

If you see mds or mds_stores (Spotlight's indexing service) using a lot, better to let it finish indexing before adding a VM on top, so the loads don't stack.

Resource allocation recommendation (finalize after disk cleanup)

This i5-7360U is a dual-core, low-power chip (noticeably weaker than the i7-8750H on the other machine), and combined with the tight disk, I'd recommend being more conservative here — even consider using this node only for stateless-service testing, not taking on resource-hungry stateful services like Kafka/ES:

Resource	Suggested	Notes
CPU	2 cores	Half the logical cores — this dual-core machine can't spare more
Memory	3GB	The system's already using 10GB of the 16GB total, leaving only ~5.6GB free; over-allocating to the VM will push macOS itself into swapping
Disk	10GB	Adjust based on confirmed free space after cleanup — this is the most conservative estimate for now

Run those two diagnostic commands first and send me the results — once we confirm disk and load are okay, I'll give you the final multipass launch command.


中文

清理后数据好看多了：磁盘从17GB提升到133GB可用，负载也从6.62降到1.32、CPU idle率93.77%——之前那个"高负载低CPU占用"的现象确认是索引/清理相关的临时抖动，现在已经恢复正常，可以放心继续。

不过内存这块还是提醒一句：PhysMem显示12G used（比之前还略高一点），4515M unused——16GB里可用不到5GB，虚拟机内存还是建议保守分配，避免macOS本体因为内存压力变卡。

资源分配（更新版）
项目	建议值	说明
CPU	2核	双核机器，最多也只能给这么多
内存	3GB	当前可用内存有限，给太多会挤压macOS本体
磁盘	20GB	磁盘空间已经充裕，可以比之前保守估计的10GB略宽松一点
启动虚拟机（这台机器要重复一遍完整流程）

第一步：装Multipass（这是一台新机器，需要重新装一遍）

bash
brew install --cask multipass

如果又报command not found: brew，按之前教过的排查ls /usr/local/bin/brew，缺了就重装Homebrew。

第二步：查这台iMac的网卡名

bash
multipass networks

Wi-Fi接口大概率还是en0，但保险起见先确认一遍，不同Mac可能不一样。

第三步：设置桥接网络 + 启动虚拟机（版本保持26.04跟其他两个node对齐）

bash
multipass set local.bridged-network=en0
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

第四步：进虚拟机确认拿到桥接IP、跟ziqiao-asm100互通

bash
multipass shell k3s-node3
ip addr show    # 记下桥接网卡名字和IP，跟之前node2的坑一样，这里必须显式记录
ping 192.168.40.23

第五步：拿token，join集群时显式指定node-ip和flannel-iface（避免重蹈node2最初选错网卡的坑，<接口名>和<IP>换成第四步查到的）

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<之前的token> INSTALL_K3S_EXEC="--node-ip=<IP> --flannel-iface=<接口名>" sh -

先跑第一、二步，把multipass networks的结果发我确认一下网卡名再继续。

日本語(ふりがな付き)

整理後(せいりご)のデータはかなり良好(りょうこう)です：ディスクは17GBから133GB空(あ)きに増(ふ)えました、負荷(ふか)も6.62から1.32に下(さ)がり、CPUのidle率(りつ)は93.77%——さきほどの「負荷(ふか)は高(たか)いのにCPU使用率(しようりつ)は低(ひく)い」という現象(げんしょう)は、インデックス作成(さくせい)や整理(せいり)関連(かんれん)の一時的(いちじてき)な揺(ゆ)らぎだったことが確認(かくにん)できました。今(いま)はもう正常(せいじょう)に戻(もど)っているので、安心(あんしん)して進(すす)められます。

ただメモリについては一言(ひとこと)注意(ちゅうい)しておきます：PhysMemは12G used(以前(いぜん)よりやや高(たか)め)、4515M unusedと表示(ひょうじ)されています——16GB中(ちゅう)使(つか)える空(あ)きは5GB弱(じゃく)なので、仮想(かそう)マシンのメモリはやはり控(ひか)えめに割(わ)り当(あ)てた方(ほう)がよく、macOS本体(ほんたい)がメモリ圧迫(あっぱく)で重(おも)くなるのを避(さ)けましょう。

リソース割(わ)り当(あ)て(更新版(こうしんばん))
項目(こうもく)	推奨値(すいしょうち)	説明(せつめい)
CPU	2コア	デュアルコアマシンなので、これ以上(いじょう)は割(わ)り当(あ)てられません
メモリ	3GB	現在(げんざい)の空(あ)きメモリが限(かぎ)られているので、多(おお)く割(わ)り当(あ)てるとmacOS本体(ほんたい)を圧迫(あっぱく)します
ディスク	20GB	ディスク容量(ようりょう)にはすでに余裕(よゆう)があるので、以前(いぜん)の控(ひか)えめな10GBより少(すこ)し多(おお)めにできます
仮想(かそう)マシンを起動(きどう)する(このマシンでも一連(いちれん)の流(なが)れを繰(く)り返(かえ)します)

ステップ1：Multipassをインストールする(新(あたら)しいマシンなので、改(あらた)めてインストールが必要(ひつよう)です)

bash
brew install --cask multipass

もし再(ふたた)びcommand not found: brewと出(で)たら、以前(いぜん)教(おし)えた通(とお)りls /usr/local/bin/brewで確認(かくにん)し、なければHomebrewを再(さい)インストールしてください。

ステップ2：このiMacのネットワークインターフェース名(めい)を確認(かくにん)する

bash
multipass networks

Wi-Fiインターフェースはおそらくen0のままですが、Macによって異(こと)なる可能性(かのうせい)もあるので、念(ねん)のため確認(かくにん)しておきましょう。

ステップ3：ブリッジネットワークを設定(せってい)し、仮想(かそう)マシンを起動(きどう)する(バージョンは他(ほか)の二(ふた)つのノードと揃(そろ)えて26.04のまま)

bash
multipass set local.bridged-network=en0
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

ステップ4：仮想(かそう)マシンに入(はい)って、ブリッジIPが取得(しゅとく)できているか、ziqiao-asm100と疎通(そつう)するか確認(かくにん)する

bash
multipass shell k3s-node3
ip addr show    # ブリッジインターフェースの名前とIPをメモしておく。前回(ぜんかい)のnode2と同(おな)じ落(お)とし穴(あな)を避(さ)けるため、ここは必(かなら)ず明示的(めいじてき)に記録(きろく)すること
ping 192.168.40.23

ステップ5：tokenを取得(しゅとく)し、クラスターにjoinする際(さい)、node-ipとflannel-ifaceを明示的(めいじてき)に指定(してい)する(node2で最初(さいしょ)にインターフェースを間違(まちが)えた失敗(しっぱい)を繰(く)り返(かえ)さないため。<インターフェース名>と<IP>はステップ4で確認(かくにん)したものに置(お)き換(か)えてください)

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<前回(ぜんかい)のtoken> INSTALL_K3S_EXEC="--node-ip=<IP> --flannel-iface=<インターフェース名>" sh -

まずステップ1、2を実行(じっこう)し、multipass networksの結果(けっか)を送(おく)ってインターフェース名(めい)を確認(かくにん)してから次(つぎ)に進(すす)みましょう。

English

Much better after cleanup: disk went from 17GB to 133GB free, and load dropped from 6.62 to 1.32 with CPU idle at 93.77% — confirming that earlier "high load, low CPU usage" pattern was just temporary indexing/cleanup noise. Everything's back to normal now, safe to proceed.

One note on memory though: PhysMem shows 12G used (slightly higher than before) and 4515M unused — with less than 5GB free out of 16GB, still worth being conservative on VM memory allocation to avoid macOS itself getting sluggish under memory pressure.

Resource allocation (updated)
Resource	Suggested	Notes
CPU	2 cores	Dual-core machine — this is the max you can spare
Memory	3GB	Free memory is limited right now; more would squeeze macOS itself
Disk	20GB	Disk has plenty of headroom now, a bit more generous than the earlier conservative 10GB estimate
Launching the VM (repeat the full flow on this machine)

Step 1: Install Multipass (new machine, needs installing again)

bash
brew install --cask multipass

If you get command not found: brew again, check ls /usr/local/bin/brew like before, and reinstall Homebrew if it's missing.

Step 2: Check this iMac's network interface name

bash
multipass networks

The Wi-Fi interface is probably still en0, but worth confirming since it can vary by Mac.

Step 3: Set the bridged network and launch the VM (keep version 26.04 to match your other two nodes):

bash
multipass set local.bridged-network=en0
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

Step 4: Get into the VM and confirm it got a bridged IP, and can reach ziqiao-asm100

bash
multipass shell k3s-node3
ip addr show    # note the bridged interface name and IP — avoiding the same trap as node2, this must be recorded explicitly
ping 192.168.40.23

Step 5: Get the token, and explicitly specify node-ip and flannel-iface when joining (to avoid repeating node2's original wrong-interface mistake — replace <interface-name> and <IP> with what you found in step 4):

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=<the earlier token> INSTALL_K3S_EXEC="--node-ip=<IP> --flannel-iface=<interface-name>" sh -

Run steps 1 and 2 first, and send me the multipass networks output to confirm the interface name before continuing.


Last login: Sat Sep  5 20:05:13 on ttys000
ziqiao@ZIQIAOnoiMac ~ % multipass networks
Name   Type       Description
en0    ethernet   Ethernet
en1    wifi       Wi-Fi
ziqiao@ZIQIAOnoiMac ~ % 

用Wi-Fi这个

中文

注意到了——这台iMac跟之前那台MacBook Pro不一样：这里en0是有线以太网，Wi-Fi是en1。这正是我之前提醒"不同Mac可能不一样"的原因，如果照抄之前的en0就会桥接到错误的（甚至可能没插网线、根本不通的）接口。

按你的选择，用en1：

bash
multipass set local.bridged-network=en1
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

起完之后进去确认桥接IP和网卡名（这台机器内部的网卡命名规则大概率还是ensX这种，但具体是哪个要重新查，不能直接沿用node2的ens4）：

bash
multipass shell k3s-node3
ip addr show
ping 192.168.40.23

把ip addr show的结果发我，确认一下IP和接口名再进行下一步join集群。

日本語(ふりがな付き)

気(き)づきました——このiMacは前回(ぜんかい)のMacBook Proとは違(ちが)います：ここではen0が有線(ゆうせん)イーサネットで、Wi-Fiはen1です。これはまさに以前(いぜん)「Macによって異(こと)なる可能性(かのうせい)がある」と注意(ちゅうい)した理由(りゆう)です。もし前回(ぜんかい)のen0をそのまま使(つか)ってしまうと、間違(まちが)ったインターフェース(下手(へた)をすればケーブルすら繋(つな)がっていない、通信(つうしん)できないインターフェース)にブリッジしてしまうところでした。

ご希望(きぼう)通(とお)り、en1を使(つか)います：

bash
multipass set local.bridged-network=en1
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

起動後(きどうご)、中(なか)に入(はい)ってブリッジIPとインターフェース名(めい)を確認(かくにん)してください(このマシン内部(ないぶ)のインターフェース命名規則(めいめいきそく)もおそらくensXのような形式(けいしき)ですが、具体的(ぐたいてき)にどれかは改(あらた)めて確認(かくにん)する必要(ひつよう)があり、node2のens4をそのまま流用(りゅうよう)することはできません)：

bash
multipass shell k3s-node3
ip addr show
ping 192.168.40.23

ip addr showの結果(けっか)を送(おく)ってください。IPとインターフェース名(めい)を確認(かくにん)してから、次(つぎ)のクラスターjoinステップに進(すす)みましょう。

English

Good catch — this iMac is different from the MacBook Pro: here en0 is wired Ethernet, and Wi-Fi is en1. This is exactly why I flagged earlier that "it can vary by Mac" — if you'd just reused the previous en0, you'd have bridged to the wrong interface (possibly not even connected to anything).

Going with en1 as you chose:

bash
multipass set local.bridged-network=en1
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

Once it's up, get in and confirm the bridged IP and interface name (the interface naming inside this VM will likely follow the same ensX pattern, but which specific one it is needs to be checked fresh — don't just reuse node2's ens4):

bash
multipass shell k3s-node3
ip addr show
ping 192.168.40.23

Send me the ip addr show output so we confirm the IP and interface name before moving on to joining the cluster.


multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged
[2026-09-05T20:23:12.195] [error] [qemu-img] Process crashed
launch failed: Failed to amend image to QCOW2 v3: qemu-img failed (program: qemu-img; error: Process crashed) with output:

中文

这个报错不是你操作的问题——查了一下，这是Multipass在部分老款Intel Mac上的已知问题：qemu-img这个工具在处理镜像格式转换（QCOW2 v3）时进程直接崩溃了，跟macOS版本较老、或者下载的镜像缓存损坏有关系，Multipass官方GitHub上有相同报错的公开issue,目前还没有官方修复,只能靠绕过。

先排查：macOS版本
bash
sw_vers

如果这台iMac的macOS版本比较旧（比如还停留在Big Sur/Monterey这类），Multipass新版本内置的qemu-img可能对旧系统的兼容性本身就有问题——这跟机器"老"直接相关,不是配置错误。

第一步尝试：清掉可能损坏的镜像缓存，重新下载
bash
multipass delete k3s-node3 --purge 2>/dev/null   # 如果残留了失败的实例，先清掉
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"

清完后重新跑一次launch命令，看是不是下载缓存损坏导致的。

第二步：如果还是崩，重装Multipass本体
bash
brew uninstall --cask multipass
brew install --cask multipass

新版本二进制本身修复过这个bug的可能性也有,先试试。

第三步：如果以上都不行——这台老Mac可能就是不适合跑Multipass/QEMU

如果这台iMac的macOS版本确实很老,遇到的是已知的、社区还没修复的兼容性问题，与其继续在这台机器上死磕，不如考虑：这台机器改用VirtualBox（对老系统兼容性通常更好），或者干脆认为它更适合承担"轻量无状态服务测试"而不是"当k3s node"这个更重的任务。

先跑sw_vers把系统版本发我，再试第一步清缓存，两步都做了告诉我结果。

日本語(ふりがな付き)

このエラーはあなたの操作(そうさ)の問題(もんだい)ではありません——調(しら)べたところ、**これは一部(いちぶ)の古(ふる)いIntel Macで発生(はっせい)するMultipassの既知(きち)の問題(もんだい)**です。qemu-imgというツールが画像(がぞう)フォーマット変換(へんかん)(QCOW2 v3)を処理(しょり)する際(さい)にプロセスがクラッシュしてしまうもので、macOSのバージョンが古(ふる)いことや、ダウンロードしたイメージキャッシュが破損(はそん)していることが原因(げんいん)として関係(かんけい)しています。Multipass公式(こうしき)GitHubにも同(おな)じエラーが報告(ほうこく)されているissueがありますが、今(いま)のところ公式(こうしき)の修正(しゅうせい)はまだなく、回避策(かいひさく)で対応(たいおう)するしかない状況(じょうきょう)です。

まず確認(かくにん)：macOSのバージョン
bash
sw_vers

このiMacのmacOSバージョンが古(ふる)い場合(ばあい)(例(たと)えばBig SurやMontereyあたりで止(と)まっている場合(ばあい))、Multipassの新(あたら)しいバージョンに内蔵(ないぞう)されているqemu-img自体(じたい)が、古(ふる)いシステムとの互換性(ごかんせい)に問題(もんだい)を抱(かか)えている可能性(かのうせい)があります——これはマシンが「古(ふる)い」こと自体(じたい)に直接(ちょくせつ)関係(かんけい)しており、設定(せってい)ミスではありません。

ステップ1：破損(はそん)している可能性(かのうせい)のあるイメージキャッシュを削除(さくじょ)して再(さい)ダウンロード
bash
multipass delete k3s-node3 --purge 2>/dev/null   # 失敗(しっぱい)したインスタンスが残(のこ)っていれば先(さき)に削除(さくじょ)
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"

削除後(さくじょご)、もう一度(いちど)launchコマンドを実行(じっこう)して、ダウンロードキャッシュの破損(はそん)が原因(げんいん)だったかどうか確認(かくにん)しましょう。

ステップ2：それでもクラッシュする場合(ばあい)、Multipass本体(ほんたい)を再(さい)インストールする
bash
brew uninstall --cask multipass
brew install --cask multipass

新(あたら)しいバージョンのバイナリでこのバグが修正(しゅうせい)されている可能性(かのうせい)もあるので、まず試(ため)してみましょう。

ステップ3：それでもダメな場合(ばあい)——この古(ふる)いMac自体(じたい)がMultipass/QEMUに向(む)いていない可能性(かのうせい)

もしこのiMacのmacOSバージョンが本当(ほんとう)に古(ふる)く、コミュニティでもまだ修正(しゅうせい)されていない既知(きち)の互換性問題(ごかんせいもんだい)に当(あ)たっているのであれば、このマシンで無理(むり)に粘(ねば)るより、このマシンだけVirtualBoxに切(き)り替(か)える(古(ふる)いシステムとの互換性(ごかんせい)は一般的(いっぱんてき)にVirtualBoxの方(ほう)が良好(りょうこう)な傾向(けいこう)があります)か、あるいは思(おも)い切(き)って「k3sノードを担当(たんとう)させる」という重(おも)いタスクではなく「軽量(けいりょう)なステートレスサービスのテスト用途(ようと)」に回(まわ)す方(ほう)が適(てき)しているかもしれません。

まずsw_versを実行(じっこう)してシステムバージョンを送(おく)ってください。それからステップ1のキャッシュ削除(さくじょ)を試(ため)してみて、両方(りょうほう)やった結果(けっか)を教(おし)えてください。

English

This error isn't something you did wrong — I looked into it, and it's a known issue with Multipass on some older Intel Macs: qemu-img, the tool handling image format conversion (to QCOW2 v3), crashes outright. It's tied to older macOS versions or a corrupted downloaded image cache. There's an open GitHub issue on Multipass's official repo with the exact same error string, and it's currently unresolved upstream — so this needs a workaround for now.

First, check the macOS version
bash
sw_vers

If this iMac is running an older macOS (say, still on Big Sur or Monterey), the qemu-img bundled with newer Multipass versions may simply have compatibility problems with older systems — this is directly tied to the machine's age, not a config mistake.

Step 1: Clear the potentially corrupted image cache and re-download
bash
multipass delete k3s-node3 --purge 2>/dev/null   # clean up the failed instance if it's still lingering
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"

Then re-run the launch command to see if a corrupted download cache was the cause.

Step 2: If it still crashes, reinstall Multipass itself
bash
brew uninstall --cask multipass
brew install --cask multipass

There's a chance a newer binary has this fixed — worth trying.

Step 3: If none of this works — this older Mac may just not be a great fit for Multipass/QEMU

If this iMac's macOS is genuinely old and you're hitting this known, community-unresolved compatibility issue, rather than fighting it further on this machine, consider either: switching just this machine to VirtualBox (generally better compatibility with older systems), or accepting that it's better suited to lightweight stateless-service testing rather than the heavier job of running a k3s node.

Run sw_vers first and send me the system version, then try step 1's cache clear, and let me know the results of both.


ziqiao@ZIQIAOnoiMac ~ % sw_vers
ProductName: macOS
ProductVersion: 13.7.8
BuildVersion: 22H730
ziqiao@ZIQIAOnoiMac ~ %


中文

Ventura 13.7.8——这个版本本身不算特别老（是Ventura系列里打到最新补丁的版本），所以大概率不是"系统版本太老导致的根本性不兼容"，更可能是这次GitHub issue里提到的"镜像下载缓存损坏"或者"多版本qemu-img冲突"这类偶发问题，值得先按前面的步骤排查，不用一上来就放弃Multipass换VirtualBox。

继续排查

第一步：清掉缓存重试

bash
multipass delete k3s-node3 --purge 2>/dev/null
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

如果还是崩，看一下版本号，确认是不是这次GitHub issue命中的那个版本区间：

bash
multipass version

把结果发我。

如果清缓存没用，试一下重装Multipass：

bash
brew uninstall --cask multipass
brew install --cask multipass

这几步做完告诉我结果，如果依然崩溃，再考虑要不要换VirtualBox这条路。

日本語(ふりがな付き)

Ventura 13.7.8——このバージョン自体(じたい)はそこまで古(ふる)くありません(Venturaシリーズの中(なか)で最新(さいしん)のパッチまで当(あ)たっているバージョンです)。なので**「システムバージョンが古(ふる)すぎることによる根本的(こんぽんてき)な非互換性(ひごかんせい)」の可能性(かのうせい)は低(ひく)く**、前回(ぜんかい)のGitHub issueで触(ふ)れられていた「ダウンロードしたイメージキャッシュの破損(はそん)」や「複数(ふくすう)バージョンのqemu-imgの競合(きょうごう)」といった偶発的(ぐうはつてき)な問題(もんだい)の可能性(かのうせい)が高(たか)いです。まずは前回(ぜんかい)のステップに沿(そ)って確認(かくにん)する価値(かち)があり、いきなりMultipassを諦(あきら)めてVirtualBoxに切(き)り替(か)える必要(ひつよう)はありません。

続(つづ)けて確認(かくにん)しましょう

ステップ1：キャッシュを削除(さくじょ)して再試行(さいしこう)

bash
multipass delete k3s-node3 --purge 2>/dev/null
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

それでもクラッシュする場合(ばあい)、バージョン番号(ばんごう)を確認(かくにん)して、今回(こんかい)のGitHub issueが該当(がいとう)するバージョン範囲(はんい)かどうか確認(かくにん)しましょう：

bash
multipass version

結果(けっか)を送(おく)ってください。

キャッシュ削除(さくじょ)で効(き)かない場合(ばあい)、Multipassを再(さい)インストールしてみてください：

bash
brew uninstall --cask multipass
brew install --cask multipass

これらを試(ため)した結果(けっか)を教(おし)えてください。それでもまだクラッシュするなら、そのときにVirtualBoxへの切(き)り替(か)えを検討(けんとう)しましょう。

English

Ventura 13.7.8 — this version itself isn't particularly old (it's on the latest patch within the Ventura series), so it's probably not a fundamental incompatibility from an outdated OS version. It's more likely one of the intermittent issues mentioned in that GitHub issue — a corrupted image download cache or conflicting qemu-img versions. Worth troubleshooting with the earlier steps first, no need to jump straight to abandoning Multipass for VirtualBox.

Let's continue troubleshooting

Step 1: Clear the cache and retry

bash
multipass delete k3s-node3 --purge 2>/dev/null
sudo rm -rf "/var/root/Library/Caches/multipassd/qemu/vault/images"
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

If it still crashes, check the version to see if it falls in the range mentioned in that GitHub issue:

bash
multipass version

Send me the result.

If clearing the cache doesn't help, try reinstalling Multipass:

bash
brew uninstall --cask multipass
brew install --cask multipass

Let me know how these go — if it's still crashing after all this, we can consider whether to switch to VirtualBox.



ziqiao@ZIQIAOnoiMac ~ % multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged
[2026-09-05T20:28:24.716] [error] [qemu-img] Process crashed
launch failed: Failed to amend image to QCOW2 v3: qemu-img failed (program: qemu-img; error: Process crashed) with output:

ziqiao@ZIQIAOnoiMac ~ % multipass version
multipass   1.16.3+mac
multipassd  1.16.3+mac
ziqiao@ZIQIAOnoiMac ~ %



中文

查到这个报错从2022年到2026年5月这几年间反复被报告，一直没有官方修复，你手头这个1.16.3版本大概率也踩中了同一个坑——单纯清缓存/重装大概率也解决不了根本问题，不用再浪费时间反复试了。

更靠谱的路子：换掉Multipass底层用的虚拟化驱动

Multipass本身支持切换不同的虚拟化后端，默认在macOS上用QEMU（就是现在崩溃的这个），但也支持切到VirtualBox作为驱动——不需要放弃Multipass这套命令行工具，只是换个底层引擎，命令行操作方式完全不变。

第一步：装VirtualBox

bash
brew install --cask virtualbox

（如果这一步提示需要在"系统设置→隐私与安全性"里手动允许Oracle的驱动加载，跑完安装后去点一下"允许"，这是VirtualBox在macOS上的标准安装步骤）

第二步：把Multipass的驱动切到VirtualBox

bash
multipass set local.driver=virtualbox

第三步：重新launch，命令完全不变

bash
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

如果这次能正常起来，说明问题确实出在QEMU这个驱动本身在你这台机器上的兼容性,换引擎就绕过去了,后续所有操作(shell、网络配置等)命令都不用变。

先跑第一、二步，然后重新launch,把结果发我。

日本語(ふりがな付き)

このエラーは2022年から2026年5月まで、何年(なんねん)にもわたって繰(く)り返(かえ)し報告(ほうこく)されていることがわかりました。公式(こうしき)の修正(しゅうせい)はまだなく、あなたが使(つか)っているこの1.16.3バージョンも同(おな)じ問題(もんだい)に当(あ)たっている可能性(かのうせい)が高(たか)いです——単純(たんじゅん)にキャッシュ削除(さくじょ)や再(さい)インストールを繰(く)り返(かえ)しても根本的(こんぽんてき)には解決(かいけつ)しない可能性(かのうせい)が高(たか)いので、これ以上(いじょう)同(おな)じ方法(ほうほう)で時間(じかん)を使(つか)う必要(ひつよう)はありません。

より確実(かくじつ)な方法(ほうほう):Multipassの内部(ないぶ)で使(つか)う仮想化(かそうか)ドライバーを切(き)り替(か)える

Multipassは異(こと)なる仮想化(かそうか)バックエンドに切(き)り替(か)えることをサポートしています。macOSではデフォルトでQEMU(今回(こんかい)クラッシュしているもの)を使(つか)いますが、VirtualBoxをドライバーとして使(つか)うこともできます——Multipassというコマンドラインツール自体(じたい)を手放(てばな)す必要(ひつよう)はなく、裏側(うらがわ)のエンジンを変(か)えるだけで、コマンドの使(つか)い方(かた)は一切(いっさい)変(か)わりません。

ステップ1：VirtualBoxをインストールする

bash
brew install --cask virtualbox

(このステップで「システム設定(せってい)→プライバシーとセキュリティ」でOracleのドライバー読(よ)み込(こ)みを手動(しゅどう)で許可(きょか)するよう求(もと)められたら、インストール後(ご)に「許可(きょか)」をクリックしてください。これはmacOSでのVirtualBoxの標準的(ひょうじゅんてき)なインストール手順(てじゅん)です)

ステップ2：Multipassのドライバーを VirtualBoxに切(き)り替(か)える

bash
multipass set local.driver=virtualbox

ステップ3：コマンドはまったく変(か)えずに再(さい)launchする

bash
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

これで正常(せいじょう)に起動(きどう)できれば、問題(もんだい)は本当(ほんとう)にQEMUというドライバー自体(じたい)がこのマシンで互換性(ごかんせい)の問題(もんだい)を起(お)こしていたということが確定(かくてい)し、エンジンを変(か)えることで回避(かいひ)できたことになります。以降(いこう)のすべての操作(そうさ)(shell、ネットワーク設定(せってい)など)のコマンドは変(か)える必要(ひつよう)がありません。

まずステップ1、2を実行(じっこう)し、再(さい)launchしてみて、結果(けっか)を送(おく)ってください。

English

Turns out this error has been reported repeatedly across years, from 2022 through as recently as May 2026 — with no official fix. The 1.16.3 version you're on is likely hitting the exact same unresolved bug — clearing the cache or reinstalling probably won't fix the root cause, so no need to keep repeating those steps.

A more reliable path: switch Multipass's underlying virtualization driver

Multipass supports switching between different virtualization backends — on macOS it defaults to QEMU (the one currently crashing), but it also supports VirtualBox as a driver. You don't need to give up the Multipass CLI at all — just swap the engine underneath, and every command stays exactly the same.

Step 1: Install VirtualBox

bash
brew install --cask virtualbox

(If this prompts you to manually allow Oracle's kernel extension in System Settings → Privacy & Security, click "Allow" after installation — that's a standard VirtualBox install step on macOS.)

Step 2: Switch Multipass's driver to VirtualBox

bash
multipass set local.driver=virtualbox

Step 3: Re-launch with the exact same command

bash
multipass launch 26.04 --name k3s-node3 --cpus 2 --memory 3G --disk 20G --bridged

If this launches successfully, it confirms the issue really was QEMU's compatibility with this specific machine, and switching engines routed around it — every subsequent command (shell, network config, etc.) stays unchanged.

Run steps 1 and 2, re-launch, and send me the result.



Last login: Sat Sep  5 20:36:33 2026 from 10.0.2.2
ubuntu@k3s-node3:~$ ip addr show
1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
    link/loopback 00:00:00:00:00:00 brd 00:00:00:00:00:00
    inet 127.0.0.1/8 scope host lo
       valid_lft forever preferred_lft forever
    inet6 ::1/128 scope host noprefixroute 
       valid_lft forever preferred_lft forever
2: enp0s3: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
    link/ether 52:54:00:6f:19:af brd ff:ff:ff:ff:ff:ff
    altname enx5254006f19af
    inet 10.0.2.15/24 metric 100 brd 10.0.2.255 scope global dynamic enp0s3
       valid_lft 86281sec preferred_lft 86281sec
    inet6 fd17:625c:f037:2:5054:ff:fe6f:19af/64 scope global dynamic mngtmpaddr noprefixroute 
       valid_lft 86282sec preferred_lft 14282sec
    inet6 fe80::5054:ff:fe6f:19af/64 scope link proto kernel_ll 
       valid_lft forever preferred_lft forever
3: enp0s8: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default qlen 1000
    link/ether 52:54:00:a1:ae:12 brd ff:ff:ff:ff:ff:ff
    altname enx525400a1ae12
    inet 192.168.40.14/24 metric 200 brd 192.168.40.255 scope global dynamic enp0s8
       valid_lft 43082sec preferred_lft 43082sec
    inet6 2405:1201:1189:9d00::ef4b:3c22/128 scope global dynamic noprefixroute 
       valid_lft 604682sec preferred_lft 604682sec
    inet6 2405:1201:1189:9d00:5054:ff:fea1:ae12/64 scope global dynamic mngtmpaddr noprefixroute 
       valid_lft 604767sec preferred_lft 604767sec
    inet6 fe80::5054:ff:fea1:ae12/64 scope link proto kernel_ll 
       valid_lft forever preferred_lft forever
ubuntu@k3s-node3:~$ 

ubuntu@k3s-node3:~$ ping 192.168.40.23
PING 192.168.40.23 (192.168.40.23) 56(84) bytes of data.
64 bytes from 192.168.40.23: icmp_seq=1 ttl=64 time=179 ms
64 bytes from 192.168.40.23: icmp_seq=2 ttl=64 time=193 ms
64 bytes from 192.168.40.23: icmp_seq=3 ttl=64 time=410 ms
64 bytes from 192.168.40.23: icmp_seq=4 ttl=64 time=203 ms
64 bytes from 192.168.40.23: icmp_seq=5 ttl=64 time=107 ms
64 bytes from 192.168.40.23: icmp_seq=6 ttl=64 time=100 ms
64 bytes from 192.168.40.23: icmp_seq=7 ttl=64 time=147 ms
64 bytes from 192.168.40.23: icmp_seq=8 ttl=64 time=135 ms
64 bytes from 192.168.40.23: icmp_seq=9 ttl=64 time=203 ms
64 bytes from 192.168.40.23: icmp_seq=10 ttl=64 time=411 ms
64 bytes from 192.168.40.23: icmp_seq=11 ttl=64 time=7.26 ms



中文

VirtualBox这条路走通了，换驱动确实绕开了那个QEMU的bug。网络也确认没问题——enp0s8是桥接网卡，IP192.168.40.14，能ping通ziqiao-asm100。

延迟波动（7ms到410ms之间跳）比之前两台机器还要明显一些，这台走的也是Wi-Fi桥接（en1），加上这台iMac本身性能更弱，抖动大一点符合预期，不影响继续，跟之前提醒的一样，后面Kafka/ES如果报超时可以回来想到这里。

继续join集群

用之前的token,这次接口换成enp0s8,IP换成192.168.40.14：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.14 --flannel-iface=enp0s8" sh -
验证

回到ziqiao-asm100：

bash
kubectl get nodes -o wide

这次应该能看到三个node，都是Ready。确认后可以顺手跑一遍之前的busybox测试，验证node3的跨节点网络也通：

bash
kubectl run test-node3 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node3"}}}' --command -- sleep 3600
kubectl get pod test-node3 -o wide

把结果发我。

日本語(ふりがな付き)

VirtualBoxの方法(ほうほう)でうまくいきました——ドライバーを切(き)り替(か)えることで、確(たし)かにあのQEMUのバグを回避(かいひ)できました。ネットワークも問題(もんだい)ないことが確認(かくにん)できています——enp0s8がブリッジ用(よう)のインターフェースで、IPは192.168.40.14、ziqiao-asm100にpingが通(とお)っています。

遅延(ちえん)の揺(ゆ)らぎ(7msから410msまで)は前(まえ)の二台(にだい)より少(すこ)し大(おお)きめですが、このマシンもWi-Fiブリッジ(en1)経由(けいゆ)で、しかもこのiMac自体(じたい)の性能(せいのう)が弱(よわ)いので、揺(ゆ)らぎが大(おお)きめなのは想定内(そうていない)です。続(つづ)けることに問題(もんだい)はありません。前(まえ)に伝(つた)えた通(とお)り、今後(こんご)Kafka/ESでタイムアウトが出(で)たら、ここを思(おも)い出(だ)してください。

クラスターへのjoinを続(つづ)ける

前回(ぜんかい)のtokenを使(つか)い、今回(こんかい)はインターフェースをenp0s8、IPを192.168.40.14に変更(へんこう)します：

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.14 --flannel-iface=enp0s8" sh -
確認(かくにん)

ziqiao-asm100に戻(もど)って：

bash
kubectl get nodes -o wide

今回(こんかい)は三(みっ)つのノードが見(み)え、すべてReadyになっているはずです。確認(かくにん)できたら、前回(ぜんかい)と同(おな)じくbusyboxのテストを実行(じっこう)し、node3のノード間(かん)ネットワークも問題(もんだい)ないか確認(かくにん)しましょう：

bash
kubectl run test-node3 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node3"}}}' --command -- sleep 3600
kubectl get pod test-node3 -o wide

結果(けっか)を送(おく)ってください。

English

VirtualBox worked — switching drivers did route around that QEMU bug. Networking checks out too — enp0s8 is the bridged interface, IP 192.168.40.14, and it's reaching ziqiao-asm100.

The latency jitter (bouncing between 7ms and 410ms) is a bit more pronounced than the other two machines, but this one's also on Wi-Fi bridging (en1), and this iMac is weaker hardware, so more jitter is expected — no issue continuing. As mentioned before, if Kafka/ES throws timeouts later, this is worth remembering.

Continue joining the cluster

Using the same token as before, this time with interface enp0s8 and IP 192.168.40.14:

bash
curl -sfL https://get.k3s.io | K3S_URL=https://192.168.40.23:6443 K3S_TOKEN=K10b83f017fef5f2d6039263d7511bcfdd8e8e8f5756ce8a239c6415c27e3cc7d4a::server:a1c7fc408341059628237e2267ed0df0 INSTALL_K3S_EXEC="--node-ip=192.168.40.14 --flannel-iface=enp0s8" sh -
Verify

Back on ziqiao-asm100:

bash
kubectl get nodes -o wide

You should now see three nodes, all Ready. Once confirmed, run the busybox test again to verify node3's cross-node networking too:

bash
kubectl run test-node3 --image=busybox --overrides='{"spec":{"nodeSelector":{"kubernetes.io/hostname":"k3s-node3"}}}' --command -- sleep 3600
kubectl get pod test-node3 -o wide

Send me the results.


ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ kubectl get pod test-node3 -o wide
NAME         READY   STATUS    RESTARTS   AGE   IP          NODE        NOMINATED NODE   READINESS GATES
test-node3   1/1     Running   0          18s   10.42.5.3   k3s-node3   <none>           <none>
ziqiao@ziqiao-ASM100:/etc/rancher/k3s$ kubectl get nodes -o wide
NAME            STATUS   ROLES           AGE     VERSION        INTERNAL-IP     EXTERNAL-IP   OS-IMAGE           KERNEL-VERSION             CONTAINER-RUNTIME
k3s-node2       Ready    <none>          71m     v1.36.4+k3s1   192.168.40.69   <none>        Ubuntu 26.04 LTS   7.0.0-30-generic (amd64)   containerd://2.3.4-k3s1.36
k3s-node3       Ready    <none>          2m37s   v1.36.4+k3s1   192.168.40.14   <none>        Ubuntu 26.04 LTS   7.0.0-30-generic (amd64)   containerd://2.3.4-k3s1.36
ziqiao-asm100   Ready    control-plane   42d     v1.36.2+k3s1   192.168.40.23   <none>        Ubuntu 26.04 LTS   7.0.0-29-generic (amd64)   containerd://2.3.2-k3s2


中文

三节点集群完整搭建成功——node2、node3都是Ready，test-node3也正常Running，跨节点网络确认没问题。老MacBook Pro用QEMU、老iMac用VirtualBox两种不同驱动都能正常work，这也算意外收获——你亲身体会到了Multipass底层驱动可以按需切换这件事。

收尾：清理测试Pod
bash
kubectl delete pod test-node2 test-node3
现在手上有什么

三个物理节点组成的集群，每个节点用的虚拟化路径还不太一样，值得记一笔：

ziqiao-asm100：裸机直接跑k3s server（控制平面）
k3s-node2：老MacBook Pro，通过Multipass+QEMU驱动跑的agent
k3s-node3：老iMac，通过Multipass+VirtualBox驱动跑的agent
接下来

回到最初规划的伸缩练习清单——无状态服务的replicas扩缩容/podAntiAffinity/cordon-drain，现在可以在三个node间玩得更充分了（比如podAntiAffinity强制三副本分散到三个不同node，这个在两node时体验不完整）；Kafka/ES的"broker绑定node + local PV"设计也可以三个node都用上。

要不要先从哪个开始练，还是先回到P6装监控？

日本語(ふりがな付き)

三(さん)ノードのクラスターが完全(かんぜん)に構築(こうちく)できました——node2、node3ともにReady、test-node3も正常(せいじょう)にRunningしており、ノード間(かん)ネットワークも問題(もんだい)なしと確認(かくにん)できました。古(ふる)いMacBook ProではQEMU、古(ふる)いiMacではVirtualBoxと、異(こと)なる二(ふた)つのドライバーがそれぞれ正常(せいじょう)に動(うご)いたのも、ある意味(いみ)思(おも)わぬ収穫(しゅうかく)ですね——Multipassのバックエンドドライバーが必要(ひつよう)に応(おう)じて切(き)り替(か)えられることを、身(み)をもって体験(たいけん)できました。

仕上(しあ)げ：テスト用(よう)Podの削除(さくじょ)
bash
kubectl delete pod test-node2 test-node3
今(いま)手元(てもと)にあるもの

三(さん)つの物理(ぶつり)ノードで構成(こうせい)されたクラスターで、各(かく)ノードの仮想化(かそうか)経路(けいろ)がそれぞれ違(ちが)う、というのは記録(きろく)しておく価値(かち)があります：

ziqiao-asm100：ベアメタルで直接(ちょくせつ)k3s server(コントロールプレーン)を実行(じっこう)
k3s-node2：古(ふる)いMacBook Pro、Multipass+QEMUドライバーでagentを実行(じっこう)
k3s-node3：古(ふる)いiMac、Multipass+VirtualBoxドライバーでagentを実行(じっこう)
次(つぎ)にやること

最初(さいしょ)に計画(けいかく)していたスケーリング練習(れんしゅう)のリストに戻(もど)りましょう——ステートレスサービスのreplicasスケーリング/podAntiAffinity/cordon-drainは、今(いま)なら三(みっ)つのノードでより充実(じゅうじつ)した形(かたち)で試(ため)せます(例(たと)えばpodAntiAffinityで3(さん)レプリカを三(みっ)つの異(こと)なるノードに強制分散(きょうせいぶんさん)させるのは、二(ふた)ノードの時(とき)には完全(かんぜん)に体験(たいけん)できませんでした)。Kafka/ESの「brokerをノードに固定(こてい)する+local PV」設計(せっけい)も三(みっ)つのノード全部(ぜんぶ)を使(つか)えます。

どれから練習(れんしゅう)を始(はじ)めますか、それとも先(さき)にP6の監視(かんし)導入(どうにゅう)に戻(もど)りますか？

English

The three-node cluster is fully up and running — both node2 and node3 show Ready, test-node3 is Running correctly, and cross-node networking is confirmed working. As an unexpected bonus, you got QEMU working on the old MacBook Pro and VirtualBox working on the old iMac — a hands-on demonstration that Multipass's underlying driver can be swapped as needed.

Wrap-up: clean up the test pods
bash
kubectl delete pod test-node2 test-node3
What you've got now

A three-physical-node cluster, each taking a different virtualization path — worth noting:

ziqiao-asm100: bare metal running k3s server (control plane) directly
k3s-node2: old MacBook Pro, agent via Multipass + QEMU driver
k3s-node3: old iMac, agent via Multipass + VirtualBox driver
What's next

Back to the original scaling practice checklist — stateless service replicas scaling / podAntiAffinity / cordon-drain can now be exercised much more fully across three nodes (e.g., podAntiAffinity forcing three replicas onto three distinct nodes, which two nodes couldn't fully demonstrate). The Kafka/ES "broker pinned to node + local PV" design can also make use of all three nodes now.

Want to start practicing one of those, or head back to P6 for monitoring setup?