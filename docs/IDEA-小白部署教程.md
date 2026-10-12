# 酒店管理系统 · 小白 IDEA 本地部署教程

> **这份文档是给谁的**：没配过 Java 环境、第一次用 IDEA、只想把系统在自己电脑上跑起来的人。
> **不需要**：懂 Spring Boot、懂 Maven、懂 MySQL 语法。
> **全程耗时**：软件下载顺利的话约 **40 分钟**（其中 15 分钟是在等下载/等 IDEA 索引，可以摸鱼）。
> **最后你会得到**：浏览器里一个能登录、能下单、能派工的酒店管理系统，四个角色账号各一套权限。

---

## 阅读路线（别从头硬啃）

| 你的情况 | 直接看 |
| --- | --- |
| 什么都没装，从零开始 | **第 0 章 → 第 1 章 → 第 2 章 → 第 3 章 → 第 4 章 → 第 5 章 → 第 6 章 → 第 7 章** |
| 环境都装好了，只想把项目跑起来 | **第 5 章 → 第 6 章 → 第 7 章** |
| 只想给别人一个能访问的系统，不想装 IDEA | **第 9 章**（用打好的 jar 直接跑） |
| 已经报错了 | **第 10 章**（按报错原文查） |
| 刚才还能用，现在起不来 | **第 12 章「每日工作流」+ 第 10.1 / 10.2 节** |

---

## 第 0 章：先搞懂一共要装几个东西

小白最容易卡住的地方不是「不会装」，而是**不知道这几个软件之间是什么关系**，装完不知道谁该先启动。

你一共需要 **3 个必备 + 1 个可选**：

| 序号 | 软件 | 干什么用的 | 一句话理解 | 必装？ |
| --- | --- | --- | --- | --- |
| ① | **JDK 21** | Java 运行环境 + 编译器 | 系统的「发动机」，没它什么都跑不起来 | ✅ 必装 |
| ② | **MySQL 9** | 数据库 | 系统的「仓库」，房态、订单、账号全存在这里 | ✅ 必装 |
| ③ | **IntelliJ IDEA** | 打开项目、点按钮启动 | 系统的「操作台」，你平时就用它 | ✅ 必装 |
| ④ | **Git Bash** | 跑验收测试脚本 | 只在「跑自动化测试」时用得到 | ⭕ 可选 |

它们的关系是这样（从左到右，左边依赖右边）：

```
  你                    IDEA                     Spring Boot 应用                MySQL
浏览器  ←──────────  点绿色三角启动  ──────→   监听 8080 端口  ────JDBC────→   监听 3306 端口
                                                  ↑                              ↑
                                            「发动机」JDK 21               「仓库」hotel_management 库
```

**三个关键认知，先建立起来**：

1. **启动顺序**：MySQL 先起来 → 再启动 IDEA 里的项目。反过来会报 `Communications link failure`。
2. **端口别撞车**：MySQL 固定用 `3306`，本项目默认用 `8080`。两个端口一旦被别的软件占了，就会启动失败。
3. **数据库不用你建**：项目连库的地址里写了 `createDatabaseIfNotExist=true`，**第一次启动会自动建库、自动建表、自动灌演示数据**。你只需要保证 MySQL 在跑、账号密码对得上。

> 💡 **只想看到系统跑起来，一点环境都不想配？**
> 直接跳到 **第 9 章**：项目已经能一键打包成一个 `.jar` 文件，双击命令行跑起来即可——但**前提仍然是本机装了 JDK 21 和 MySQL**（IDEA 可以省掉）。

---

## 第 1 章：安装 JDK 21

### 1.1 先检查是不是已经装了

打开 **命令行**（键盘 `Win + R` → 输入 `cmd` → 回车），输入：

```bash
java -version
```

- 如果输出了 `openjdk version "21.0.x"` 之类的版本号 → **已经装好了**，跳到第 2 章。
- 如果提示 `'java' 不是内部或外部命令` → 没装，继续往下看。

### 1.2 下载

推荐 **Eclipse Temurin（原 AdoptOpenJDK）**，免费、无需注册、下载速度快：

| 来源 | 地址 | 说明 |
| --- | --- | --- |
| Adoptium 官网 | https://adoptium.net/temurin/releases/?version=21 | 选 **Version: 21** → **Operating System: Windows** → **Architecture: x64** → 下载 `.msi` |
| 华为云镜像 | https://mirrors.huaweicloud.com/openjdk/ | 国内下载更快，找 21 的 Windows 包 |
| 腾讯云镜像 | https://mirrors.cloud.tencent.com/AdoptOpenJDK/ | 同上 |

> ⚠️ **只下 JDK，不要下 JRE**。JRE 只能运行、不能编译，IDEA 会报「找不到编译器」。

### 1.3 安装（Windows 向导）

1. 双击下载好的 `.msi`，一路 **Next**
2. 到 **Custom Setup** 这一步，注意两个勾选项：
   - **Add to PATH** → 选 **Will be installed on local hard drive**（**必须**，否则命令行找不到 java）
   - **Set JAVA_HOME variable** → 选 **Will be installed on local hard drive**（**建议**，Maven 靠它找 JDK）
3. 安装路径保持默认 `C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot\` 即可（**不要放在中文路径或带空格的路径下**）
4. 点 **Install**，等 1 分钟，完成

### 1.4 验证

**关掉旧命令行窗口，重新开一个**（环境变量变了要重开才生效），输入：

```bash
java -version
javac -version
```

期望输出（两个版本号都应是 21）：

```
openjdk version "21.0.5" 2024-10-15
OpenJDK Runtime Environment Temurin-21.0.5+11 (build 21.0.5+11-LTS)
OpenJDK 64-Bit Server VM Temurin-21.0.5+11 (build 21.0.5+11-LTS, mixed mode, sharing)

