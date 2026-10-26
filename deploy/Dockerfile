# recoow 后端镜像。
#
# 构建上下文是 dist/package（内含交叉编译好的静态二进制与字段清单）：
#   cd dist/package && docker build -t recoow-backend .
#
# 二进制是 CGO_ENABLED=0 的静态链接 ELF，运行镜像不需要任何运行时库。
#
# 国内网络拉不到 Docker Hub 时，可换镜像加速源构建：
#   docker build --build-arg BASE_IMAGE=your-registry/alpine:3.24 -t recoow-backend .

ARG BASE_IMAGE=alpine:3.24

# ---- 阶段一：按目标架构挑选二进制 ----
# 单独一层来做选择，避免把另一个架构的 11MB 二进制留在最终镜像里。
FROM ${BASE_IMAGE} AS pick
ARG TARGETARCH
COPY recoow-api-linux-amd64 recoow-api-linux-arm64 /tmp/
COPY schema /schema
RUN set -eux; \
    case "${TARGETARCH:-$(uname -m)}" in \
      amd64|x86_64)  src=/tmp/recoow-api-linux-amd64 ;; \
      arm64|aarch64) src=/tmp/recoow-api-linux-arm64 ;; \
      *) echo "不支持的架构：${TARGETARCH:-$(uname -m)}" >&2; exit 1 ;; \
    esac; \
    mv "$src" /recoow-api; \
    chmod 0755 /recoow-api; \
    find /schema -name '._*' -type f -delete

# ---- 阶段二：运行镜像 ----
FROM ${BASE_IMAGE}

RUN apk add --no-cache ca-certificates tzdata

RUN addgroup -S -g 10001 recoow \
 && adduser -S -u 10001 -G recoow recoow \
 && mkdir -p /data \
 && chown recoow:recoow /data

WORKDIR /app
COPY --from=pick /recoow-api /app/recoow-api
COPY --from=pick /schema /app/schema

ENV RECOOW_LISTEN=0.0.0.0:8080 \
    RECOOW_DATA=/data \
    RECOOW_SCHEMA=/app/schema \
    TZ=Asia/Shanghai

USER recoow
EXPOSE 8080
VOLUME ["/data"]

# /api/schema 未带设备令牌时返回 401，出现 401 即代表服务已就绪且鉴权生效
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -S -O /dev/null http://127.0.0.1:8080/api/schema 2>&1 | grep -Eq '^[[:space:]]*HTTP/[0-9.]+ 401([[:space:]]|$)' || exit 1

ENTRYPOINT ["/app/recoow-api"]
