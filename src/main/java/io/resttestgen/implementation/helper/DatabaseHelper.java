package io.resttestgen.implementation.helper;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.implementation.sql.SqlInteraction;

import java.io.InputStream;
import java.sql.*;
import java.util.*;

public class DatabaseHelper {

    private static DatabaseHelper instance = null;
    private final HikariDataSource dataSource;
    private final String tableName = "api_test_data_" + Environment.getInstance().getApiUnderTest().getName();

    private DatabaseHelper() {
        try (InputStream in = DatabaseHelper.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (in == null) {
                throw new RuntimeException("Failed to load SQL schema file.");
            }
            Properties props = new Properties();
            props.load(in);
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(props.getProperty("url"));
            config.setUsername(props.getProperty("user"));
            config.setPassword(props.getProperty("password"));
            config.setDriverClassName(props.getProperty("driver"));
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to load database schema.", e);

        }
    }
    public static DatabaseHelper getInstance() {
        if (instance == null) {
            instance = new DatabaseHelper();
        }
        return  instance;
    }
    public HikariDataSource getDataSource() {
        return dataSource;
    }
    public static void closePool() {
        if (instance != null && instance.dataSource != null && !instance.dataSource.isClosed()) {
            instance.dataSource.close();
        }
    }

    public int executeCreate(String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException(e + " Failed to execute SQL: " + sql);
        }
    }

    public void executePost(String schemaSql, Map<String, Object> columnValues, SqlInteraction sqlInteraction) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(schemaSql, Statement.RETURN_GENERATED_KEYS)) {
            int index = 1;
            if (columnValues != null) {
                for (Object val : columnValues.values()) {
                    pstmt.setObject(index++, val);
                }
            }
            sqlInteraction.setColumnValues(columnValues);
            sqlInteraction.setExecutedSql(pstmt.toString());
            sqlInteraction.setOperationType( SqlInteraction.OperationType.CREATE);
            int rowsAffected = pstmt.executeUpdate();
            sqlInteraction.setRowsAffected(rowsAffected);
            //获取Insert后返回的主键值，插入的结果
            Object newId = null;
            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    newId = generatedKeys.getObject(1);
                }
            }
            if (newId != null) {
                String selectSql = "SELECT * FROM " + tableName + " WHERE id = ?";
                try (PreparedStatement selectStmt = connection.prepareStatement(selectSql)) {
                    selectStmt.setObject(1, newId);
                    try (ResultSet rs = selectStmt.executeQuery()) {
                        List<Map<String, Object>> allReturnedRows = new ArrayList<>();
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        while (rs.next()) {
                            Map<String, Object> fullRow = new HashMap<>();
                            for (int i = 1; i <= columnCount; i++) {
                                String columnName = metaData.getColumnName(i);
                                Object value = rs.getObject(i);
                                fullRow.put(columnName, value);
                            }
                            // 每处理完一行，就把它加入到 List 中
                            allReturnedRows.add(fullRow);
                        }
                        sqlInteraction.setQueryResults(allReturnedRows);
                    }
                    sqlInteraction.setStatus(SqlInteraction.InteractionStatus.SUCCESS);
                }
            } else {
                sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            }
        } catch (SQLException e) {
            sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            sqlInteraction.setErrorMessage( e.getMessage());
            throw new RuntimeException("Failed to generate SQL schema.", e);
        }
    }

    public void executePut(String schemaSql, Map<String, Object> setValues, Map<String, Object> whereValues, SqlInteraction sqlInteraction) {
        // Implementation for PUT method execution
        try (Connection connection = dataSource.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(schemaSql)) {
            int index = 1;
            if (setValues != null) {
                for (Object val : setValues.values()) {
                    pstmt.setObject(index++, val);
                }
            }
            sqlInteraction.setSetValues(setValues);
            if (whereValues != null) {
                for (Object val : whereValues.values()) {
                    pstmt.setObject(index++, val);
                }
            }
            sqlInteraction.setWhereValues(whereValues);
            sqlInteraction.setExecutedSql(pstmt.toString());
            sqlInteraction.setOperationType( SqlInteraction.OperationType.UPDATE);
            int rowsAffected = pstmt.executeUpdate();
            sqlInteraction.setRowsAffected(rowsAffected);
            if (rowsAffected > 0) {
                sqlInteraction.setStatus(SqlInteraction.InteractionStatus.SUCCESS);
            } else {
                sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            }
        } catch (SQLException e) {
            sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            sqlInteraction.setErrorMessage( e.getMessage());
            throw new RuntimeException("Failed to execute PUT SQL.", e);
        }
    }

    public void executeDelete(String schemaSql, Map<String, Object> whereValues, SqlInteraction sqlInteraction) {
        // Implementation for DELETE method execution
        try (Connection connection = dataSource.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(schemaSql)) {
            int index = 1;
            // 注意：这里的 whereValues 必须是经过筛选、剔除 null 之后的 Map
            if (whereValues != null) {
                for (Object val : whereValues.values()) {
                    pstmt.setObject(index++, val);
                }
            }
            // 记录传递的参数，方便调试
            sqlInteraction.setWhereValues(whereValues);
            sqlInteraction.setExecutedSql(pstmt.toString());
            sqlInteraction.setOperationType(SqlInteraction.OperationType.DELETE);
            int rowsAffected = pstmt.executeUpdate();
            sqlInteraction.setRowsAffected(rowsAffected);
            if (rowsAffected > 0) {
                sqlInteraction.setStatus(SqlInteraction.InteractionStatus.SUCCESS);
            } else {
                sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            }
        } catch (SQLException e) {
            sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            sqlInteraction.setException(e);
            throw new RuntimeException("Failed to execute DELETE SQL.", e);
        }
    }

    public void executeGet(String schemaSql, Map<String, Object> whereValues, SqlInteraction sqlInteraction) {
        // Implementation for GET method execution
        try (Connection connection = dataSource.getConnection();
             PreparedStatement pstmt = connection.prepareStatement(schemaSql)) {
            int index = 1;
            // 注意：这里的 whereValues 必须是经过筛选、剔除 null 之后的 Map
            if (whereValues != null) {
                for (Object val : whereValues.values()) {
                    pstmt.setObject(index++, val);
                }
            }
            // 记录完整 SQL 用于调试
            sqlInteraction.setWhereValues(whereValues);
            sqlInteraction.setExecutedSql(pstmt.toString());
            sqlInteraction.setOperationType(SqlInteraction.OperationType.GET);
            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                List<Map<String, Object>> queryResults = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new HashMap<>();
                    // 遍历当前行的每一列
                    for (int i = 1; i <= columnCount; i++) {
                        String columnName = metaData.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        row.put(columnName, value);
                    }
                    queryResults.add(row);
                }
                if (queryResults.isEmpty()) {
                    sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
                    sqlInteraction.setRowsAffected(0);
                    sqlInteraction.setQueryResults(Collections.emptyList());
                } else {
                    sqlInteraction.setStatus(SqlInteraction.InteractionStatus.SUCCESS);
                    sqlInteraction.setQueryResults(queryResults);
                    sqlInteraction.setRowsAffected(queryResults.size());
                }
            }
        } catch (SQLException e) {
            sqlInteraction.setStatus(SqlInteraction.InteractionStatus.FAILED);
            sqlInteraction.setException(e);
            throw new RuntimeException("Failed to execute GET SQL.", e);
        }
    }
}
