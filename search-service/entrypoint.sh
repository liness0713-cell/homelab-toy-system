#!/bin/sh
set -e

# 把ECK自动生成的CA证书，导入一份Java能用的truststore
keytool -importcert \
  -trustcacerts \
  -noprompt \
  -alias es-ca \
  -file /certs/ca.crt \
  -keystore /tmp/truststore.jks \
  -storepass changeit

# 把truststore路径告诉JVM，再启动应用
exec java \
  -Djavax.net.ssl.trustStore=/tmp/truststore.jks \
  -Djavax.net.ssl.trustStorePassword=changeit \
  -jar app.jar