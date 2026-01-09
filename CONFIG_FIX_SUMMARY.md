# 配置修复总结

## 已完成的修改

### 1. 修复了 `apis/gitlab/api-config.yml` 配置文件

**问题：**
- 字段名使用了 `auth` 而不是代码期望的 `authenticationCommands`
- `duration` 字段使用了字符串格式 `"31104000"` 而不是数字

**修复：**
```yaml
name: Gitlab
authenticationCommands:
  name: Authorization
  value: Bearer glpat-YKpZMLfO6s-mrignM-JTb286MQp1OjEH.01.0w1328bku
  type: apiKey
  in: header
  duration: 31104000
```

### 2. 创建了配置文档

在 `docs/GITLAB_AUTH_CONFIG.md` 中详细说明了：
- 项目的认证配置方式（与原始 RestTestGen 不同）
- 正确的配置格式和字段说明
- 完整的故障排查步骤
- 常见问题解答

## 配置方式说明

### 原始 RestTestGen vs 当前项目

**原始方式：** 执行命令获取认证信息
```yaml
authenticationCommands:
  default: "python3 /path/to/auth-script.py"
```

**当前项目：** 直接在配置文件中提供认证信息
```yaml
authenticationCommands:
  name: Authorization
  value: Bearer TOKEN
  in: header
  duration: 600
```

代码修改位置：`src/main/java/io/resttestgen/boot/ApiUnderTest.java` (第 119-131 行)

## 后续步骤

### 如果仍然遇到 401 错误：

1. **验证 GitLab 是否运行：**
   ```powershell
   docker ps | Select-String -Pattern "gitlab"
   ```

2. **测试 API 连接：**
   ```powershell
   Invoke-WebRequest -Uri "http://localhost/api/v4/version" `
     -Headers @{"Authorization"="Bearer YOUR_TOKEN"} `
     -UseBasicParsing
   ```

3. **生成新的 Access Token：**
   - 登录 GitLab: http://localhost
   - User Settings → Access Tokens
   - 创建新 Token，勾选 `api` scope
   - 更新配置文件中的 `value` 字段

4. **检查服务器地址：**
   - 查看 `apis/gitlab/specifications/openapi.json` 中的 `servers[0].url`
   - 确保与 GitLab 实际运行的地址一致
   - 如果不一致，在 `api-config.yml` 添加 `host` 字段覆盖

## 验证配置

运行测试时，不应再看到以下错误：
- ✗ `Invalid authentication command specified in the API configuration`
- ✗ `This authentication information will be probably ignored`

如果配置正确但仍有 401 错误，问题在于：
- GitLab 未运行
- Token 无效/过期
- 服务器地址不匹配
- Token 权限不足

详细故障排查步骤请参考 `docs/GITLAB_AUTH_CONFIG.md`。

