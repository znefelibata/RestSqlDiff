# GitLab 身份认证配置 - 快速参考

## ✅ 配置已修复

### 修改的文件
- `apis/gitlab/api-config.yml` - GitLab API 配置文件

### 关键修改
1. **字段名：** `auth` → `authenticationCommands`
2. **数据类型：** `duration: "31104000"` → `duration: 31104000`

---

## 📝 正确的配置格式

```yaml
name: Gitlab
authenticationCommands:
  name: Authorization
  value: Bearer glpat-YOUR_TOKEN_HERE
  type: apiKey
  in: header
  duration: 31104000
```

### 字段说明
| 字段 | 必需 | 类型 | 说明 |
|------|------|------|------|
| `name` | ✓ | String | HTTP 头名称 (通常是 `Authorization`) |
| `value` | ✓ | String | 完整的认证值 (包括 `Bearer ` 前缀) |
| `in` | ✓ | String | 位置：`header` 或 `query` |
| `duration` | ✓ | Number | Token 有效期（秒） |
| `type` | ✗ | String | 类型标识（可选，当前未使用） |

---

## 🔧 获取 GitLab Token

1. 登录 GitLab: `http://localhost`
2. 进入：User Settings → Access Tokens
3. 填写表单：
   - **Name:** `RestSqlDiff Test`
   - **Expires at:** 选择未来日期
   - **Scopes:** ✓ `api`
4. 点击 "Create personal access token"
5. **立即复制 Token**（只显示一次！）
6. 更新配置文件中的 `value` 字段

---

## 🚨 故障排查

### "Invalid authentication command" 错误
- ✓ 确认字段名是 `authenticationCommands`
- ✓ 确认 `duration` 是数字而非字符串

### "401 Unauthorized" 错误
```powershell
# 1. 检查 GitLab 是否运行
docker ps | Select-String -Pattern "gitlab"

# 2. 测试 API 连接
Invoke-WebRequest -Uri "http://localhost/api/v4/version" `
  -Headers @{"Authorization"="Bearer YOUR_TOKEN"} `
  -UseBasicParsing
```

可能原因：
- GitLab 容器未启动
- Token 已过期或无效
- Token 权限不足（未勾选 `api` scope）
- 服务器地址不匹配

### 服务器地址不匹配
检查 `apis/gitlab/specifications/openapi.json`:
```json
"servers": [{ "url": "http://localhost/api/v4" }]
```

如果 GitLab 在不同端口（如 8080），在 `api-config.yml` 添加：
```yaml
host: "http://localhost:8080/api/v4"
```

---

## 📚 详细文档

- 完整配置说明：`docs/GITLAB_AUTH_CONFIG.md`
- 修复总结：`CONFIG_FIX_SUMMARY.md`
- 代码位置：`src/main/java/io/resttestgen/boot/ApiUnderTest.java` (L119-131)

---

## ⚡ 快速验证

运行测试，确认不再出现以下错误：
```
✗ Invalid authentication command specified
✗ This authentication information will be probably ignored
```

如果仍有 401 错误，说明是 Token 或 GitLab 连接问题，不是配置格式问题。