javac 21.0.5
```

### 1.5 关于 JDK 版本的一个坑（重要）

本项目 `pom.xml` 里声明的是 **Java 21**，并且**显式锁定了 Lombok 1.18.48**：

```xml
<properties>
    <java.version>21</java.version>
    <lombok.version>1.18.48</lombok.version>   <!-- 支持 JDK 21 ~ 27 -->
</properties>
```

因此：

| 你本机的 JDK | 结果 |
| --- | --- |
| **JDK 21** | ✅ 最佳选择，直接用 |
| JDK 22 / 23 / 24 / 25 / 26 / 27 | ✅ 也能编译（因为 Lombok 已升级到 1.18.48） |
| JDK 17 | ⚠️ 项目要求 21，会报 `无效的目标发行版: 21`，请装 21 |
| JDK 8 / 11 | ❌ 完全跑不了 |

> **一句话结论**：**装 JDK 21 最省事**。如果你本机已有更高版本（比如 27），也不用卸载，能跑；但一旦编译报 `ExceptionInInitializerError`，请直奔 **第 10.6 节**。

---

## 第 2 章：安装并启动 MySQL

### 2.1 下载

到 MySQL 官网下载 **社区版（Community Server）**，免费：

- 下载页：https://dev.mysql.com/downloads/mysql/
- 选 **Windows (x86, 64-bit), MSI Installer**，体积较大的是「离线完整包」，推荐（不用边装边下）

> 版本选 **8.0.x 或 9.x 都可以**，本项目两者都验证过。

### 2.2 安装向导的关键选项（这里错了以后很麻烦）

| 向导页 | 怎么选 | 为什么 |
| --- | --- | --- |
| Choosing a Setup Type | **Server only** | 只要数据库服务，其他工具用不上，装多了拖慢电脑 |
| Authentication Method | **Use Strong Password Encryption**（默认第一个） | 现代的 `caching_sha2_password`，本项目连接串已适配 |
| **Accounts and Roles** | **root 密码设为 `123456`** | 与项目默认配置一致，**少改一处配置**（想用别的密码也行，记住它，第 6 章要填） |
| Windows Service | **勾选 Configure MySQL Server as a Windows Service**，服务名保持默认 | 让 MySQL 开机自启，不用每次手动开 |
| Apply Configuration | 点 **Execute** 等它跑完所有绿勾 | — |

> 🔐 **`123456` 只是为了本地学习方便**。如果你的电脑上有重要数据，请设一个强密码，然后在第 6 章把密码填进项目配置。

### 2.3 启动 MySQL 服务

**正常情况**：安装时勾了「Windows Service」+「Start the MySQL Server at System Startup」，**它已经在后台跑着了**，可以直接跳到 2.4。

如果没在跑（或者你重启电脑后想手动开），用**管理员身份**打开命令行：

> 开始菜单搜 `cmd` → 右键 → **以管理员身份运行**

```bash
net start MySQL
```

> ⚠️ **服务名以实际安装为准**。本机实测服务名就是 `MySQL`；
> 常见还有 `MySQL80`、`MySQL90`、`MySQL84` 等。
> 不确定的话：按 `Win + R` 输入 `services.msc` 回车，在列表里找名字带 **MySQL** 的那一项，看它的「名称」列。

其他系统：

```bash
# macOS（Homebrew）
brew services start mysql

# Linux
sudo systemctl start mysqld
```

### 2.4 验证数据库能连上

```bash
mysql -uroot -p123456 -e "SELECT VERSION();"
```

期望输出（版本号不重要，能出结果就行）：

```
+-----------+
| VERSION() |
+-----------+
| 9.0.1     |
+-----------+
```

> 提示 `'mysql' 不是内部或外部命令`？说明 MySQL 的 `bin` 目录没加到 PATH。
> 用全路径执行即可（把路径换成你自己的）：
> ```bash
> "D:\SoftWare\MySQL\MySQL Server 9.0\bin\mysql.exe" -uroot -p123456 -e "SELECT VERSION();"
> ```

### 2.5 不需要手动建库

再强调一次：**不用你建库、不用你建表、不用你插数据**。

项目连库地址是：

```
jdbc:mysql://127.0.0.1:3306/hotel_management?createDatabaseIfNotExist=true&...
```

那个 `createDatabaseIfNotExist=true` 就是「库不存在就自动建」的意思。表结构由 JPA 自动生成，演示数据由启动时的初始化代码灌入。

> 只有当你的数据库账号**没有 CREATE 权限**时，才需要手动建：
> ```sql
> CREATE DATABASE IF NOT EXISTS hotel_management
>   DEFAULT CHARACTER SET utf8mb4
>   DEFAULT COLLATE utf8mb4_general_ci;
> ```

---

## 第 3 章：安装 IntelliJ IDEA

### 3.1 社区版够不够？——**够**

| | 社区版 Community（免费） | 旗舰版 Ultimate（收费/学生免费） |
| --- | --- | --- |
| 打开项目、编译、运行 | ✅ | ✅ |
| 本项目能否跑起来 | ✅ **完全可以** | ✅ |
| Spring 专属运行面板 | ❌ 没有 | ✅ 有 |
| 内置数据库工具窗口 | ❌ 没有 | ✅ 有 |

**结论**：**下载 Community 社区版就行**。没有 Spring 面板不影响使用——我们用启动类旁边的绿色三角运行（第 7.1 节），效果一样。想连数据库看看数据，装个免费的 DBeaver 或 Navicat 试用版即可。

下载地址：https://www.jetbrains.com/idea/download/ （页面往下拉，找 **Community Edition**）

### 3.2 安装

1. 双击安装包 → **Next**
2. 安装路径建议默认；**不要**放在中文路径下
3. 勾选项建议：
   - ☑ **Create Desktop Shortcut**（建桌面图标）
   - ☑ **Add "bin" folder to the PATH**（方便命令行启动）
   - ☑ **.java**（关联 Java 文件）
4. 安装完成后启动 IDEA，首次启动会让你选主题（选 **Dark** 或 **Light** 随你喜欢）、是否导入设置（选 **Do not import settings**）

---

## 第 4 章：把项目代码准备好

### 4.1 放到哪

把项目文件夹整个复制到本地硬盘，例如：

```
D:\Work\Hotel Management\hotel-management-system\
                                    └── pom.xml      ← 这个文件在，就说明目录选对了
