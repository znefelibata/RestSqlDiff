package io.resttestgen.implementation.sql.strategy;

import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.helper.SqlGenerationHelper;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.util.ParameterToJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PutStrategy extends RestStrategy {
    private static final Logger log = LoggerFactory.getLogger(PutStrategy.class);
    @Override
    public SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable) {
        //根据Operation对象，生成对应的UPDATE语句
        //获取所有的叶子参数
        Map<String, Object> setValues = new LinkedHashMap<>();   // 存 SET 子句的参数
        Map<String, Object> whereValues = new LinkedHashMap<>(); // 存 WHERE 子句的参数
        Collection<LeafParameter> leaves = op.getLeaves();
        Collection<ArrayParameter> arrays = op.getArrays();
        boolean hasBodyParams = leaves.stream()
                .anyMatch(p -> p.getLocation() == ParameterLocation.REQUEST_BODY);
        for (LeafParameter leaf : leaves) {
            if (!(leaf.getParent() instanceof ArrayParameter)) {
                String columnName = convertSequenceToTable.getColumnNameByParameter(leaf);
                String whereName = leaf.getName().toString();
                Object val = leaf.getValue();
                if (leaf.getLocation() == ParameterLocation.PATH) {
                    // Path 参数 -> 永远是 WHERE
                    whereValues.put(whereName, val);
                } else if (leaf.getLocation() == ParameterLocation.REQUEST_BODY) {
                    // Body 参数 -> 永远是 SET
                    if (columnName != null) {
                        setValues.put(columnName, val);
                    }
                } else if (leaf.getLocation() == ParameterLocation.QUERY) {
                    // Query 参数 -> 智能判定
                    if (!hasBodyParams) {
                        // 如果没有 Body，Query 参数大概率是用来更新值的 (非标准写法)
                        if (columnName != null) {
                            setValues.put(columnName, val);
                        }
                    } else {
                        // 如果有 Body，Query 参数通常是用来做条件过滤的 (标准写法)或者是版本号、乐观锁等
                        whereValues.put(whereName, val);
                    }
                }
            }
        }
        for (ArrayParameter array : arrays) {
            if (array.getElements().isEmpty()) {
                continue;
            }
            String columnName = convertSequenceToTable.getColumnNameByParameter(array);
            String whereName = array.getName().toString();
            List<Object> arrayValues = ParameterToJsonUtil.getArrayValues(array);
            if (array.getLocation() == ParameterLocation.PATH) {
                //不把whereValues改成json字符串形式，因为where条件是用来查询的，必须是具体值
                whereValues.put(whereName, arrayValues);
            } else if (array.getLocation() == ParameterLocation.REQUEST_BODY) {
                if (columnName != null) {
                    setValues.put(columnName, ParameterToJsonUtil.arrayToJsonString(arrayValues));
                }
            } else if (array.getLocation() == ParameterLocation.QUERY) {
                if (!hasBodyParams) {
                    if (columnName != null) {
                        setValues.put(columnName, ParameterToJsonUtil.arrayToJsonString(arrayValues));
                    }
                } else {
                    whereValues.put(whereName, arrayValues);
                }
            }
        }
        if (setValues.isEmpty()) {
            log.warn("UPDATE operation skipped: No columns to update (SET clause empty) for {}", op.getEndpoint());
            return new SqlInteraction(SqlInteraction.OperationType.UPDATE, "",
                    "UPDATE operation skipped: No columns to update (SET clause empty)",
                    new SQLException("Dangerous Operation: UPDATE without WHERE clause is not allowed for " + op.getEndpoint())); // 或者抛异常
        }
        if (!whereValues.isEmpty()) {
            // 移除所有 value 为 null 的键值对
            whereValues.entrySet().removeIf(entry -> entry.getValue() == null);
        }
        String whereClauseString = SqlGenerationHelper.generateWhereClauseAndCleanParams(whereValues, op, convertSequenceToTable);
        if (whereClauseString.isEmpty()) {
            // 极其危险，防止更新全表。除非你确实允许全表更新，否则抛出异常。
            log.warn("UPDATE operation skipped: No where parameters found (Dangerous full table update) for {}", op.getEndpoint());
            SqlInteraction failedInteraction = new SqlInteraction();
            failedInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            failedInteraction.setErrorMessage("Dangerous Operation: UPDATE without WHERE clause is not allowed.");
            return failedInteraction;
        }
        StringBuilder updateTableSQL = new StringBuilder("UPDATE "  + convertSequenceToTable.getTableName() + " SET ");
        for (String key : setValues.keySet()) {
            updateTableSQL.append(key).append(" = ?, ");
        }
        updateTableSQL.setLength(updateTableSQL.length() - 2); // 去掉逗号
        updateTableSQL.append(whereClauseString); // 直接拼接返回的字符串
        updateTableSQL.append(";");
        SqlInteraction sqlInteraction = new SqlInteraction();
        try {
            databaseHelper.executePut(updateTableSQL.toString(), setValues, whereValues, sqlInteraction);
        } catch (RuntimeException e) {
            log.error("Failed to generate SQL interaction for {} operation {}: {}",op.getMethod(), op.getEndpoint(), e.getMessage());
        }
        return sqlInteraction;
    }

}
