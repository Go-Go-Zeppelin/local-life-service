# Local Life Service

[![CI](https://github.com/Go-Go-Zeppelin/local-life-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Go-Go-Zeppelin/local-life-service/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

基于 Spring Boot 的本地生活服务后端示例，包含用户登录、店铺检索、探店博客互动与优惠券秒杀下单。仓库提供后端代码，不包含前端与图片资源。

## 功能

- 用户：手机验证码登录、Redis Token、签到与连续签到统计。
- 店铺：分类查询、附近店铺检索、缓存查询。
- 社交：博客发布与点赞、关注、关注流。
- 优惠券：普通券与秒杀券、Caffeine/Redis 缓存。
- 秒杀订单：Redis Lua 预占、Kafka 异步建单、结果查询、死信补偿、未支付订单超时关闭与库存回补。

秒杀提交成功返回 **HTTP 202**，只表示受理。客户端必须查询最终建单结果；详细接口与状态约定见 [异步秒杀下单](manual/异步秒杀下单.md)。支付接口仅演示订单状态更新，未接入第三方支付。

## 技术栈

| 组件 | 使用版本 |
| --- | --- |
| Spring Boot | 2.3.12.RELEASE |
| MyBatis-Plus | 3.4.3 |
| Spring Data Redis / Lettuce | 2.6.2 / 6.1.6.RELEASE |
| Redisson | 3.13.6 |
| Spring Kafka / Kafka Client | 2.9.13 / 3.2.3 |
| MySQL JDBC Driver | 5.1.47 |

版本以 [pom.xml](pom.xml) 为准。编译目标为 Java 8，建议使用已验证的 **JDK 17** 和 Maven 3；当前 Lombok 在 JDK 21 下存在编译兼容问题。

## 本地运行

### 1. 获取代码

```sh
git clone https://github.com/Go-Go-Zeppelin/local-life-service.git
cd local-life-service
```

### 2. 准备服务与数据库

默认连接 MySQL `127.0.0.1:3306`、Redis `127.0.0.1:6379`、Kafka `localhost:9092`。需先准备可用的三个服务。Redis 需要支持 Lua、GEO 和 Bitmap。

在专用开发库中导入初始化 SQL，脚本包含 `DROP TABLE`，会重建同名表：

```sh
mysql -h 127.0.0.1 -u root -p -e "CREATE DATABASE IF NOT EXISTS hmdp CHARACTER SET utf8mb4;"
mysql -h 127.0.0.1 -u root -p hmdp < src/main/resources/db/hmdp.sql
```

新建库只需导入上述脚本。已有库升级说明见 [异步秒杀下单](manual/异步秒杀下单.md#数据库升级)，迁移不会自动执行。

SQL 中用户手机号已替换为无效的合成标识，不能直接用于登录。使用自己的测试号码请求验证码后登录，可自动创建用户。样例中的图片路径和外部图片链接不代表仓库提供相应资源或授权。

### 3. 配置并启动

macOS/Linux 示例：

```sh
export MYSQL_USERNAME=root
read -r -s MYSQL_PASSWORD
export MYSQL_PASSWORD
mvn clean compile
mvn spring-boot:run
```

`read` 时输入本地 MySQL 密码并回车，终端不显示输入。服务默认监听 `8081`。可通过环境变量覆盖 Spring Boot 配置，例如 `SPRING_DATASOURCE_URL`、`SPRING_REDIS_HOST`、`SPRING_REDIS_PORT`、`SPRING_KAFKA_BOOTSTRAP_SERVERS`、`SERVER_PORT`。

**当前 Redisson 连接独立写在 [RedissonConfig.java](src/main/java/com/hmdp/config/RedissonConfig.java)，固定为本机 Redis 6379。** 如果调整 Redis 地址或启用认证，需要同步修改 Redisson 配置，确保缓存与锁使用同一 Redis。

项目不会自动加载 `.env` 文件。不要把真实密码、Token 或本地私有配置提交到仓库。

### 4. 验证接口

```sh
curl 'http://localhost:8081/shop-type/list'
```

登录入口为 `POST /user/code?phone=...` 与 `POST /user/login`。验证码当前只输出到应用 debug 日志，未接入短信服务；登录返回的 Token 放入 `authorization` 请求头。

图片上传目录当前仍是 [SystemConstants.java](src/main/java/com/hmdp/utils/SystemConstants.java) 中的 Windows 路径。使用上传功能前需改为本机可写目录并配置静态资源服务。

## 构建与测试

```sh
# 打包，不执行测试
mvn clean package -DskipTests

# 无需外部服务的单元测试，CI 使用相同测试范围
mvn -Dtest=VoucherOrderControllerTest,CacheInvalidationTest,NormalTest test
```

`mvn test` 会包含依赖外部服务的原有测试，部分测试会写 Redis 或导入 GEO 数据，应使用专用开发环境。秒杀集成测试默认关闭，需要专用 MySQL、Redis 和 Kafka；启用命令和隔离要求见 [集成测试说明](manual/异步秒杀下单.md#验证)。

## 目录

```text
src/main/java/com/hmdp/
├── controller/   HTTP API
├── service/      业务逻辑与缓存
├── consumer/     Kafka 消费与补偿
├── task/         定时重投与订单关闭
├── mapper/       MyBatis-Plus 数据访问
├── entity/       数据实体
├── config/       Spring 配置
└── utils/        通用工具与拦截器
src/main/resources/
├── db/           初始化 SQL 与手动迁移
├── mapper/       MyBatis XML
└── *.lua         Redis 原子操作
src/test/java/com/hmdp/
manual/           业务规则与流程说明
```

## 许可证

项目采用 [MIT License](LICENSE)。第三方依赖及外部图片、内容遵循其各自的许可证或授权条件。