```

> ⚠️ **路径规范（重要）**：
> - ✅ 可以有空格（本项目实测路径 `D:\Work\Hotel Management\...` 带空格，完全正常）
> - ❌ **不要放在中文路径下**（如 `D:\我的项目\`），Maven / JDK 在中文路径下偶发编码问题
> - ❌ 不要放在 C 盘用户目录下的 `OneDrive` 同步目录里（会造成文件锁冲突）

### 4.2 检查目录结构对不对

打开该文件夹，应该看到这些内容：

```
hotel-management-system/
├── pom.xml                 ← Maven 项目的「身份证」，最关键的文件
├── README.md               ← 项目说明（推荐先扫一眼「功能总览」表格）
├── docs/                   ← 文档与截图
├── scripts/                ← 验收测试脚本
└── src/
    └── main/
        ├── java/           ← Java 源代码
        └── resources/
            ├── application.yml   ← 配置文件（第 6 章要改的就是它）
            ├── templates/        ← 所有网页（Thymeleaf 模板）
            └── static/           ← CSS / JS
```

只要 `pom.xml` 和 `src` 都在，就可以了。

---

## 第 5 章：在 IDEA 中打开并配置项目

**这一章是小白最容易卡住的地方，请一步一步照着做，每步都有「怎么判断做对了」。**

### 5.1 打开项目（注意：是 Open，不是 New）

1. IDEA 启动页 → 点 **Open**（⚠️ 千万别点 New Project，那会新建一个空项目）
2. 在文件选择框里，选中 **`hotel-management-system` 这一层文件夹**（也就是包含 `pom.xml` 的那一层）→ 点 **OK**
3. 如果弹出 **Trust Project**（信任项目）对话框 → 点 **Trust Project**
   （这是 IDEA 的安全机制，本地自己的项目放心点）

### 5.2 等 Maven 下载依赖（第一次要等）

打开后，IDEA 识别到 `pom.xml`，会自动开始下载依赖：

- **右下角**会出现进度条 / `Resolving dependencies...` 之类的提示
- 首次需要下载约 100+ 个 jar 包，**国内网络约 3～15 分钟**

**怎么判断做对了**：打开右侧的 **Maven** 面板（`View → Tool Windows → Maven`），
`Dependencies` 下面能看到一大堆 `spring-boot-*`、`mysql-connector-j` 之类的条目。

> 卡住不动、或者一堆红色报错？先看 **第 10.13 节**（换阿里云镜像）。

### 5.3 配置 JDK（Project SDK）

按 `Ctrl + Alt + Shift + S` 打开 **Project Structure** → 左侧选 **Project**：

| 项目 | 设置成 |
| --- | --- |
| **SDK** | `21`（下拉里如果没有，点 `Add SDK → Download JDK...`，Version 选 **21**，Vendor 选 Eclipse Temurin，下载即可） |
| **Language level** | `21 - Records, patterns, ...` |

然后左侧切到 **Modules** → 选中 `hotel-management-system` 模块 → **Sources** 标签页 →
确认 **Language level** 同样是 **21**。

**怎么判断做对了**：`pom.xml` 里会有个黄色提示条消失，且不再报 Java 版本相关错误。

> 💡 **本机只装了 IDEA、没有独立 JDK 怎么办？**
> 可以用 IDEA 自带的 JBR（基于 21，含 `javac`）：
> 在 SDK 下拉里选 `Add SDK → JDK...`，然后选中 IDEA 安装目录下的 **`jbr`** 文件夹（例如 `D:\SoftWare\WorkBuddy\jbr`）。
> 这条路本机验证可用。

### 5.4 打开「注解处理」（Lombok 必须）

本项目大量使用了 Lombok（代码里看不到 `getId()` 这类方法，但其实是自动生成的）。IDEA 必须开启注解处理，否则**满屏红字、编译报「找不到符号」**。

1. `Ctrl + Alt + S` 打开 **Settings**
2. 左侧 `Build, Execution, Deployment → Compiler → Annotation Processors`
3. 勾选 ☑ **Enable annotation processing**
4. 左侧 `Plugins`，搜索 **Lombok**，确认它是 **已启用**状态
   （IDEA 2023.3+ 已内置 Lombok 插件，通常不用额外安装）

**怎么判断做对了**：打开任意一个 `entity` 包下的类（如 `Room.java`），
代码里 `@Data` / `@Getter` 注解下面**不出现红色波浪线**。

> 如果还是红字：菜单 `Build → Rebuild Project` 重新编译一次。

### 5.5 设置文件编码为 UTF-8

本项目界面**全是中文**，编码不对会出现乱码甚至页面 500。

`Ctrl + Alt + S` → `Editor → File Encodings`：

| 设置项 | 值 |
| --- | --- |
| Global Encoding | **UTF-8** |
| Project Encoding | **UTF-8** |
| Default encoding for properties files | **UTF-8** |
| ☑ Transparent native-to-ascii conversion | **勾上** |

**怎么判断做对了**：IDEA 右下角状态栏显示 **UTF-8**。

### 5.6 （强烈建议）配置 Maven 阿里云镜像加速

国内直连 Maven 中央仓库很慢。改成本地镜像后，下载速度会快很多。

1. 打开文件 `C:\Users\你的用户名\.m2\settings.xml`
   （如果 `.m2` 文件夹或 `settings.xml` 不存在，就**手动新建这个文件**）
2. 把下面内容整个复制进去（已有内容的话，只把 `<mirrors>` 段加进去）：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun-public</id>
      <mirrorOf>central</mirrorOf>
      <name>aliyun public</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

3. 回到 IDEA：`Settings → Build, Execution, Deployment → Build Tools → Maven`
   - **User settings file**：勾选 `Override`，指向刚编辑的 `C:\Users\你的用户名\.m2\settings.xml`
4. 点右侧 Maven 面板的 **🔄 Reload All Maven Projects**

### 5.7 收尾自检：编译一次

菜单 **Build → Rebuild Project**（或 `Ctrl + F9`）。

**怎么判断做对了**：底部 Build 面板显示 **BUILD SUCCESS** / `Compilation completed successfully`。

> 报错了不要慌，直接拿报错原文去 **第 10 章** 对照，最常见的三条：
> `找不到符号: getXxx()` → 10.4；`无效的目标发行版: 21` → 10.5；`ExceptionInInitializerError` → 10.6。

---

## 第 6 章：把数据库密码告诉项目

项目默认连的是 `root / 123456`。**如果你第 2.2 节就设的 `123456`，这一章可以直接跳过**。

打开 `src/main/resources/application.yml`，找到这一段：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/hotel_management?createDatabaseIfNotExist=true&...
    username: root
    password: "123456"        # ← 改成你的真实 MySQL 密码
```

