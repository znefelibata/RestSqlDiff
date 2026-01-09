# SqlDiffOracle 使用示例

## 场景1：嵌套对象扁平化

### API返回
```json
{
  "user": {
    "name": "John Doe",
    "age": 30,
    "email": "john@example.com"
  }
}
```

### 数据库存储
| id | user_name | user_age | user_email |
|----|-----------|----------|------------|
| 1  | John Doe  | 30       | john@example.com |

### Oracle处理流程
1. 解析API响应的JSON
2. 遇到 `user` 对象 → 递归展开
3. 对于 `name` 字段：
   - 使用 `getColumnNameByName("name", operation)` 查找映射
   - 得到数据库列名 `user_name`
   - 对比值：API的 `"John Doe"` vs DB的 `"John Doe"` ✅
4. 对于 `age` 字段：同理对比 ✅
5. 对于 `email` 字段：同理对比 ✅

### 结果
所有字段匹配 → **测试通过**

---

## 场景2：数组字段JSON存储

### API返回
```json
{
  "tags": ["java", "rest", "api", "testing"],
  "version": "1.0"
}
```

### 数据库存储
| id | tags | version |
|----|------|---------|
| 1  | ["java","rest","api","testing"] | 1.0 |

### Oracle处理流程
1. 对于 `tags` 字段：
   - 检测到是数组 `isJsonArray() == true`
   - 查找数据库列名 → `tags`
   - 从DB读取：`"[\"java\",\"rest\",\"api\",\"testing\"]"` (字符串)
   - 解析JSON字符串 → `JsonArray`
   - 递归对比每个元素：
     - `tags[0]`: "java" vs "java" ✅
     - `tags[1]`: "rest" vs "rest" ✅
     - `tags[2]`: "api" vs "api" ✅
     - `tags[3]`: "testing" vs "testing" ✅
2. 对于 `version` 字段：
   - 基本类型，直接对比 ✅

### 结果
所有字段匹配 → **测试通过**

---

## 场景3：混合场景

### API返回
```json
{
  "product": {
    "name": "Laptop",
    "price": 999.99,
    "categories": ["electronics", "computers"],
    "specs": {
      "cpu": "Intel i7",
      "ram": "16GB"
    }
  }
}
```

### 数据库存储
| id | product_name | product_price | product_categories | product_specs_cpu | product_specs_ram |
|----|-------------|---------------|-------------------|------------------|------------------|
| 1  | Laptop      | 999.99        | ["electronics","computers"] | Intel i7 | 16GB |

### Oracle处理流程
1. 遇到 `product` 对象 → 递归展开
2. 对于 `name`：
   - 基本类型 → 映射到 `product_name` → 对比 ✅
3. 对于 `price`：
   - 基本类型 → 映射到 `product_price` → 对比 ✅
4. 对于 `categories`：
   - 数组 → 映射到 `product_categories`
   - 解析DB的JSON字符串
   - 递归对比数组元素 ✅
5. 遇到 `specs` 对象 → 继续递归展开
6. 对于 `cpu`：
   - 基本类型 → 映射到 `product_specs_cpu` → 对比 ✅
7. 对于 `ram`：
   - 基本类型 → 映射到 `product_specs_ram` → 对比 ✅

### 结果
所有字段匹配 → **测试通过**

---

## 场景4：检测差异

### API返回
```json
{
  "user": {
    "name": "Jane Smith",
    "age": 25
  }
}
```

### 数据库存储
| id | user_name | user_age |
|----|-----------|----------|
| 1  | John Doe  | 25       |

### Oracle处理流程
1. 对于 `name` 字段：
   - API: "Jane Smith"
   - DB:  "John Doe"
   - **差异检测** ❌

### 差异报告
```json
{
  "path": "user.name",
  "sqlColumn": "user_name",
  "sqlValue": "John Doe",
  "apiValue": "Jane Smith",
  "difference": "VALUE_MISMATCH"
}
```

### 结果
检测到差异 → **测试失败**

---

## 关键点总结

### 1. Object扁平化
- API的嵌套对象会被递归展开
- 每个叶子字段映射到扁平化的数据库列
- 使用点号显示路径（如 `user.profile.email`）

### 2. Array JSON存储
- 数组存储为JSON字符串
- Oracle会自动解析JSON字符串
- 递归对比数组元素

### 3. 字段映射
- 使用叶子字段的原始名称查找映射
- 通过 `ConvertSequenceToTable.getColumnNameByName()`
- 聚类过程确保唯一的数据库列名

### 4. 递归对比
- 支持任意深度的嵌套
- 自动处理混合类型（对象+数组+基本类型）
- 生成详细的差异报告

