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
- 冲突检测：创建/修改预订时校验同房间在 `[入住, 离店)` 区间内是否已有有效订单，同一房间加悲观锁，并发下单只能成功一单
- 房态语义：`room.status` 只表达**物理状态**（空闲 / 已入住 / 待清洁 / 维修中）；「某晚能不能卖」由预约表按日期实时推导，房态图支持选日期查看
- 账单：`应收合计 = 房费 + 其他消费 − 折扣`，`应退 = 押金 + 本次收款 − 应收合计`，收款必须足额才能结账；提前离店按实际住夜重算房费，差额记为「提前离店减免」
- 留痕：折扣 / 反结账必须填原因并写入账单备注与操作日志，关键动作（入住 / 退房 / 结账 / 房态变更）全量审计，仅管理员可查

## 端到端测试

`scripts/` 下有 4 个 curl 驱动的端到端验收脚本，直接打真实应用 + 真实 MySQL：

| 脚本 | 覆盖 | 断言 |
| --- | --- | --- |
| `func-test.sh` | 完整业务链路：登录 → 建单 → 冲突拦截 → 入住 → 退房 → 追加消费 → 结账 → 权限隔离 | 18 |
| `opt1-test.sh` | 第一批：营收口径、欠款校验、看板 SQL 聚合、日期校验、索引、管理员保护 | 21 |
| `opt2-test.sh` | 第二批：内联新建客户、分页、多条件筛选、客户搜索下拉 | 57 |
| `opt3-test.sh` | 第三批：房态语义收敛、房态图日期选择器、并发下单、提前退房重算、折扣/反结账、审计日志、打印导出、平板适配 | 82 |

```bash
bash scripts/func-test.sh     # 应用运行在 8080 时执行，可用 HMS_BASE 覆盖
HMS_BASE=http://127.0.0.1:8081 bash scripts/opt3-test.sh
bash scripts/clean-test-data.sh   # 清理测试产生的订单/账单，恢复基线数据
```

当前结果：**178 项断言全部通过（PASS=178 FAIL=0）**。

## 页面截图

- `docs/screenshots/` —— 基础页面（登录、看板、房态图、预订管理、预订表单、客房、房型、客户、账单列表/详情、账号权限、个人中心）
- `docs/screenshots/batch2/` —— 第二批交互优化（分页、筛选、内联新建客户、客户搜索下拉）
- `docs/screenshots/batch3/` —— 第三批结构性优化（按日期的房态图、退房弹窗实时重算、账单折扣与变更留痕、账单小票、操作日志、交接班名单、平板适配）

## 文档

| 文档 | 说明 |
| --- | --- |
| [docs/IDEA-本地部署文档.md](docs/IDEA-本地部署文档.md) | IntelliJ IDEA 本地部署全流程：环境要求、数据库准备、项目导入、SDK/Lombok/编码配置、运行配置、验证清单、常见问题排查、打包部署、配置速查表 |
| [docs/优化建议.md](docs/优化建议.md) | 代码审查报告：按「正确性 / 逻辑边界 / 使用习惯 / 工程运维」四类梳理的优化清单，含证据位置、影响分析与三批落地结果 |