改完保存（`Ctrl + S`）。

| 配置项 | 默认值 | 什么时候要改 |
| --- | --- | --- |
| `url` 里的 `127.0.0.1:3306` | 本机 3306 | MySQL 装在别的机器 / 改了端口 |
| `username` | `root` | 用了别的数据库账号 |
| `password` | `123456` | **设了别的 root 密码 → 必改这里** |

> ⚠️ **要提交代码到 Git 的话**，别把密码提交上去。两种做法：
> - **推荐**：不改文件，改在 IDEA 运行配置里用环境变量覆盖（见 7.2 节）
> - 或者执行一次：`git update-index --skip-worktree src/main/resources/application.yml`
>   （让 Git 假装看不见这个文件的变化）

---

## 第 7 章：启动系统

### 7.1 方式 A：点绿色三角（推荐，最省事）

1. 在左侧项目树里展开：`src/main/java/com/hotel/hms/HotelManagementApplication.java`
2. 打开它，在 `public static void main` 那一行的**行号旁边**，会看到一个 **绿色三角 ▶**
3. 点它 → 选 **Run 'HotelManagementApplication'**

IDEA 会自动创建一个运行配置并启动。第一次会多花十几秒。

### 7.2 方式 B：手动创建一个运行配置（想自定义端口时用）

菜单 `Run → Edit Configurations...` → 左上角 **+** → 选 **Spring Boot**（社区版没有这项就选 **Application**）：

| 字段 | 填什么 |
| --- | --- |
| **Name** | `HotelManagementApplication` |
| **Main class** | `com.hotel.hms.HotelManagementApplication` |
| **Use classpath of module** | `hotel-management-system` |
| **JRE** | `21` |
| **Working directory** | `$PROJECT_DIR$`（项目根目录） |

**想改端口 / 用环境变量藏密码**：在同一个窗口的 **Environment variables** 里填（多条用 `;` 分隔）：

```
SPRING_DATASOURCE_PASSWORD=你的真实密码;SERVER_PORT=8081
```

然后点 **OK**，用右上角的绿色三角运行。

### 7.3 方式 C：命令行启动（不依赖 IDEA，出问题时用来对照）

在项目根目录打开命令行：

```bash
mvn clean package -DskipTests
java -jar target/hotel-management-system.jar
```

> 这条路径也说明：**系统不依赖 IDEA**，只要 JDK 和 MySQL 在，任何机器都能跑。

### 7.4 怎么判断启动成功了

控制台会滚过一大堆日志，**最后**会出现这样一段横幅（这是项目特意打印的，方便你直接点地址）：

```
  ✔ 云栖大酒店 · 酒店管理系统 启动成功
════════════════════════════════════════════════════════════════
  访问入口    http://localhost:8080/login
  数据看板    http://localhost:8080/dashboard
  局域网访问  http://192.168.1.5:8080/login
────────────────────────────────────────────────────────────────
  演示账号    admin / admin123              （管理员）
              reception / 123456            （前台）
              housekeeping / 123456         （客房）
              supervisor / 123456           （客房主管）
════════════════════════════════════════════════════════════════
```

横幅之前还会有这几句关键日志：

```
已创建默认账号 admin / admin123
已创建默认账号 reception / 123456
已创建默认账号 housekeeping / 123456
已创建默认账号 supervisor / 123456
演示数据初始化完成。
Tomcat started on port 8080 (http)
Started HotelManagementApplication in 8.362 seconds
```

**看到 `Started HotelManagementApplication` 和横幅，就是成功了。**

- 横幅里的端口是**真实端口**（取自实际启动的 Web 容器），所以即使你改成 8081，打印出来的地址依然是对的
- 「局域网访问」地址取自本机网卡，**手机连同一个 WiFi 就能用这个地址打开**（详见 9.4 节）
- 不想看这段横幅：`application.yml` 里把 `hotel.startup.print-banner` 改成 `false`

### 7.5 端口 8080 被占用了

如果报 **`Port 8080 was already in use`**，两种解法：

**解法一（推荐）**：换端口。方式 B 里加环境变量 `SERVER_PORT=8081`，或命令行加参数：

```bash
java -jar target/hotel-management-system.jar --server.port=8081
```

**解法二**：把占用 8080 的进程干掉（注意别杀错系统进程）：

```bash
# Windows：先查出占用端口的进程号（最后一列是 PID）
netstat -ano | findstr :8080

# 再按 PID 结束它
taskkill /F /PID 12345
```

---

## 第 8 章：验证部署（四个身份挨个登一遍）

### 8.1 打开并登录

浏览器访问 **http://localhost:8080** ，会自动跳到登录页（**页面上不再显示演示账号，账号见下一节**）。

### 8.2 四个演示账号

