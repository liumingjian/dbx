#!/usr/bin/env bash
# 下载 Connect 插件到 ./connect/plugins/，供 connect/Dockerfile COPY 进镜像。
# 只需在首次启动前、或改版本号时跑一次。需要外网。
set -euo pipefail

cd "$(dirname "$0")"

JDBC_VERSION=10.9.6
JDBC_ZIP="confluentinc-kafka-connect-jdbc-${JDBC_VERSION}.zip"
JDBC_URL="https://hub-downloads.confluent.io/api/plugins/confluentinc/kafka-connect-jdbc/versions/${JDBC_VERSION}/${JDBC_ZIP}"
JDBC_SHA256=1581f133644c34b9a6cfcf0a6f2011fc1c66ecbb458a175c936018a38b72be27

# 研究票 #2：MySQL Connector/J 是 GPLv2+UFE，绝不能打进 DBX 发行包——Confluent 自己也不打包它。
# 这里是本地实验床，自行下载无妨；但产品安装器必须走「引导客户自备」的路子。
MYSQL_DRIVER_VERSION=9.1.0
MYSQL_DRIVER_JAR="mysql-connector-j-${MYSQL_DRIVER_VERSION}.jar"
MYSQL_DRIVER_URL="https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/${MYSQL_DRIVER_VERSION}/${MYSQL_DRIVER_JAR}"

PLUGIN_DIR="connect/plugins"
JDBC_DIR="${PLUGIN_DIR}/confluentinc-kafka-connect-jdbc-${JDBC_VERSION}"

extract() {  # 有 unzip 用 unzip，没有就用 python3
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$1" -d "$2"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -m zipfile -e "$1" "$2"
  else
    echo "需要 unzip 或 python3 来解压 $1" >&2
    exit 1
  fi
}

mkdir -p "$PLUGIN_DIR"

if [ ! -d "$JDBC_DIR" ]; then
  echo "==> 下载 kafka-connect-jdbc ${JDBC_VERSION}（约 26MB）"
  curl -fSL --retry 3 -o "/tmp/${JDBC_ZIP}" "$JDBC_URL"
  echo "${JDBC_SHA256}  /tmp/${JDBC_ZIP}" | sha256sum -c -
  extract "/tmp/${JDBC_ZIP}" "$PLUGIN_DIR"
  rm -f "/tmp/${JDBC_ZIP}"
else
  echo "==> kafka-connect-jdbc ${JDBC_VERSION} 已就位，跳过"
fi

if [ ! -f "${JDBC_DIR}/lib/${MYSQL_DRIVER_JAR}" ]; then
  echo "==> 下载 MySQL Connector/J ${MYSQL_DRIVER_VERSION}"
  curl -fSL --retry 3 -o "${JDBC_DIR}/lib/${MYSQL_DRIVER_JAR}" "$MYSQL_DRIVER_URL"
else
  echo "==> MySQL Connector/J ${MYSQL_DRIVER_VERSION} 已就位，跳过"
fi

echo
echo "插件就绪：${JDBC_DIR}"
echo "  PostgreSQL 驱动由 connector 自带（lib/postgresql-42.7.11.jar），无需单独下载。"
echo "接下来：docker compose build connect && docker compose up -d"
