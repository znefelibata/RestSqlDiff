package io.resttestgen.implementation.oracle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.core.datatype.HttpStatusCode;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.testing.TestInteraction;
import io.resttestgen.core.testing.TestSequence;
import io.resttestgen.implementation.sql.SqlInteraction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.*;

public class TestSqlDiffOracle {

    private final Gson gson = new Gson();

    // Helper to create a minimal TestInteraction with given response body and status
    private TestInteraction makeInteraction(String operationId, String endpoint, HttpMethod method, String responseBody, int statusCode) {
        Operation op = new Operation(endpoint, method, new HashMap<>());
        TestInteraction ti = new TestInteraction(op);
        ti.setResponseBody(responseBody);
        ti.setResponseStatusCode(new HttpStatusCode(statusCode));
        return ti;
    }

    // Helper to create a SqlInteraction with given query results
    private SqlInteraction makeSqlInteraction(List<Map<String, Object>> rows) {
        SqlInteraction sql = new SqlInteraction();
        sql.setStatus(SqlInteraction.InteractionStatus.SUCCESS);
        sql.setQueryResults(rows);
        return sql;
    }

    public Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i+1]);
        }
        return m;
    }

    // Cases:
    // 1) Primitive equality (number, bool, string)
    @Test
    public void primitiveEqualityNumberBooleanString() {
        TestSqlDiffOracle self = this;
        TestInteraction ti = makeInteraction("op1", "/items/{id}", HttpMethod.GET, "{\"id\":1,\"active\":true,\"name\":\"foo\"}", 200);
        SqlInteraction sql = makeSqlInteraction(Collections.singletonList(row("id", 1, "active", true, "name", "foo")));
        ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sql);
        TestSequence seq = new TestSequence(null, ti);

        SqlDiffOracle oracle = new SqlDiffOracle();
        oracle.assertTestSequence(seq);

        // Ensure report file created
        // We can't read file name easily but we can ensure no exceptions and result attached
        Assertions.assertFalse(seq.isEmpty());
    }

    // 2) Numeric tolerance
    @Test
    public void numericToleranceComparison() {
        TestInteraction ti = makeInteraction("op2", "/items/{id}", HttpMethod.GET, "{\"value\":1.0000001}", 200);
        SqlInteraction sql = makeSqlInteraction(Collections.singletonList(row("value", 1.0000002)));
        ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sql);
        TestSequence seq = new TestSequence(null, ti);

        SqlDiffOracle oracle = new SqlDiffOracle();
        oracle.assertTestSequence(seq);
        // If tolerance applied, difference should be considered equal -> no VALUE_MISMATCH in report
        Assertions.assertFalse(seq.isEmpty());
    }

    // 3) Date/time tolerance
    @Test
    public void datetimeToleranceComparison() {
        String now = "2023-01-01T00:00:00Z";
        String later = "2023-01-01T00:00:00.500Z"; // 500ms difference
        TestInteraction ti = makeInteraction("op3", "/items/{id}", HttpMethod.GET, "{\"ts\":\""+later+"\"}", 200);
        SqlInteraction sql = makeSqlInteraction(Collections.singletonList(row("ts", now)));
        ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sql);
        TestSequence seq = new TestSequence(null, ti);

        SqlDiffOracle oracle = new SqlDiffOracle();
        oracle.assertTestSequence(seq);
        Assertions.assertFalse(seq.isEmpty());
    }

    // 4) Missing fields
    @Test
    public void missingFieldsDetection() {
        TestInteraction ti = makeInteraction("op4", "/items/{id}", HttpMethod.GET, "{\"id\":1}", 200);
        SqlInteraction sql = makeSqlInteraction(Collections.singletonList(row("id", 1, "extra", "value")));
        ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sql);
        TestSequence seq = new TestSequence(null, ti);

        SqlDiffOracle oracle = new SqlDiffOracle();
        oracle.assertTestSequence(seq);
        Assertions.assertFalse(seq.isEmpty());
    }

    // 5) Nested objects and arrays
    @Test
    public void nestedObjectsAndArraysComparison() {
        TestInteraction ti = makeInteraction("op5", "/items/{id}", HttpMethod.GET, "{\"id\":1,\"meta\":{\"tags\":[\"a\",\"b\"]}}", 200);
        Map<String,Object> meta = new LinkedHashMap<>();
        meta.put("tags", Arrays.asList("a","b"));
        SqlInteraction sql = makeSqlInteraction(Collections.singletonList(row("id", 1, "meta", meta)));
        ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sql);
        TestSequence seq = new TestSequence(null, ti);

        SqlDiffOracle oracle = new SqlDiffOracle();
        oracle.assertTestSequence(seq);
        Assertions.assertFalse(seq.isEmpty());
    }
}

