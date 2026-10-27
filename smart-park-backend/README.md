# 智慧园区管理系统后端（smart-park-backend）

Spring Boot 3 后端工程，覆盖智慧门禁 / 访客接待 / 智慧仓管 / 告警中心 / 运营看板五大模块。
接口契约见 `../docs/api-contract.md`（唯一事实来源）。

## 技术栈

Java 17 · Spring Boot 3.2 · MyBatis-Plus 3.5 · H2（文件模式 `./data/smart-park`）· Lombok · Maven

## 启动

```bash
mvn spring-boot:run
```

- 接口前缀：`http://localhost:8080/api`
- H2 控制台：`http://localhost:8080/api/h2-console`
  - JDBC URL：`jdbc:h2:file:./data/smart-park`，用户名 `sa`，密码留空

## 说明

- 启动时通过 `schema.sql` 重建 8 张表，并由 `data.sql` 灌入中文演示数据（每次重启数据重置）。
- 统一返回体 `{code, message, data}`，`code=0` 表示成功；分页返回 `{total, list}`。
- 时间字段以字符串存储与返回，格式 `yyyy-MM-dd HH:mm:ss`。
- 定时任务（每 1 分钟）：权限时效扫描、访客滞留扫描、物资逾期扫描。
- 访客身份证接口返回前脱敏（前 6 后 4）。
