# cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
# EOF

# sudo systemctl restart k3s



不用改名字，要改的是"部署范围" / 名前は変えなくていい、直すのは配布範囲 / Don't rename it — fix the deployment scope

中文： 这里有个关键点容易搞反：registries.yaml 里 mirrors."localhost:5000" 这个key，不是让containerd去DNS解析"localhost"，而是字符串匹配——只要镜像引用里写的host是localhost:5000，containerd就会拦截并重定向到你配置的endpoint（http://192.168.40.23:5000）。所以理论上不管在哪个node上，只要这份registries.yaml存在，localhost:5000都能正确重定向到真实IP，不需要改成IP当key名。

真正的问题大概率是：这份配置文件你可能只放在了192.168.40.23那台机器上，其他node没有/etc/rancher/k3s/registries.yaml，所以它们对localhost:5000的解析就是字面意义上的"自己的localhost"，当然拉不到。

修复方法：
先创建目录，tee不能创建不存在的目录
sudo mkdir -p /etc/rancher/k3s

bash
# 在每一台node上都要有这份文件（server和agent都算）
cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
EOF

然后重启对应服务——注意server和agent节点的service名不一样：

bash
# 在server(control-plane)节点
sudo systemctl restart k3s

# 在worker/agent节点
sudo systemctl restart k3s-agent

验证可以用：

bash
sudo crictl pull localhost:5000/search-service:202609020807-3805cc8

日本語（📝ふりがな付き）： ここ、勘違(かんちが)いしやすいポイントです。registries.yaml の mirrors."localhost:5000" というkeyは、containerdが"localhost"をDNS解決(かいけつ)しに行(い)くわけではなく、**文字列(もじれつ)一致(いっち)**で動(うご)いています。イメージ参照(さんしょう)のhostがlocalhost:5000と書(か)いてあれば、containerdがそれを横取(よこど)りして、指定(してい)したendpoint（http://192.168.40.23:5000）にリダイレクトします。だから理論上(りろんじょう)は、どのnodeにいてもこのregistries.yamlさえあればlocalhost:5000は正(ただ)しく実際(じっさい)のIPにリダイレクトされます。keyの名前(なまえ)をIPに変(か)える必要(ひつよう)はありません。

本当(ほんとう)の原因(げんいん)はおそらく：この設定(せってい)ファイルが192.168.40.23のマシンにしか置(お)かれていないことです。他(ほか)のnodeには/etc/rancher/k3s/registries.yamlが存在(そんざい)しないので、localhost:5000は文字通(もじどお)り「自分自身(じぶんじしん)のlocalhost」として解釈(かいしゃく)され、当然(とうぜん)pullできません。

修正方法(しゅうせいほうほう)：

bash
# 全(すべ)てのnodeにこのファイルが必要(ひつよう)（serverもagentも）
cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
EOF

その後(あと)、対応(たいおう)するサービスを再起動(さいきどう)します——serverノードとagentノードでサービス名(めい)が違(ちが)う点(てん)に注意(ちゅうい)：

bash
# serverノード（control-plane）で
sudo systemctl restart k3s

# worker/agentノードで
sudo systemctl restart k3s-agent

確認(かくにん)コマンド：

bash
sudo crictl pull localhost:5000/search-service:202609020807-3805cc8

日本語（💬自然）： ここ、勘違いしやすいポイントです。registries.yaml の mirrors."localhost:5000" というkeyは、containerdが"localhost"をDNS解決しに行くわけではなく、文字列一致で動いています。イメージ参照のhostがlocalhost:5000と書いてあれば、containerdがそれを横取りして、指定したendpoint（http://192.168.40.23:5000）にリダイレクトします。だから理論上は、どのnodeにいてもこのregistries.yamlさえあればlocalhost:5000は正しく実際のIPにリダイレクトされます。keyの名前をIPに変える必要はありません。

本当の原因はおそらく：この設定ファイルが192.168.40.23のマシンにしか置かれていないことです。他のnodeには/etc/rancher/k3s/registries.yamlが存在しないので、localhost:5000は文字通り「自分自身のlocalhost」として解釈され、当然pullできません。