| 角色 | 账号 | 密码 | 权限范围（一句话） |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部功能，含账号权限与操作日志 |
| 前台接待 | `reception` | `123456` | 预订 / 入住 / 退房 / 结账 / 客户 / 房态 / 收款流水 / 交接班 / 发票 / 应收 / 房价方案 / 团队 |
| 客房服务 | `housekeeping` | `123456` | 客房全流程（工单完成 / 迷你吧 / 洗衣布草 / 维修 / 失物 / 查房 / 移动端上报 / 待办） |
| 客房主管 | `supervisor` | `123456` | 客房服务的**全部权限** + 派工 / 验收 / 检查单模板 / 排班 / 耗材建档 / 布草外送结算 |

> 权限是**服务端**拦的，不是把按钮藏起来就算。所以直接手敲 URL 也会被挡——这正是「闭环」的含义。

### 8.3 五分钟验收动作清单（每个身份点三下）

按这个表点一遍，能全部对上，说明部署和权限都正常：

| 身份 | 动作 | 期望结果 |
| --- | --- | --- |
| **管理员** | ① 登录 → 看**数据看板** | 显示入住率、今日收款、近 7 日营收趋势曲线 |
| | ② 左侧「房态一览」 | 按楼层平铺房间，五种颜色状态 |
| | ③ 左侧「账号权限」 | 能看到 4 个账号，能新建/重置密码 |
| **前台接待** | ① 左侧「预订管理 → 新建预订」 | 选日期后房型房价自动联动，晚数 × 房价实时算出 |
| | ② 对一条「已预订」订单点**办理入住** | 状态变「已入住」，房态图对应房间变红 |
| | ③ 左侧「收款流水」「交接班对账」「发票管理」 | 都能打开（这些是前台专属模块，客房类账号看不到这些菜单） |
| **客房服务** | ① 左侧「派工与验收」 | 能看工单列表，但**没有「新建派工」按钮** |
| | ② 手动访问 `http://localhost:8080/customers` | **跳到 403 无权限页**（这就是闭环生效了） |
| | ③ 看**数据看板** | 房态与待办正常显示，**但看不到任何金额**（营收卡片被隐藏） |
| **客房主管** | ① 左侧「派工与验收」 | **有「新建派工」按钮**，能建单、能验收/打回 |
| | ② 左侧「排班与工作量」 | 能排早/中/晚班，能看到每人派工量与平均耗时 |
| | ③ 手动访问 `http://localhost:8080/payments` | **跳到 403**（主管进不了前台资金模块） |

如果每一条都对上了：**部署成功，权限闭环也没有破洞。**

### 8.4 （可选）跑自动化验收脚本

项目自带一套 curl 驱动的端到端测试，直接打真实应用 + 真实数据库。

**前提**：系统正跑在 **8080**（不是 8080 就用 `HMS_BASE` 指定，见下）。

在项目根目录用 **Git Bash**（或任意 bash 环境）执行：

```bash
bash scripts/func-test.sh
# 期望最后输出：结果：PASS=18  FAIL=0

bash scripts/run-all-tests.sh
# 一键顺序跑完 8 个脚本，最后一行汇总，期望：TOTAL PASS=715 FAIL=0
#（整套约 25～30 分钟，中间会连续输出各脚本的 [PASS] 明细）

bash scripts/clean-test-data.sh
# 跑完测试后清理测试数据，把数据库恢复到「演示基线」
```

系统不在 8080 端口时：

```bash
HMS_BASE=http://127.0.0.1:8081 bash scripts/func-test.sh
```

> ⚠️ **测试脚本会往数据库里写测试数据**。跑完请执行 `clean-test-data.sh` 恢复基线。
> 另外：测试脚本需要 `mysql` 命令可用（用于校验数据），路径不通时参考第 2.4 节的说明。

---

## 第 9 章：不想装 IDEA？直接用 jar 运行

### 9.1 打包

```bash
# 项目根目录
mvn clean package -DskipTests
```

产物是 `target/hotel-management-system.jar`（Spring Boot 可执行 fat jar，约 52 MB，自带 Tomcat，**不需要额外装服务器**）。

> 用 IDEA 也可以打包：右侧 Maven 面板 → `Lifecycle` → 双击 `package`。

### 9.2 运行

```bash
java -jar target/hotel-management-system.jar
# 换端口
java -jar target/hotel-management-system.jar --server.port=8081
```

### 9.3 让它后台常驻

```bash
# Linux / macOS
nohup java -jar target/hotel-management-system.jar > app.log 2>&1 &

# Windows（PowerShell）
Start-Process -NoNewWindow -RedirectStandardOutput app.log `
  -FilePath java -ArgumentList '-jar','target\hotel-management-system.jar'
```

### 9.4 让同事 / 手机访问

1. 启动后横幅里的 **局域网访问** 地址（形如 `http://192.168.1.5:8080/login`）就是入口
2. 同事和你在**同一个 WiFi / 局域网**下，把地址发给他即可
3. 如果打不开，多半是 **Windows 防火墙**挡了 8080 端口：
   控制面板 → Windows Defender 防火墙 → 高级设置 → 入站规则 → 新建规则 → 端口 → TCP `8080` → 允许连接

> 端口检测不到、或者 8080 被占，先看第 7.5 节。

---

## 第 10 章：常见问题排查（按报错原文查）

### 10.1 `Port 8080 was already in use`

**含义**：8080 被别的程序占了。

```bash
# Windows
netstat -ano | findstr :8080
taskkill /F /PID <上面查到的PID>

# macOS / Linux
lsof -i :8080
kill -9 <PID>
```

或干脆换端口：`--server.port=8081`（见 7.5）。

---

### 10.2 `Communications link failure` / `Unable to connect to MySQL`

**含义**：应用连不上数据库。按顺序排查（**90% 是第一条**）：

1. **MySQL 服务是不是没启动？** 这是最常见原因：
   ```bash
   net start MySQL          # 服务名以实际为准，见 2.3
   ```
