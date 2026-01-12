package io.resttestgen.implementation.sql;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

public class SqlInteraction {
    //数据库操作的实体，需要记录返回的值，具体那一列，执行成功还是失败等
    /**
     * 内部枚举，用于明确表示操作的状态。
     * 比起 boolean，枚举更清晰，易于扩展（例如：PENDING, PARTIAL_SUCCESS 等）。
     */
    public enum InteractionStatus {
        SUCCESS,
        FAILED,
        PENDING
    }

    /**
     * 内部枚举，用于标识这是哪种类型的 RESTful 操作。
     * 这对于后续处理（例如，决定返回 201 还是 200）很有帮助。
     */
    public enum OperationType {
        CREATE, // 对应 POST (INSERT)
        GET,   // 对应 GET (SELECT)
        UPDATE, // 对应 PUT/PATCH (UPDATE)
        DELETE  // 对应 DELETE (DELETE)
    }

    // --- 1. 操作上下文 (Context) ---

    /**
     * 标识此次操作的类型（CREATE, READ, UPDATE, DELETE）。
     */
    private OperationType operationType;

    /**
     * 此次操作实际执行的 SQL 语句（带 '?' 占位符）。
     * 用于日志记录和调试。
     */
    private String executedSql;

    // --- 2. 执行结果状态 (Outcome) ---

    /**
     * 标记此次操作是成功还是失败。
     */
    private InteractionStatus status;

    /**
     * 如果操作失败 (status == FAILED)，这里应包含可读的错误信息。
     * 例如："违反了唯一约束：email 必须是唯一的"。
     */
    private String errorMessage;

    /**
     * 如果操作失败，这里可以存储捕获到的原始 SQLException。
     * 这对于深度调试和日志记录非常有用。
     */
    private transient SQLException exception;

    // --- 3. 结果数据 (Data) ---

    /**
     * 对于 INSERT, UPDATE, DELETE 操作：
     * 记录受此SQL影响的数据库行数。
     * 对于 SELECT 操作，这可以记录结果集的行数。
     */
    private int rowsAffected;

    /**
     * 专用于 READ (SELECT) 和 INSERT 操作：
     * 存储查询返回的结果集。
     * 结构: List 代表多行，Map<String, Object> 代表一行（Key=列名, Value=值）。
     */
    private List<Map<String, Object>> queryResults;

    /**
     * 专用于所有操作，记录输入到数据库的列和对于的值
     * 满足您 "记录返回的值，具体那一列" 的需求。
     *
     * 1.  对于 INSERT (例如使用 RETURNING id, name):
     * Map 会包含 {"id": 123, "name": "newName"}
     * 2.  对于 UPDATE (例如使用 RETURNING email):
     * Map 会包含 {"email": "new.email@example.com"}
     *
     * 这比单个值更灵活，因为它允许返回多列。
     */
    private Map<String, Object> columnValues;

    private Map<String, Object> setValues;

    private Map<String, Object> whereValues;

    /**
     * 默认构造函数，创建一个空的 SqlInteraction 对象。
     */
    public SqlInteraction() {
        this.status = InteractionStatus.PENDING;
        this.operationType = null;
        this.executedSql = null;
        this.rowsAffected = 0;
        this.queryResults = null;
        this.columnValues = null;
        this.errorMessage = null;
        this.exception = null;
        this.setValues = null;
        this.whereValues = null;
    }

    /**
     * 构造一个失败的 Interaction
     */
    public SqlInteraction(OperationType operationType, String executedSql, String errorMessage, SQLException exception) {
        this.status = InteractionStatus.FAILED;
        this.operationType = operationType;
        this.executedSql = executedSql;
        this.errorMessage = errorMessage;
        this.exception = exception;
        this.rowsAffected = 0;
        this.queryResults = null;
        this.columnValues = null;
        this.setValues = null;
        this.whereValues = null;
    }


    // --- Getters (只提供 Getters，使其成为不可变对象) ---

    public InteractionStatus getStatus() {
        return status;
    }

    public boolean isSuccess() {
        return status == InteractionStatus.SUCCESS;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public String getExecutedSql() {
        return executedSql;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public SQLException getException() {
        return exception;
    }

    public int getRowsAffected() {
        return rowsAffected;
    }

    public Map<String, Object> getColumnValues() {
        return columnValues;
    }

    public Map<String, Object> getSetValues() {
        return setValues;
    }

    public Map<String, Object> getWhereValues() {
        return whereValues;
    }

    public List<Map<String, Object>> getQueryResults() {
        return queryResults;
    }

    public Map<String, Object> getReturnedValues() {
        return columnValues;
    }

    public void setOperationType(OperationType operationType) {
        this.operationType = operationType;
    }

    public void setExecutedSql(String executedSql) {
        this.executedSql = executedSql;
    }

    public void setStatus(InteractionStatus status) {
        this.status = status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setException(SQLException exception) {
        this.exception = exception;
    }

    public void setRowsAffected(int rowsAffected) {
        this.rowsAffected = rowsAffected;
    }

    public void setQueryResults(List<Map<String, Object>> queryResults) {
        this.queryResults = queryResults;
    }

    public void setSetValues(Map<String, Object> setValues) {
        this.setValues = setValues;
    }

    public void setWhereValues(Map<String, Object> whereValues) {
        this.whereValues = whereValues;
    }

    public void setColumnValues(Map<String, Object> columnValues) {
        this.columnValues = columnValues;
    }

    /**
     * 辅助方法：快速获取您关心的“那一列”的值。
     * @param columnName 您在 RETURNING 中指定的列名
     * @return 返回的值，如果不存在则为 null
     */
    public Object getReturnedValue(String columnName) {
        if (this.columnValues != null) {
            return this.columnValues.get(columnName);
        }
        return null;
    }
}