修正方法：

bash
# 全てのnodeにこのファイルが必要（serverもagentも）
cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
EOF

その後、対応するサービスを再起動します——serverノードとagentノードでサービス名が違う点に注意：

bash
# serverノード（control-plane）で
sudo systemctl restart k3s

# worker/agentノードで
sudo systemctl restart k3s-agent

確認コマンド：

bash
sudo crictl pull localhost:5000/search-service:202609020807-3805cc8

English: The confusing part here: the mirrors."localhost:5000" key in registries.yaml doesn't make containerd DNS-resolve "localhost" — it's a string match. Any pull request whose image host string is localhost:5000 gets intercepted and redirected to whatever endpoint you configured (http://192.168.40.23:5000). So in theory, as long as this registries.yaml exists on a node, localhost:5000 will correctly redirect to the real IP there — no need to rename the key to an IP.

The real problem is almost certainly: this config file only exists on the 192.168.40.23 machine. Other nodes don't have /etc/rancher/k3s/registries.yaml, so on them localhost:5000 is interpreted literally as "my own localhost" — which of course fails to pull.

Fix:

bash
# every node needs this file — servers and agents alike
cat <<'EOF' | sudo tee /etc/rancher/k3s/registries.yaml
mirrors:
  "localhost:5000":
    endpoint:
      - "http://192.168.40.23:5000"
configs:
  "localhost:5000":
    tls:
      insecure_skip_verify: true
EOF

Then restart the matching service — note the service name differs between server and agent nodes:

bash
# on the server (control-plane) node
sudo systemctl restart k3s

# on worker/agent nodes
sudo systemctl restart k3s-agent

Verify with:

bash
sudo crictl pull localhost:5000/search-service:202609020807-3805cc8

sudo crictl pull localhost:5000/search-service:202609020807-3805cc8
这句是什么意思？

这条命令是什么意思 / このコマンドの意味 / What this command means

中文： 这是让节点上的containerd直接去拉取（下载）这个镜像，不通过Kubernetes调度，用来单独验证"这台机器能不能连到你的私有镜像仓库"。

逐段拆解：

sudo —— crictl需要访问containerd的socket，一般要root权限
crictl —— Kubernetes容器运行时的命令行工具（跟docker类似，但操作的是containerd/CRI-O这类底层runtime）
pull —— 拉取镜像的子命令
localhost:5000/search-service:202609020807-3805cc8 —— 镜像的完整地址：localhost:5000是仓库host（会被刚才配置的registries.yaml重定向到192.168.40.23:5000），search-service是镜像名，202609020807-3805cc8是tag（看起来是"时间戳+git commit短哈希"的自动生成tag）

用它测试的好处是：跳过Pod调度、跳过Deployment，直接问containerd"你能不能连到仓库、下载到这个镜像"，把问题范围缩小到网络/镜像仓库这一层，排除掉K8s其他部分的干扰。

如果拉取成功，会看到类似Image is up to date for sha256:...；如果失败，报错信息（比如connection refused、x509证书错误等）能直接告诉你是网络不通还是TLS配置的问题。

日本語（📝ふりがな付き）： これは、ノード上(じょう)のcontainerdに直接(ちょくせつ)このイメージをpull（ダウンロード）させるコマンドです。Kubernetesのスケジューリングを経由(けいゆ)せず、「このマシンがプライベートレジストリに接続(せつぞく)できるか」を単独(たんどく)で確認(かくにん)するために使(つか)います。

分解(ぶんかい)すると：

sudo —— crictlはcontainerdのsocketにアクセスする必要(ひつよう)があり、通常(つうじょう)root権限(けんげん)が要(い)ります
crictl —— Kubernetesのコンテナランタイム用(よう)コマンドラインツール（dockerに似(に)ていますが、containerdやCRI-Oのような低(ひく)いレイヤーのruntimeを操作(そうさ)します）
pull —— イメージを取得(しゅとく)するサブコマンド
localhost:5000/search-service:202609020807-3805cc8 —— イメージの完全(かんぜん)なアドレス：localhost:5000はレジストリのhost（先(さき)ほど設定(せってい)したregistries.yamlによって192.168.40.23:5000にリダイレクトされます）、search-serviceはイメージ名(めい)、202609020807-3805cc8はtag（「タイムスタンプ＋gitコミットの短(みじか)いハッシュ」を自動生成(じどうせいせい)したもののようです）

このコマンドでテストする利点(りてん)は、PodのスケジューリングやDeploymentを飛(と)ばして、containerdに直接(ちょくせつ)「レジストリに繋(つな)がるか、このイメージをダウンロードできるか」だけを聞(き)けることです。問題(もんだい)の範囲(はんい)をネットワーク／レジストリの層(そう)に絞(しぼ)り込(こ)み、K8sの他(ほか)の部分(ぶぶん)の影響(えいきょう)を排除(はいじょ)できます。

成功(せいこう)すればImage is up to date for sha256:...のような表示(ひょうじ)が出(で)ます。失敗(しっぱい)した場合(ばあい)、エラーメッセージ（connection refusedやx509証明書(しょうめいしょ)エラーなど）が、ネットワーク不通(ふつう)なのかTLS設定(せってい)の問題(もんだい)なのかを直接(ちょくせつ)教(おし)えてくれます。

日本語（💬自然）： これは、ノード上のcontainerdに直接このイメージをpull（ダウンロード）させるコマンドです。Kubernetesのスケジューリングを経由せず、「このマシンがプライベートレジストリに接続できるか」を単独で確認するために使います。

分解すると：

sudo —— crictlはcontainerdのsocketにアクセスする必要があり、通常root権限が要ります
crictl —— Kubernetesのコンテナランタイム用コマンドラインツール（dockerに似ていますが、containerdやCRI-Oのような低いレイヤーのruntimeを操作します）
pull —— イメージを取得するサブコマンド
localhost:5000/search-service:202609020807-3805cc8 —— イメージの完全なアドレス：localhost:5000はレジストリのhost（先ほど設定したregistries.yamlによって192.168.40.23:5000にリダイレクトされます）、search-serviceはイメージ名、202609020807-3805cc8はtag（「タイムスタンプ＋gitコミットの短いハッシュ」を自動生成したもののようです）

このコマンドでテストする利点は、PodのスケジューリングやDeploymentを飛ばして、containerdに直接「レジストリに繋がるか、このイメージをダウンロードできるか」だけを聞けることです。問題の範囲をネットワーク／レジストリの層に絞り込み、K8sの他の部分の影響を排除できます。

成功すればImage is up to date for sha256:...のような表示が出ます。失敗した場合、エラーメッセージ（connection refusedやx509証明書エラーなど）が、ネットワーク不通なのかTLS設定の問題なのかを直接教えてくれます。

English: This tells containerd on the node to pull (download) the image directly, bypassing Kubernetes scheduling entirely — a way to test in isolation whether this machine can reach your private registry.

Breaking it down:

sudo — crictl needs to talk to containerd's socket, which usually requires root
crictl — the CLI tool for Kubernetes container runtimes (similar to docker, but it operates on lower-level runtimes like containerd or CRI-O)
pull — the subcommand to fetch an image
localhost:5000/search-service:202609020807-3805cc8 — the full image reference: localhost:5000 is the registry host (redirected to 192.168.40.23:5000 by the registries.yaml you just configured), search-service is the image name, and 202609020807-3805cc8 is the tag (looks like an auto-generated timestamp + short git commit hash)

The benefit of testing this way is that it skips Pod scheduling and Deployments entirely, and asks containerd directly "can you reach the registry and download this image?" — narrowing the problem down to just the network/registry layer, ruling out anything else in K8s.

On success you'll see something like Image is up to date for sha256:.... On failure, the error (connection refused, x509 certificate errors, etc.) will tell you directly whether it's a network issue or a TLS config issue.