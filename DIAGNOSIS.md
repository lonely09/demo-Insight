# Spring Boot 项目启动失败诊断报告

**日期**: 2026-10-30  
**项目**: springboot-demo (AI 知识库服务)  
**应用名称**: ai-knowledge-base  

---

## 错误信息

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Web server failed to start. Port 8080 was already in use.

Action:
Identify and stop the process that's listening on port 8080 or configure this application to listen on another port.
```

---

## 根因分析

### 根因 #1（编译阶段）：JAVA_HOME 未正确设置 → JRE 而非 JDK

当运行 `mvnw.cmd clean package` 时，系统返回的错误为：

```
[ERROR] No compiler is provided in this environment. Perhaps you are running on a JRE rather than a JDK?
```

**原因**:
- 当前系统的 `java -version` 指向 Java 1.8.0_271（JRE），位置在 `C:\Program Files (x86)\Common Files\Oracle\Java\javapath\java.exe`
- JAVA_HOME 环境变量**未设置**
- Spring Boot 3.2.12 要求 **JDK 17+**，而当前环境只有 JRE 1.8
- JRE 不包含 `javac` 编译器，因此 Maven 编译失败

README.md 中指定的 JDK 路径存在但未被使用：
```
D:\Program Files\devlopertools\zulu-jdk-17   ← Zulu OpenJDK 17.0.21，可用但未配置到 PATH/JAVA_HOME
```

### 根因 #2（运行阶段）：端口 8080 已被占用

成功编译后，Spring Boot 应用初始化正常（MySQL 连接、JPA 初始化、Tomcat 启动均成功），但在启动嵌入式 Tomcat Web Server 时失败：

```
Error starting ApplicationContext. To display the condition evaluation report re-run your application with 'debug' enabled.
...
Web server failed to start. Port 8080 was already in use.
```

**原因**:
- 另一个 Java 进程（PID 28244）正在监听端口 8080
- 推测是此前启动过的同一应用实例未关闭，或端口被其他服务占用

---

## 运行时环境清单

| 组件 | 当前状态 | 期望/建议 |
|------|---------|----------|
| JDK | ❌ `java` → JRE 1.8.0_271，无 javac | JDK 17 → `D:\Program Files\devlopertools\zulu-jdk-17` |
| Maven | ✅ 3.10.0（Maven Wrapper 缓存已下载） | Maven 3.10.0 + JDK 17 |
| MySQL (192.168.1.120:3306) | ✅ 连接成功，hikari pool 启动正常 | 无需修改 |
| Nacos (192.168.1.120:8848) | ⚠️ 客户端可连上，但配置为空 (`config is empty`) | Nacos 上需创建 dataId=`ai-knowledge-base.yaml` |
| 端口 8080 | ❌ 被 PID 28244 的 java.exe 占用 | 杀掉旧进程或换端口 |
| Embedding API / Chat API | 🔍 未在日志中发现错误（应用尚未走到调用处） | 确保 KB_EMBEDDING_BASE_URL 和 KB_CHAT_BASE_URL 可达 |

---

## 修复步骤

### 步骤 1：设置 JAVA_HOME 并重新编译

在 Windows CMD 或 PowerShell 中执行：

```bat
:: 设置 JAVA_HOME 指向正确的 JDK 17
set "JAVA_HOME=D:\Program Files\devlopertools\zulu-jdk-17"

:: （可选但推荐）将 javac 加入 PATH
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: 进入项目目录
cd D:\ai_work\springboot-demo

:: 清理并打包
mvnw.cmd clean package -DskipTests
```

或在 bash/sh 环境中：
```bash
export JAVA_HOME="/d/Program Files/devlopertools/zulu-jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
cd /d/ai_work/springboot-demo
./mvnw clean package -DskipTests
```

### 步骤 2：关闭占用 8080 端口的进程

```bat
:: 查找占用 8080 的进程
netstat -ano | findstr :8080

:: 杀掉该进程（替换 PID 为实际值，通常是 28244）
taskkill /F /PID 28244
```

### 步骤 3：重新启动应用

```bat
set "JAVA_HOME=D:\Program Files\devlopertools\zulu-jdk-17"
java -jar target\springboot-demo-0.0.1-SNAPSHOT.jar
```

或通过 IDEA 运行，在 Run Configuration 中设置 Environment Variables：
```
JAVA_HOME=D:\Program Files\devlopertools\zulu-jdk-17
```

### 步骤 4（可选）：如不想杀进程，可修改端口

编辑 `application.yml`：
```yaml
server:
  port: 8081    # 改为其他空闲端口
```

或以命令行覆盖：
```bat
java -jar target\springboot-demo-0.0.1-SNAPSHOT.jar --server.port=8081
```

---

## 补充说明

### Nacos 配置为空问题

启动日志中有以下警告：
```
WARN ... [Nacos Config] config[dataId=ai-knowledge-base.yaml, group=DEFAULT_GROUP] is empty
```

这是预期行为 — 项目中使用了 `optional:nacos:` 前缀导入，Nacos 上如果没有配置 dataId，不会阻塞启动。如果需要集中管理配置，请在 Nacos 控制台创建：
- **Data ID**: `ai-knowledge-base.yaml`
- **Group**: `DEFAULT_GROUP`
- **内容**: 包含 `datasource.*`, `kb.*` 等配置

### Nacos 兼容提醒

根据 README 中的说明：
- `server-addr` 应填 `192.168.1.120:8848`（服务端主端口），不是 8080（控制台端口）
- 需放行 gRPC 端口 `9848`（8848+1000）和 `9849`（8848+1001）
- namespace 应填写命名空间的 **ID**（如 `ba3dfbf5-f6df-40af-9652-66d05c59bcba`），非名称

### 已知 WARN（非致命）

启动过程中有以下 WARN 级别日志，均为 Spring Cloud Alibaba Nacos 组件的 BeanPostProcessor 顺序警告，不影响功能：
```
Bean 'com.alibaba.cloud.nacos.NacosConfigAutoConfiguration' is not eligible for getting processed by all BeanPostProcessors
```
如需消除，可将相关 bean 声明加上 `@Role(BeanDefinition.ROLE_INFRASTRUCTURE)` 或改用静态工厂方法。

---

## 验证结果

经实际测试：
1. ✅ 设置 JAVA_HOME=Zulu 17 后，`mvn clean package -DskipTests` BUILD SUCCESS（8.8秒）
2. ✅ 关闭 8080 端口占用进程后，Spring Boot 正常启动，Tomcat 在 8080 运行
3. ✅ MySQL 连接池（HikariCP）成功建立
4. ✅ BM25 索引从数据库加载完成（chunks=1, terms=1）
5. ✅ Nacos 空配置情况下不阻塞启动（optional 机制生效）
