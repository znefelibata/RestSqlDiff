package io.resttestgen.implementation.sql.strategy;

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

public class GetStrategy extends RestStrategy {
    private static final Logger log = LoggerFactory.getLogger(DeleteStrategy.class);
    @Override
    public SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable) {
        // 根据 Operation 对象，生成对应的 SELECT 语句
        // GET 请求的核心是筛选，所有参数都放入 whereValues
        String path = op.getEndpoint();
        Map<String, Object> whereValues = new LinkedHashMap<>();
        Collection<LeafParameter> leaves = op.getLeaves();
        Collection<ArrayParameter> arrays = op.getArrays();
        for (LeafParameter leaf : leaves) {
            if (!(leaf.getParent() instanceof ArrayParameter)) {
                String whereName = leaf.getName().toString();
                Object val = leaf.getValue();
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
        //查询全表是合法的
        if (whereClauseString.isEmpty()) {
            log.warn("SELECT operation : 进行全表查询 No parameters found (Dangerous full table SELECT) for {}", op.getEndpoint());
        }
        StringBuilder selectTableSQL = new StringBuilder("SELECT * FROM " + convertSequenceToTable.getTableName());
        selectTableSQL.append(whereClauseString);
        selectTableSQL.append(";");
        SqlInteraction sqlInteraction = new SqlInteraction();
        try {
            // 需要在 DatabaseHelper 中实现 executeSelect
            databaseHelper.executeGet(selectTableSQL.toString(), whereValues, sqlInteraction);
        } catch (RuntimeException e) {
            log.error("Failed to generate SQL interaction for {} operation {}: {}", op.getMethod(), op.getEndpoint(), e.getMessage());
        }

        return sqlInteraction;
    }



}