2. **3306 端口通不通**：
   ```bash
   netstat -ano | findstr :3306
   ```
3. **账号密码对不对**（直接用命令行连一次）：
   ```bash
   mysql -uroot -p123456 -h 127.0.0.1 -P 3306 -e "SELECT VERSION();"
   ```
   连不上 → 密码错，去改第 6 章的 `password`
4. MySQL 8/9 的加密方式问题：本项目连接串已带 `allowPublicKeyRetrieval=true&useSSL=false`，一般无需处理

---

### 10.3 `Access denied for user 'root'@'localhost'`

**含义**：账号或密码不对 / 没权限。

```bash
mysql -u root -p      # 手动输入密码，能登进去说明密码是对的
```

登进去后确认账号状态：

```sql
SELECT user, host, plugin FROM mysql.user WHERE user = 'root';
```

`plugin` 那一列是 `caching_sha2_password` 属正常。密码确实忘了/不对 → 改 `application.yml` 里的 `password`，或重置 MySQL 密码。

---

### 10.4 编译报 `找不到符号: 方法 getXxx()` / `log 变量不存在`

**含义**：Lombok 没生效。回到 **5.4 节**逐项检查：

1. `Settings → Build → Compiler → Annotation Processors` → 勾选 **Enable annotation processing**
2. `Settings → Plugins` → Lombok 插件已启用
3. 然后 `Build → Rebuild Project`

> 如果报的是一长串 `ExceptionInInitializerError` + `TypeTag :: UNKNOWN`，
> 那**不是**配置问题，是 JDK 版本问题，看 **10.6**。

---

### 10.5 `无效的目标发行版: 21` / `has been compiled by a more recent version`

**含义**：Maven 用的 JDK 不是 21。

```bash
mvn -v                    # 看输出的 Java version
echo %JAVA_HOME%          # Windows（Git Bash 里用 echo $JAVA_HOME）
```

`JAVA_HOME` 指向别的 JDK 就改过来（改完重启命令行/IDEA）。
另外 IDEA 里也要一致：`Settings → Build → Build Tools → Maven → Runner → JRE` 设为 **21**。

---

### 10.6 ⭐ `java.lang.ExceptionInInitializerError` / `com.sun.tools.javac.code.TypeTag :: UNKNOWN`

**完整报错**：

```
java: java.lang.ExceptionInInitializerError
com.sun.tools.javac.code.TypeTag :: UNKNOWN
```

**根因**：Lombok 版本和编译用的 JDK 不兼容。Lombok 要 hook javac 内部 API，JDK 大版本升级会改这些内部结构，对不上就崩。

本项目**已经把 Lombok 锁定为 1.18.48**（支持 JDK 21～27），所以正常情况下不会遇到。
如果还是遇到了，说明有人改动了 `pom.xml` 或用了更旧的代码版本。

**修复**：确认 `pom.xml` 的 `<properties>` 里有这一行：

```xml
<properties>
    <java.version>21</java.version>
    <lombok.version>1.18.48</lombok.version>   <!-- 支持 JDK 21 ~ 27 -->
</properties>
```

然后依次：
1. Maven 面板 → **🔄 Reload All Maven Projects**
2. `Build → Rebuild Project`
3. 还不行 → `File → Invalidate Caches... → Invalidate and Restart`

> 注意这个坑的迷惑性：**IDEA 编译用的是「Project SDK」，命令行 `mvn` 用的是 `JAVA_HOME`**，
> 两者不是同一个 JDK，所以会出现「命令行能编译、IDEA 报错」的现象。

**验证是否修好**：

```bash
mvn clean package -DskipTests          # 期望 BUILD SUCCESS
javap -verbose -cp target/classes com.hotel.hms.entity.Room | grep "major version"
# 期望：major version: 65   ← 65 即 Java 21 字节码
```

---

### 10.7 页面 404 / 样式全部丢失

- 确认 `application.yml` 里没改坏 `spring.web.resources.static-locations`（应为 `classpath:/static/`）
- `Build → Rebuild Project`，清掉 `target/classes` 里的旧文件
- 浏览器 **强制刷新** `Ctrl + F5`（CSS/JS 被缓存了）

---

### 10.8 页面报 500 / Thymeleaf 模板解析错误

本项目已关闭模板缓存（`spring.thymeleaf.cache: false`），所以**改完 HTML 直接刷新浏览器就生效，不用重启**。

仍然 500 的话，看控制台最下面那段堆栈：
- 会指出具体是哪个模板的哪一行
- 常见原因是自定义 `th:` 属性里表达式写错（引号不配对、枚举当 map 下标用等）

> 💡 **一个真实的坑**（已修复，供你改代码时避雷）：Thymeleaf 里用枚举做 map 下标要写
> `${map.get(enumValue)}`，**不能**写 `${map[enumValue]}`，否则报
> `IllegalArgumentException: The target type java.lang.Enum does not refer to an enum`。

---

### 10.9 中文乱码

1. 确认第 **5.5 节** 的编码设置（三项都是 UTF-8）
2. 确认 IDEA 右下角状态栏显示 **UTF-8**
3. 命令行启动时补一个参数：

```bash
java -Dfile.encoding=UTF-8 -jar target/hotel-management-system.jar
```

---

### 10.10 想清空数据、从演示状态重来

```sql
DROP DATABASE hotel_management;
```

然后重启应用即可——会自动重建库、重建表、重新灌演示数据。

只想清空测试数据、保留演示基线（推荐）：

```bash
bash scripts/clean-test-data.sh
```

---

### 10.11 不想要演示数据（干干净净一个空系统）

改 `application.yml`：

```yaml
hotel:
  init-demo-data: false    # 不再灌房型/客房/订单，只建 4 个账号
```

保存后重启。

---

### 10.12 IDEA 里满屏红字，但项目其实能跑

按顺序处理：

