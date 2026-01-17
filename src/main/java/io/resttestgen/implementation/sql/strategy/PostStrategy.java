package io.resttestgen.implementation.sql.strategy;

import io.resttestgen.core.datatype.parameter.attributes.ParameterLocation;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.util.ParameterToJsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class PostStrategy extends RestStrategy {
    private static final Logger log = LoggerFactory.getLogger(PostStrategy.class);

    @Override
    public SqlInteraction operationToSQL(Operation op, ConvertSequenceToTable convertSequenceToTable) {
        Collection<LeafParameter> leaves = op.getLeaves();
        Collection<ArrayParameter> arrays = op.getArrays();

        List<LeafParameter> pathParams = new ArrayList<>();
        // Identify path parameters
        for (LeafParameter leaf : leaves) {
            if (leaf.getLocation() == ParameterLocation.PATH) {
                pathParams.add(leaf);
            }
        }

        // If path parameters exist, check existence and perform UPDATE (PUT-like)
        if (!pathParams.isEmpty()) {
            SqlInteraction selectInteraction = new SqlInteraction();
            Map<String, Object> whereValues = new LinkedHashMap<>();
            StringBuilder selectSql = new StringBuilder("SELECT * FROM " + convertSequenceToTable.getTableName() + " WHERE ");

            for (LeafParameter leaf : pathParams) {
                String columnName = convertSequenceToTable.getColumnNameByParameter(leaf);
                if (columnName != null) {
                    selectSql.append(columnName).append(" = ? AND ");
                    whereValues.put(columnName, leaf.getValue());
                }
            }

            // If no WHERE values could be mapped (path params not in table), we can't check/update.
            if (whereValues.isEmpty()) {
                log.warn("Path parameters found but no column mapping found for operation {}. Skipping SQL execution.", op.getEndpoint());
                return selectInteraction;
            }

            selectSql.setLength(selectSql.length() - 5);
            selectSql.append(";");

            try {
                databaseHelper.executeGet(selectSql.toString(), whereValues, selectInteraction);
            } catch (Exception e) {
                log.error("Failed to execute existence check for POST operation {}", op.getEndpoint(), e);
                return selectInteraction;
            }

            // If not exists, return directly
            if (selectInteraction.getQueryResults() == null || selectInteraction.getQueryResults().isEmpty()) {
                log.info("Resource not found in DB for POST operation {}. Skipping update.", op.getEndpoint());
                return selectInteraction;
            }

            // If exists, perform UPDATE
            Map<String, Object> setValues = new LinkedHashMap<>();
            StringBuilder updateSql = new StringBuilder("UPDATE " + convertSequenceToTable.getTableName() + " SET ");

            // Set values from non-path leaves
            for (LeafParameter leaf : leaves) {
                if (leaf.getLocation() != ParameterLocation.PATH && !(leaf.getParent() instanceof ArrayParameter)) {
                    String columnName = convertSequenceToTable.getColumnNameByParameter(leaf);
                    if (columnName != null) {
                        updateSql.append(columnName).append(" = ?, ");
                        setValues.put(columnName, leaf.getValue());
                    }
                }
            }

            // Set values from arrays
            for (ArrayParameter array : arrays) {
                if (array.getElements().isEmpty()) {
                    continue;
                }
                String columnName = convertSequenceToTable.getColumnNameByParameter(array);
                if (columnName != null) {
                    updateSql.append(columnName).append(" = ?, ");
                    List<Object> arrayValues = ParameterToJsonUtil.getArrayValues(array);
                    setValues.put(columnName, ParameterToJsonUtil.arrayToJsonString(arrayValues));
                }
            }

            if (setValues.isEmpty()) {
                log.warn("No values to update for POST operation {}. Skipping.", op.getEndpoint());
                return selectInteraction;
            }

            updateSql.setLength(updateSql.length() - 2);
            updateSql.append(" WHERE ");
            for (String col : whereValues.keySet()) {
                updateSql.append(col).append(" = ? AND ");
            }
            updateSql.setLength(updateSql.length() - 5);
            updateSql.append(";");

            SqlInteraction updateInteraction = new SqlInteraction();
            try {
                databaseHelper.executePut(updateSql.toString(), setValues, whereValues, updateInteraction);
            } catch (Exception e) {
                log.error("Failed to generate SQL interaction for {} operation {}: {}", op.getMethod(), op.getEndpoint(), e.getMessage());
            }
            return updateInteraction;

        } else {
            // Original INSERT logic for operations without path parameters
            Map<String, Object> columnValues = new LinkedHashMap<>();
            Collection<LeafParameter> leavesForInsert = op.getLeaves();
            Collection<ArrayParameter> arraysForInsert = op.getArrays();

            StringBuilder insertTableSQL = new StringBuilder("INSERT INTO "  + convertSequenceToTable.getTableName () + " (");
            for (LeafParameter leaf : leavesForInsert) {
                if (!(leaf.getParent() instanceof ArrayParameter)) {
                    String columnName = convertSequenceToTable.getColumnNameByParameter(leaf);
                    if (columnName != null) {
                        insertTableSQL.append(columnName).append(", ");
                        columnValues.put(columnName, leaf.getValue());
                    }
                }
            }
            //Array转换成JSON类型 object是直接拆开了
            for (ArrayParameter array : arraysForInsert) {
                if (array.getElements().isEmpty()) {
                    continue;
                }
                String columnName = convertSequenceToTable.getColumnNameByParameter(array);
                if (columnName != null) {
                    insertTableSQL.append(columnName).append(", ");
                    List<Object> arrayValues = ParameterToJsonUtil.getArrayValues(array);
                    columnValues.put(columnName, ParameterToJsonUtil.arrayToJsonString(arrayValues));
                }
            }
            if (columnValues.isEmpty()) {
                log.error("Skipping INSERT generation: No parameters found for operation {}", op.getEndpoint());
                throw new RuntimeException("Dangerous Operation: INSERT without columnValues is not allowed for " + op.getEndpoint());
            }

            insertTableSQL.setLength(insertTableSQL.length() - 2);
            insertTableSQL.append(") VALUES (");
            for (int i = 0; i < columnValues.size(); i++) {
                insertTableSQL.append("?, ");
            }
            insertTableSQL.setLength(insertTableSQL.length() - 2);
            insertTableSQL.append(");");
            SqlInteraction sqlInteraction = new SqlInteraction();
            try {
                databaseHelper.executePost(insertTableSQL.toString(), columnValues, sqlInteraction);
            } catch (RuntimeException e) {
                log.error("Failed to generate SQL interaction for {} operation {}: {}",op.getMethod(), op.getEndpoint(), e.getMessage());
            }
            return sqlInteraction;
        }
    }
}
