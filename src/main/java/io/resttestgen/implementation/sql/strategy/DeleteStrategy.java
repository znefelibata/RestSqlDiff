package io.resttestgen.implementation.sql.strategy;

import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.core.datatype.parameter.attributes.ParameterType;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.helper.SqlGenerationHelper;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.util.ParameterToJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeleteStrategy extends RestStrategy {
    private static final Logger log = LoggerFactory.getLogger(DeleteStrategy.class);
    @Override
    public SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable) {
        //根据Operation对象，生成对应的DELETE语句
        //获取所有的叶子参数
        Map<String, Object> whereValues = new LinkedHashMap<>(); // 存 WHERE 子句的参数
        Collection<LeafParameter> leaves = op.getLeaves();
        Collection<ArrayParameter> arrays = op.getArrays();
        for (LeafParameter leaf : leaves) {
            if (!(leaf.getParent() instanceof ArrayParameter)) {
                String whereName = leaf.getName().toString();
                Object val = leaf.getValue();
                // 对于 DELETE，无论是 PATH, QUERY 还是 BODY，都放入 whereValues 注意：Body 参数在 DELETE 中很少见，但如果存在，通常也是做条件筛选
                whereValues.put(whereName, val);
            }
        }
        for (ArrayParameter array : arrays) {
            if (array.getElements().isEmpty()) {
                continue;
            }
            String whereName = array.getName().toString();
            List<Object> arrayValues = ParameterToJsonUtil.getArrayValues(array);
            whereValues.put(whereName, arrayValues);
        }
        if (!whereValues.isEmpty()) {
            // 移除所有 value 为 null 的键值对
            whereValues.entrySet().removeIf(entry -> entry.getValue() == null);
        }
        String whereClauseString = SqlGenerationHelper.generateWhereClauseAndCleanParams(whereValues, op, convertSequenceToTable);
        if (whereClauseString.isEmpty()) {
            log.warn("DELETE operation skipped: No where parameters found (Dangerous full table delete) for {}", op.getEndpoint());
            SqlInteraction failedInteraction = new SqlInteraction();
            failedInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            failedInteraction.setErrorMessage("Dangerous Operation: DELETE without WHERE/LIMIT clause.");
            return failedInteraction;
        }
        StringBuilder deleteTableSQL = new StringBuilder("DELETE FROM " + convertSequenceToTable.getTableName());
        deleteTableSQL.append(whereClauseString);
        deleteTableSQL.append(";");
        SqlInteraction sqlInteraction = new SqlInteraction();
        try {
            databaseHelper.executeDelete(deleteTableSQL.toString(), whereValues, sqlInteraction);
        } catch (RuntimeException e) {
            log.error("Failed to generate SQL interaction for {} operation {}: {}",op.getMethod(), op.getEndpoint(), e.getMessage());
        }
        return sqlInteraction;
    }
}
