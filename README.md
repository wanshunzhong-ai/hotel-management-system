# 酒店管理系统（Hotel Management System）

基于 **Spring Boot 3.3 + Thymeleaf + Spring Security + Spring Data JPA + MySQL 9** 的一体化酒店管理平台，
覆盖「房态总览 → 在线预订 → 入住登记 → 退房结算」的房间全生命周期。

## 功能总览

| 模块 | 说明 |
| --- | --- |
| 数据看板 | 入住率、今日预抵/预离、今日收款、近 7 日营收趋势（Chart.js）、房型销量分布、今日预抵/预离名单 |
| 房态一览 | 按楼层平铺的房态图，空闲 / 已预订 / 已入住 / 待清洁 / 维修中五种状态，支持改状态与一键清洁 |
| 房型 / 客房 | 房型（价格、床型、可住人数）与客房（房号、楼层、朝向）的增删改查与状态管理 |
| 预订管理 | 新建/编辑/取消/删除预订，按日期区间自动计算晚数与房费，**同一房间同一时段冲突自动拦截** |
| 入住 / 退房 | 预订 → 入住 → 退房状态流转，联动房间状态；退房自动生成账单 |
| 账单结算 | 房费 + 消费明细，押金 / 应收 / 应退自动计算，支持微信 / 支付宝 / 现金 / 银行卡收款，结账后锁定 |
| 客户档案 | 证件、联系方式、会员等级（普通/银卡/金卡/钻石），订单历史关联 |
| 账号权限 | 管理员 / 前台接待 / 客房服务三种角色，基于 Spring Security 的 URL + 方法级权限隔离 |

## 技术栈

- **后端**：Spring Boot 3.3.5（Web / Validation / AOP）、Spring Security 6、Spring Data JPA（Hibernate）
- **前端**：Thymeleaf 服务端渲染 + Bootstrap 5.3 + Bootstrap Icons + Chart.js（全部本地化，离线可用）
- **数据库**：MySQL 9（utf8mb4），启动时自动建表并初始化演示数据
- **构建**：Maven，JDK 17+（开发环境使用 JDK 21）

## 快速开始

> 首次在 IntelliJ IDEA 中部署？请直接看 **[docs/IDEA-本地部署文档.md](docs/IDEA-本地部署文档.md)**（含 SDK 配置、Lombok、运行配置、11 类常见问题排查）。

```bash
# 1. 准备 MySQL（root 密码按实际修改 application.yml）
#    默认连接：jdbc:mysql://127.0.0.1:3306/hotel_management（库不存在会自动创建）

# 2. 打包
mvn clean package -DskipTests

# 3. 启动（默认 8080 端口）
java -jar target/hotel-management-system.jar
# 指定端口：java -jar target/hotel-management-system.jar --server.port=8080
```

浏览器访问 <http://localhost:8080>

> 启动完成后控制台会直接打印可点击的入口链接（本机 + 局域网）、数据看板地址与演示账号，无需手动拼地址。监听端口取自实际启动的 Web 容器，所以用 `--server.port` 或环境变量改端口后打印的仍是真实地址。

## 演示账号

| 角色 | 账号 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部功能 |
| 前台接待 | `reception` | `123456` | 预订、入住、退房、结账、客户、房态 |
| 客房服务 | `housekeeping` | `123456` | 房态查看与清洁维护（无法访问客户 / 订单 / 账单 / 账号） |

> 生产环境请在首次登录后立即修改默认密码。

## 目录结构

```
src/main/java/com/hotel/hms
├── config/        Security、初始化数据、全局 Model 属性
├── controller/    MVC 控制器（看板/房型/客房/客户/预订/账单/账号）
├── dto/           表单对象（预订、结账）、看板统计
├── entity/        JPA 实体与枚举（User/RoomType/Room/Customer/Reservation/Bill/...）
├── repository/    Spring Data JPA 仓库（含冲突检测查询）
├── service/       业务层（状态流转、事务、业务校验）
└── common/        业务异常与全局异常处理

src/main/resources
├── templates/     Thymeleaf 页面（登录、看板、房态图、各业务列表与表单）
├── static/        app.css / app.js / vendor（Bootstrap、Chart.js 本地化）
└── application.yml
```

## 核心业务规则

- 订单状态机：`已预订 → 已入住 → 已退房`（可取消 / 删除，历史订单禁止删除）
- 冲突检测：创建/修改预订时校验同房间在 `[入住, 离店)` 区间内是否已有有效订单
- 房间状态联动：入住 → 已入住；退房 → 待清洁；清洁完成 → 空闲
- 账单：`应收合计 = 房费 + 其他消费`，`应退 = 押金 + 本次收款 − 应收合计`，结账后不可再追加消费

## 端到端测试

项目附带的 `scripts/func-test.sh` 用 curl 模拟完整业务链路（登录 → 建单 → 冲突拦截 → 入住 → 退房 → 追加消费 → 结账 → 权限隔离），共 17 项断言：

```bash
bash scripts/func-test.sh    # 应用运行在 8080 时执行
```

运行结果：`PASS=17 FAIL=0`。

## 页面截图

见 `docs/screenshots/`（登录、看板、房态图、预订管理、预订表单、客房、房型、客户、账单列表/详情、账号权限、个人中心）。

## 文档

| 文档 | 说明 |
| --- | --- |
| [docs/IDEA-本地部署文档.md](docs/IDEA-本地部署文档.md) | IntelliJ IDEA 本地部署全流程：环境要求、数据库准备、项目导入、SDK/Lombok/编码配置、运行配置、验证清单、常见问题排查、打包部署、配置速查表 |
| [docs/优化建议.md](docs/优化建议.md) | 代码审查报告：按「正确性 / 逻辑边界 / 使用习惯 / 工程运维」四类梳理的优化清单，含证据位置、影响分析与分批落地顺序 |

