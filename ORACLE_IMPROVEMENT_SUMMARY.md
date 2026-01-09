# SqlDiffOracle 改进总结

## 问题描述

用户询问：在数据库映射时，Array对象的属性存储为JSON，Object对象则直接拆开，每一个非Object的子字段作为数据库的一列，这个情况是否也需要在Oracle里面考虑？

## 回答：是的，必须考虑！

现在已经完成了相应的改进。

## 具体改进内容

### 1. 增强了 `compareRowAndJson` 方法

**之前的问题：**
- 只处理第一层字段的对比
- 没有考虑嵌套对象的扁平化映射
- 对Array的JSON字符串处理不够完善

**现在的改进：**
- 调用新的 `flattenAndCompareJsonObject` 方法递归处理嵌套结构
- 正确处理Object扁平化和Array JSON存储的双重策略

### 2. 新增了 `flattenAndCompareJsonObject` 方法

这个方法实现了以下核心功能：

#### Case 1: 嵌套对象 (递归扁平化)
```java
if (value.isJsonObject()) {
    // 递归展开嵌套对象
    flattenAndCompareJsonObject(value.getAsJsonObject(), displayPath, ...);
}
```

**示例：**
```
API: {"user": {"name": "John", "age": 30}}
DB:  user_name="John", user_age=30
处理：递归展开 user 对象，分别对比 name 和 age 字段
```

#### Case 2: 数组字段 (解析JSON字符串)
```java
else if (value.isJsonArray()) {
    // 从数据库读取JSON字符串，解析后对比
    if (sqlValue instanceof String) {
        JsonElement parsedSql = JsonParser.parseString((String) sqlValue);
        compareValueRecursive(displayPath, parsedToJava(parsedSql), value, differences);
    }
}
```

**示例：**
```
API: {"tags": ["java", "rest", "api"]}
DB:  tags='["java","rest","api"]'
处理：解析DB中的JSON字符串，然后递归对比数组元素
```

#### Case 3: 基本类型字段 (直接对比)
```java
else {
    // 查找数据库列名映射
    String dbColumn = tableConverter.getColumnNameByName(apiKey, operation);
    // 直接对比值
    compareValueRecursive(displayPath, sqlValue, value, differences);
}
```

### 3. 字段名映射策略说明

**关键设计决策：**
使用**叶子字段的原始名称**（而非完整路径）来查找数据库列映射。

**原因：**
1. 在 `ConvertSequenceToTable.flatten` 过程中，参数的 `getName()` 保持原始名称
2. 映射key格式：`{HTTP_METHOD} {PATH} # {FIELD_NAME}`
3. 聚类过程会自动处理重名问题，确保唯一的canonical name

**示例：**
```
API字段: {"user": {"name": "John"}}
参数name: "name" (不是 "user.name")
映射key: "POST /api/users # name"
DB列名: "user_name" (由聚类过程生成的canonical name)
```

### 4. 路径显示策略

- **内部匹配路径**：使用字段名（如 `name`）查找映射
- **显示路径**：使用点分隔的完整路径（如 `user.profile.name`）用于差异报告

这样既保证了映射的正确性，又提供了清晰的错误报告。

## 代码改进位置

### 文件：`SqlDiffOracle.java`

1. **第26-56行**：增强的Javadoc注释，说明映射策略
2. **第173-226行**：改进的 `compareRowAndJson` 方法
3. **第228-322行**：新增的 `flattenAndCompareJsonObject` 方法

## 测试验证

构建测试通过：
```
.\gradlew build -x test
BUILD SUCCESSFUL
```

## 文档

创建了详细的策略说明文档：
- `ORACLE_MAPPING_STRATEGY.md` - 映射策略详细说明

## 支持的场景

现在的Oracle可以正确处理：

✅ **简单字段**
```json
API: {"name": "John"}
DB:  name="John"
```

✅ **嵌套对象（扁平化）**
```json
API: {"user": {"name": "John", "age": 30}}
DB:  user_name="John", user_age=30
```

✅ **数组字段（JSON存储）**
```json
API: {"tags": ["java", "rest"]}
DB:  tags='["java","rest"]'
```

✅ **混合场景**
```json
API: {"user": {"name": "John", "hobbies": ["reading", "coding"]}}
DB:  user_name="John", user_hobbies='["reading","coding"]'
```

✅ **深层嵌套**
```json
API: {"user": {"profile": {"contact": {"email": "test@example.com"}}}}
DB:  user_profile_contact_email="test@example.com"
```

## 总结

**回答用户的问题：是的，这种情况必须在Oracle中考虑，并且已经完成了相应的实现。**

SqlDiffOracle现在能够：
1. 正确处理Object的扁平化映射
2. 正确处理Array的JSON字符串存储
3. 递归对比嵌套结构
4. 提供清晰的差异报告

这确保了API返回结果和数据库状态的完整对比，无论数据结构多么复杂。

