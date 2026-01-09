# SqlDiffOracle 映射策略说明

## 问题背景

在将RESTful API映射到数据库时，系统采用了以下策略：
1. **Object对象**：会被**扁平化（flatten）**处理，每个非Object的子字段作为数据库的一列
2. **Array对象**：直接存储为**JSON字符串**格式

## 映射示例

### Object扁平化
API返回：
```json
{
  "user": {
    "name": "John",
    "age": 30,
    "profile": {
      "bio": "Developer"
    }
  }
}
```

数据库列：
- `user_name` (VARCHAR)
- `user_age` (INT)
- `user_profile_bio` (VARCHAR)

### Array存储为JSON
API返回：
```json
{
  "tags": ["java", "rest", "api"]
}
```

数据库列：
- `tags` (JSON) 存储值: `["java", "rest", "api"]`

## Oracle实现策略

### 1. 扁平化对比逻辑

在 `SqlDiffOracle.flattenAndCompareJsonObject()` 方法中：

- **递归展开嵌套对象**：当遇到嵌套的JSON对象时，递归地展开每一层
- **字段名映射**：使用叶子字段的原始名称（不是完整路径）查找数据库列映射
  - 例如：`user.name` 中的字段名是 `name`，不是 `user_name`
  - 通过 `tableConverter.getColumnNameByName(apiKey, operation)` 获取实际的数据库列名
- **显示路径**：使用点分隔的完整路径（如 `user.profile.bio`）用于差异报告

### 2. Array对比逻辑

当遇到Array字段时：

- **解析JSON字符串**：从数据库读取的JSON字符串需要先解析成JsonElement
- **递归对比**：解析后与API返回的数组进行递归对比
- **异常处理**：如果数据库中的值不是有效JSON，则按原值比较

### 3. 关键代码流程

```java
private void flattenAndCompareJsonObject(JsonObject jsonObject, String parentPath, ...) {
    for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
        String apiKey = entry.getKey();
        String displayPath = parentPath.isEmpty() ? apiKey : parentPath + "." + apiKey;
        JsonElement value = entry.getValue();

        if (value.isJsonObject()) {
            // Case 1: 嵌套对象 - 递归展开
            flattenAndCompareJsonObject(value.getAsJsonObject(), displayPath, ...);
        } 
        else if (value.isJsonArray()) {
            // Case 2: 数组 - 作为JSON字符串处理
            String dbColumn = tableConverter.getColumnNameByName(apiKey, operation);
            Object sqlValue = sqlRow.get(dbColumn);
            if (sqlValue instanceof String) {
                JsonElement parsedSql = JsonParser.parseString((String) sqlValue);
                compareValueRecursive(displayPath, parsedToJava(parsedSql), value, differences);
            }
        } 
        else {
            // Case 3: 基本类型 - 直接映射和比较
            String dbColumn = tableConverter.getColumnNameByName(apiKey, operation);
            Object sqlValue = sqlRow.get(dbColumn);
            compareValueRecursive(displayPath, sqlValue, value, differences);
        }
    }
}
```

## 映射机制说明

### ConvertSequenceToTable的映射

映射的Key格式：`{HTTP_METHOD} {PATH} # {FIELD_NAME}`

例如：
- `POST /api/users # name` -> `user_name`
- `POST /api/users # age` -> `user_age`

注意：
1. **字段名保持原始**：即使是嵌套对象的字段，映射key中仍使用原始字段名（如 `name`），而不是完整路径
2. **聚类去重**：通过聚类过程处理重名问题，确保每个字段映射到唯一的数据库列
3. **Canonical Name**：数据库列名使用扁平化后的完整路径（用下划线连接）

### 为什么使用字段名而非完整路径？

在flatten过程中：
- 参数的 `getName()` 保持原始字段名
- 参数的 `getTableName()` 设置为完整路径（下划线连接）
- 映射创建时使用 `getName()`，所以映射key只包含字段名

聚类过程（clustering）会自动处理以下情况：
- 不同操作中的同名字段
- 不同上下文中的同名字段
- 通过operation和endpoint区分，确保正确映射

## 验证和测试

修改后的Oracle可以正确处理：
1. ✅ 简单字段的对比
2. ✅ 嵌套对象扁平化后的对比
3. ✅ Array字段作为JSON字符串的对比
4. ✅ 混合场景（对象中包含数组，数组中包含对象）

## 改进建议

未来可以考虑的改进：
1. 对于非常深的嵌套结构，可以添加路径深度限制
2. 对于大型数组，可以添加元素数量限制
3. 可以添加更详细的类型不匹配报告（如期望JSON但得到字符串）

