# GitLab API 身份认证配置指南

## 当前状态

✅ **配置文件已修复**
- 字段名从 `auth` 改为 `authenticationCommands`
- `duration` 从字符串改为数字类型

## 配置文件说明

本项目的代码已经被修改，与原始的 RestTestGen 框架不同。

### 原始 RestTestGen 的认证方式
在原始版本中，`authenticationCommands` 是一个命令映射，格式如下：

```yaml
authenticationCommands:
  default: "python3 /path/to/auth-script.py"
  admin: "echo {\"name\": \"apikey\", \"value\": \"token123\", \"in\": \"header\", \"duration\": 6000}"
```

命令执行后需要返回 JSON 格式：
```json
{
  "name": "Authorization",
  "value": "Bearer token",
  "in": "header",
  "duration": 600
}
```

### 当前项目的认证方式（已修改）
代码已被修改为直接从配置文件读取认证信息字段，**不再执行命令**。

#### 正确的配置格式 (apis/gitlab/api-config.yml)

```yaml
name: Gitlab
authenticationCommands:
  name: Authorization
  value: Bearer glpat-YKpZMLfO6s-mrignM-JTb286MQp1OjEH.01.0w1328bku
  type: apiKey
  in: header
  duration: 31104000
```

### 字段说明

| 字段 | 类型 | 说明 | 示例 |
|-----|------|------|------|
| `name` | String | HTTP 头名称或查询参数名 | `Authorization` |
| `value` | String | 认证值（包括前缀如 Bearer） | `Bearer glpat-xxx` |
| `type` | String | 认证类型（可选，当前未使用） | `apiKey` |
| `in` | String | 参数位置：`header` 或 `query` | `header` |
| `duration` | Number | Token 有效期（秒） | `31104000` (360天) |

### 注意事项

1. **字段名必须是 `authenticationCommands`**（不是 `auth`）
2. **`duration` 必须是数字类型**，不要用引号（`31104000` 而不是 `"31104000"`）
3. **`value` 包含完整的认证值**，包括 `Bearer ` 前缀
4. **Token 需要在 GitLab 中生成**：
   - 登录 GitLab
   - 进入 User Settings → Access Tokens
   - 创建 Token 并勾选 `api` 权限
   - 复制生成的 Token（以 `glpat-` 开头）

### 代码位置
认证信息的加载逻辑在：
- `src/main/java/io/resttestgen/boot/ApiUnderTest.java` 第 119-131 行
- `src/main/java/io/resttestgen/boot/AuthenticationInfo.java`

### 如何验证配置是否正确

运行测试时，日志中不应再出现：
- `Invalid authentication command specified in the API configuration`
- `401 Unauthorized` 错误

如果仍然出现 401 错误，请检查：
1. GitLab Token 是否有效且未过期
2. Token 是否有足够的权限（需要 `api` scope）
3. GitLab 实例是否可访问
4. OpenAPI 规范中的 host 配置是否正确

## 故障排查

### 1. 检查 GitLab Docker 容器状态
```powershell
docker ps | Select-String -Pattern "gitlab"
```

如果没有输出，说明 GitLab 容器未运行。启动 GitLab：
```powershell
# 示例：使用官方 GitLab Docker 镜像
docker run -d -p 80:80 -p 443:443 -p 22:22 `
  --name gitlab `
  --restart always `
  gitlab/gitlab-ce:latest
```

### 2. 测试 GitLab API 连接
```powershell
# 测试基本连接
Invoke-WebRequest -Uri "http://localhost" -UseBasicParsing

# 测试 API 端点（使用你的 Token）
Invoke-WebRequest -Uri "http://localhost/api/v4/version" `
  -Headers @{"Authorization"="Bearer YOUR_TOKEN_HERE"} `
  -UseBasicParsing
```

如果返回 200 OK，说明连接正常。
如果返回 401，说明 Token 无效或过期。

### 3. 生成新的 GitLab Access Token

1. 访问 GitLab: `http://localhost`
2. 登录后进入：User Settings → Access Tokens
3. 创建新 Token：
   - Name: `RestSqlDiff Test`
   - Expires at: 设置一个未来的日期
   - Scopes: 勾选 `api`
4. 点击 "Create personal access token"
5. 复制生成的 Token（**只显示一次**）
6. 更新 `apis/gitlab/api-config.yml` 中的 `value` 字段

### 4. 检查 OpenAPI 规范中的服务器地址

查看 `apis/gitlab/specifications/openapi.json`:
```json
"servers": [
  {
    "url": "http://localhost/api/v4"
  }
]
```

如果你的 GitLab 运行在不同端口（如 8080），需要修改为：
```json
"servers": [
  {
    "url": "http://localhost:8080/api/v4"
  }
]
```

或者在 `api-config.yml` 中覆盖：
```yaml
name: Gitlab
host: "http://localhost:8080/api/v4"
authenticationCommands:
  name: Authorization
  value: Bearer YOUR_TOKEN_HERE
  type: apiKey
  in: header
  duration: 31104000
```

### 5. 常见问题

**Q: 收到 "Invalid authentication command" 错误**
A: 确保字段名是 `authenticationCommands`（不是 `auth`），且所有必需字段都存在。

**Q: 收到 401 Unauthorized**
A: 
- Token 可能已过期，生成新 Token
- Token 权限不足，确保勾选了 `api` scope
- Token 格式错误，确保包含 `Bearer ` 前缀

**Q: 连接超时**
A: GitLab 容器可能未启动或在不同端口运行。

**Q: Token 在哪里？**
A: Token 格式为 `glpat-` 开头的长字符串，在创建时只显示一次，需要立即复制保存。

## 完整的配置示例

`apis/gitlab/api-config.yml`:
```yaml
name: Gitlab
specificationFileName: openapi.json
authenticationCommands:
  name: Authorization
  value: Bearer glpat-YKpZMLfO6s-mrignM-JTb286MQp1OjEH
  type: apiKey
  in: header
  duration: 31104000
```

注意：上面的 Token 仅为示例，请替换为你自己生成的有效 Token。


