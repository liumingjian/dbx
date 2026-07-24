FROM confluentinc/cp-kafka-connect:7.9.0

USER root

# 研究票 #2（CCL 许可合规）：cp-kafka-connect 镜像内含 Confluent Enterprise License 的
# confluent-hub-client。实验床用不到它（插件由 fetch-plugins.sh 离线放进来），删掉，
# 顺便让本地环境与将来可分发的发行镜像保持同一形态。
RUN rm -rf /usr/share/confluent-hub-components \
           /usr/share/java/confluent-hub-client \
           /usr/bin/confluent-hub

# fetch-plugins.sh 预先下载解包的内容：
#   confluentinc-kafka-connect-jdbc-10.9.6/   （含 postgresql-42.7.11.jar）
#   confluentinc-kafka-connect-jdbc-10.9.6/lib/mysql-connector-j-9.1.0.jar
COPY plugins/ /opt/dbx-plugins/

USER appuser