1. `Build → Rebuild Project`
2. 右侧 Maven 面板 → **🔄 Reload All Maven Projects**
3. 检查 5.3（SDK 21）与 5.4（注解处理）
4. 终极手段：`File → Invalidate Caches... → Invalidate and Restart`

---

### 10.13 Maven 一直卡在 `Resolving dependencies` / 下载龟速

配置阿里云镜像，见 **第 5.6 节**。配完记得点 **🔄 Reload All Maven Projects**。

---

### 10.14 忘了 admin 密码 / 想加人

- 忘了密码：用另一个管理员账号登录 → 「账号权限」→ 重置密码
- 连管理员都进不去：直接改数据库（密码是 BCrypt 加密的，不能直接写明文），
  最省事的做法是 `DROP DATABASE hotel_management;` 后重启，账号会重新初始化成默认密码（见 10.10）
- 加人：「账号权限 → 新建账号」，指定角色即可

---

### 10.15 怎么正确关闭系统

- **IDEA 里**：点控制台左侧的红色方块 ■（Stop），或右上角停止按钮
- **命令行跑的**：在那个窗口按 `Ctrl + C`
- ⚠️ **不要**直接叉掉控制台窗口，可能留下僵尸的 java 进程占着 8080 端口
  （真遇到了：`netstat -ano | findstr :8080` 查出 PID 再 `taskkill /F /PID xxx`）
- MySQL 服务不用关，让它开着就行

---

## 第 11 章：配置速查表

| 配置项 | 在哪 | 默认值 |
| --- | --- | --- |
| 服务端口 | `application.yml` → `server.port` | `8080` |
| 上下文路径 | `application.yml` → `server.servlet.context-path` | `/` |
| 数据库地址 | `application.yml` → `spring.datasource.url` | `jdbc:mysql://127.0.0.1:3306/hotel_management` |
| 数据库账号 / 密码 | `application.yml` → `spring.datasource.username` / `password` | `root` / `123456` |
| 建表策略 | `application.yml` → `spring.jpa.hibernate.ddl-auto` | `update`（自动建表/加字段） |
| 模板缓存 | `application.yml` → `spring.thymeleaf.cache` | `false`（改页面免重启） |
| 演示数据开关 | `application.yml` → `hotel.init-demo-data` | `true` |
| 启动横幅开关 | `application.yml` → `hotel.startup.print-banner` | `true` |
| 预订最大提前天数 | `application.yml` → `hotel.max-advance-days` | `365` |
| 单次最多住几晚 | `application.yml` → `hotel.max-stay-nights` | `30` |
| 启动类 | `src/main/java/com/hotel/hms/HotelManagementApplication.java` | — |
| 打包产物 | `target/hotel-management-system.jar` | — |

**端口占用一览（记住这三个就够）**：

| 端口 | 谁在用 | 被占了会怎样 |
| --- | --- | --- |
| **3306** | MySQL | 应用启动报 `Communications link failure` |
| **8080** | 本项目 | 启动报 `Port 8080 was already in use` |

---

## 第 12 章：目录结构参考

```
hotel-management-system/
├── pom.xml                       # Maven 配置（Java 21 + Spring Boot 3.3.5 + Lombok 1.18.48）
├── README.md                     # 项目说明：功能总览 / 业务规则 / 测试说明
├── docs/
│   ├── IDEA-小白部署教程.md        # ← 本文档
│   ├── IDEA-本地部署文档.md        # 更偏「排查手册」的版本，可与本文档对照看
│   ├── 优化建议.md                 # 代码审查报告
│   ├── 权限功能清单.md             # 按角色的权限矩阵
│   └── screenshots/               # 各批次页面截图
├── scripts/                       # 验收脚本
│   ├── func-test.sh               # 主链路冒烟（18 项）
│   ├── opt1~opt7-test.sh          # 各批次功能验收
│   ├── run-all-tests.sh           # 一键跑全部（715 项）
│   └── clean-test-data.sh         # 清理测试数据、恢复演示基线
└── src/main/
    ├── java/com/hotel/hms/
    │   ├── HotelManagementApplication.java   # ← 启动类（点这里绿色三角）
    │   ├── config/        # 安全配置、数据初始化、启动横幅
    │   ├── controller/    # 各页面与接口的入口
    │   ├── service/       # 业务逻辑（状态流转、事务、校验）
    │   ├── repository/    # 数据库访问
    │   ├── entity/        # 数据库表对应的类
    │   ├── dto/           # 页面表单与统计对象
    │   └── common/        # 业务异常与全局异常处理
    └── resources/
        ├── application.yml          # ← 主要配置文件（改密码/端口就在这）
        ├── templates/               # 所有网页（Thymeleaf）
        └── static/                  # CSS / JS / 第三方库（全部本地化，断网可用）
```

---

## 附录 A：每日工作流（把这三步背下来）

```
① 启动 MySQL（若未自启）      net start MySQL
② 启动项目                     IDEA 里点绿色三角   （或 java -jar target/hotel-management-system.jar）
③ 打开浏览器                   http://localhost:8080
────────────────────────────────────────────────
停下：IDEA 控制台点红色方块 ■  （别叉窗口）
```

## 附录 B：命令速查（Windows / Git Bash）

| 我要干什么 | 命令 |
| --- | --- |
| 看 JDK 版本 | `java -version` |
| 看 Maven 与它用的 JDK | `mvn -v` |
| 启动 MySQL 服务 | `net start MySQL`（管理员权限） |
| 连数据库 | `mysql -uroot -p123456` |
| 看 3306 是否被占 | `netstat -ano \| findstr :3306` |
| 看 8080 是否被占 | `netstat -ano \| findstr :8080` |
| 杀掉占用端口的进程 | `taskkill /F /PID <PID>` |
| 打包 | `mvn clean package -DskipTests` |
| 跑主链路测试 | `bash scripts/func-test.sh` |
| 跑全部测试 | `bash scripts/run-all-tests.sh` |
| 清理测试数据 | `bash scripts/clean-test-data.sh` |

## 附录 C：部署完成的判断标准（自查清单）

按顺序打勾，全部打上就是彻底成功了：

- [ ] `java -version` 输出 21.x
- [ ] `net start MySQL` 显示服务已在运行（或 `services.msc` 里看到 MySQL 是「正在运行」）
- [ ] IDEA 打开的是**包含 `pom.xml` 的那一层目录**
- [ ] `Project Structure` 里 SDK = 21，`Annotation Processors` 已勾选
- [ ] `Build → Rebuild Project` 显示 BUILD SUCCESS
- [ ] `application.yml` 里的数据库密码与本机一致
- [ ] 控制台出现 `Started HotelManagementApplication` 与启动横幅
- [ ] 浏览器能打开 `http://localhost:8080` 并看到「云栖大酒店」登录页
- [ ] `admin / admin123` 能登录并看到数据看板
- [ ] 用 `housekeeping / 123456` 访问 `/customers` 会跳到 403（权限闭环成立）
- [ ] （可选）`bash scripts/func-test.sh` 输出 `PASS=18 FAIL=0`

---

## 附录 D：四个身份的「业务闭环」检查表

「闭环」的意思是：**一个身份能不能靠自己把一件事从开始做到结束，中途不用等别人、也不会卡在半路。**

比如客房服务员打扫完房间，如果只有「完成」按钮、没人能验收，房间就永远变不回「空闲」、卖不出去——那就不算闭环。

按下面的表各走一遍，四条链路都能走通，说明系统是通的：

### D.1 前台接待：一单生意从生到死

| 步骤 | 在哪 | 期望结果 |
| --- | --- | --- |
| ① 建预订 | 预订管理 → 新建预订 | 选日期后房型/房价自动带出，晚数 × 房价实时算出 |
| ② 办入住 | 订单列表 → 办理入住 | 状态变「已入住」；**房态图对应房间自动变红** |
| ③ 加消费 | 账单页 → 追加消费 | 明细进账单，应收合计自动增加 |
| ④ 结账 | 账单页 → 结账 | 状态变「已结账」，收款记录生成；**房态图房间自动转「待清洁」** |
| ⑤ 开票 | 发票管理 → 开具 | 只有已结账账单能开；可开额度 = 应收 − 已开有效发票 |

> ✅ **闭环成立**：从建单到开票，前台一个人能走完，退房后房态自动流转给客房部。

### D.2 客房服务：一间脏房回到可售

| 步骤 | 在哪 | 期望结果 |
| --- | --- | --- |
| ① 看派工 | 派工与验收 | 能看到派给自己的工单（**但看不到「新建派工」按钮**，符合权限） |
| ② 打勾执行 | 派工单详情 → 清洁检查单 | 逐项打勾；**没打勾完不能提交完成**（硬闸门） |
| ③ 标记完成 | 工单 → 完成 | 状态变「待验收」；**房间仍为「待清洁」**（这一步房间还不能卖） |
| ④ 现场上报 | 移动端 `/hk` | 能报修、登记失物、迷你吧挂账、改房态、一键完成工单 |
| ⑤ 看板 | 数据看板 | 房态与待办正常，**但没有任何金额显示** |

> ⚠️ **注意**：客房服务自己**不能验收**——不能自己验自己做的房。
> 所以这个身份的闭环截止到「标记完成」，最后一步交给前台或主管，这是**故意的设计**（见 D.3）。

### D.3 客房主管：把脏房真正变回可售（必须走通这一段）

| 步骤 | 在哪 | 期望结果 |
| --- | --- | --- |
| ① 自行派工 | 派工与验收 → 新建派工 | 能选房间与服务员建单；**服务员看不到这个按钮**（手敲接口也会被 403 拦住） |
| ② 排班 | 排班与工作量 | 能排早/中/晚班；同一人同一天重复排班会被拒 |
| ③ 验收 | 工单 → 验收通过 | **房间自动从「待清洁」变「空闲」——闭环在这里合上** |
| ④ 打回 | 工单 → 打回重做 | **必须填原因**，工单回到服务员手上 |
| ⑤ 耗材 / 外送 | 耗材出入库 / 布草外送 | 能建档、报废、盘点调整；外送批次能建单与结算（未回收不能结算） |

> ✅ **闭环成立**：主管补齐了「服务员完成 → 验收 → 房态回空闲」这最后一环。
> 而且验收权限同时给了**前台**（权限对称），所以主管不在岗时前台也能验收，房间不会卡住。

### D.4 管理员：全局兜底与留痕

| 步骤 | 在哪 | 期望结果 |
| --- | --- | --- |
| ① 全部模块 | 左侧菜单 | 四类业务的菜单全都在，无需切换账号 |
| ② 账号权限 | 账号权限 | 能新建账号、重置密码、启用/停用 |
| ③ 操作日志 | 操作日志 | 折扣、反结账、撤销挂账、派工、验收、房态变更等关键动作**全都有记录** |
| ④ 财务兜底 | 账单 → 反结账 | 只有管理员能反结账（撤销已结账账单） |
| ⑤ 权限边界 | 切到客房账号看一圈 | 客房看不到任何金额、进不了客户/订单/账单/账号 |

> ✅ **闭环成立**：管理员是所有链路的兜底角色，且关键动作全部留痕可审计。

### D.5 闭环是否成立的判断口诀

```
一间脏房能不能卖出去？
  服务员完成 → 有人验收 → 房态自动回「空闲」   ← 三个环节缺一不可，缺了就是断链
一笔钱能不能收到账？
  建单 → 入住 → 加消费 → 结账 → 开票            ← 前台一个人能走完
出了事能不能查到？
  关键动作 → 操作日志（仅管理员可看）            ← 留痕不断
越权能不能被挡住？
  换个低权限账号手敲 URL → 403                   ← 服务端拦，不是藏按钮
```

