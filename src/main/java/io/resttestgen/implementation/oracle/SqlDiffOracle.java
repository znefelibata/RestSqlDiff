package io.resttestgen.implementation.oracle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import io.resttestgen.core.datatype.HttpStatusCode;
import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.structured.StructuredParameter;
import io.resttestgen.core.testing.Oracle;
import io.resttestgen.core.testing.TestInteraction;
import io.resttestgen.core.testing.TestResult;
import io.resttestgen.core.testing.TestSequence;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * A differential testing oracle that compares the outcome of the REST API execution
 * with the outcome of a simulated SQL execution on a shadow database.
 *
 * <h2>Mapping Strategy:</h2>
 * <p>The oracle handles two special cases in the API-to-Database mapping:</p>
 * <ul>
 *   <li><b>Object fields:</b> Nested objects are flattened in the database. For example,
 *       {@code {"user": {"name": "John", "age": 30}}} is stored as separate columns
 *       {@code user_name} and {@code user_age}.</li>
 *   <li><b>Array fields:</b> Arrays are stored as JSON strings in the database. For example,
 *       {@code {"tags": ["java", "rest"]}} is stored as a JSON column containing
 *       the string {@code ["java", "rest"]}.</li>
 * </ul>
 *
 * <h2>Comparison Process:</h2>
 * <p>The oracle recursively flattens the API response JSON and compares each field with
 * the corresponding database column:</p>
 * <ol>
 *   <li>For primitive fields, direct value comparison is performed.</li>
 *   <li>For nested objects, the object is recursively flattened and each leaf field
 *       is mapped to its corresponding database column using the ConvertSequenceToTable mapping.</li>
 *   <li>For arrays, the database JSON string is parsed and compared with the API array.</li>
 * </ol>
 *
 * <h2>Field Name Mapping:</h2>
 * <p>The mapping from API field names to database column names is based on the <b>leaf field name</b>,
 * not the full path. For example, in {@code {"user": {"name": "John"}}}, the field name is {@code "name"},
 * not {@code "user.name"} or {@code "user_name"}. The clustering process in ConvertSequenceToTable
 * handles disambiguation and assigns unique canonical names (like {@code user_name}) as database columns.</p>
 *
 * @see ConvertSequenceToTable for the API-to-Database mapping implementation
 */
public class SqlDiffOracle extends Oracle {

    public static final String SQL_INTERACTION_TAG = "SQL_INTERACTION";
    private static final Gson gson = new Gson().newBuilder().setPrettyPrinting().create();
    private static final double DOUBLE_TOLERANCE = 1E-6;
    private static final long DATE_TOLERANCE_MS = 1000; // 1 second tolerance for timestamps

    // Converter to map API parameter names to database column names
    private ConvertSequenceToTable tableConverter;
    // Base directory for reports, settable by the strategy
    private Path baseReportDir;

    public SqlDiffOracle() {
        // Default constructor
    }

    public   SqlDiffOracle(ConvertSequenceToTable tableConverter) {
        this.tableConverter = tableConverter;
    }

    public void setBaseReportDir(Path baseReportDir) {
        this.baseReportDir = baseReportDir;
    }

    @Override
    public TestResult assertTestSequence(TestSequence testSequence) {
        TestResult testResult = new TestResult();

        if (!testSequence.isExecuted()) {
            return testResult.setError("One or more interaction in the sequence have not been executed.");
        }

        // Prepare report container
        List<Map<String, Object>> interactionReports = new ArrayList<>();

        for (TestInteraction testInteraction : testSequence) {
            // Retrieve the SqlInteraction attached to the TestInteraction
            Object tag = testInteraction.getTag(SQL_INTERACTION_TAG);

            if (!(tag instanceof SqlInteraction)) {
                // If no SQL interaction is present, we cannot perform differential testing for this interaction
                continue;
            }

            SqlInteraction sqlInteraction = (SqlInteraction) tag;
            HttpStatusCode statusCode = testInteraction.getResponseStatusCode();

            String diffStatus = "UNKNOWN";
            String diffMessage = "";

            // 1. Compare Execution Status
            if (sqlInteraction.isSuccess()) {
                // Case: SQL Success
                if (statusCode.isSuccessful()) {
                    // SQL Success + API Success = PASS
                    diffStatus = "PASS";
                    diffMessage = "Both SQL simulation and API execution succeeded.";
                    testResult.setPass(diffMessage);
                } else if (statusCode.isClientError()) {
                    // SQL Success + API Client Error = POTENTIAL BUG (False Positive possible)
                    diffStatus = "FAIL";
                    diffMessage = "Difference detected: SQL simulation succeeded, but API returned Client Error (" + statusCode + ").";
                    testResult.setFail(diffMessage);
                } else if (statusCode.isServerError()) {
                    // SQL Success + API Server Error = FAIL
                    diffStatus = "FAIL";
                    diffMessage = "Difference detected: SQL simulation succeeded, but API returned Server Error (" + statusCode + ").";
                    testResult.setFail(diffMessage);
                }
            } else {
                // Case: SQL Failed (e.g., constraint violation)
                if (statusCode.isSuccessful()) {
                    // SQL Fail + API Success = FAIL (Serious Bug)
                    // The API accepted data that violates DB constraints (or the shadow DB schema is stricter)
                    diffStatus = "FAIL";
                    diffMessage = "Difference detected: SQL simulation failed (" + sqlInteraction.getErrorMessage() + "), but API succeeded (" + statusCode + ").";
                    testResult.setFail(diffMessage);
                } else if (statusCode.isClientError()) {
                    // SQL Fail + API Client Error = PASS
                    // Both rejected the request (likely for the same reason)
                    diffStatus = "PASS";
                    diffMessage = "Both SQL simulation and API execution rejected the request.";
                    testResult.setPass(diffMessage);
                } else if (statusCode.isServerError()) {
                    // SQL Fail + API Server Error = FAIL (Graceful handling expected)
                    diffStatus = "FAIL";
                    diffMessage = "SQL simulation failed, and API crashed with Server Error (" + statusCode + "). Expected Client Error.";
                    testResult.setFail(diffMessage);
                }
            }

            // Field-level comparison: only when SQL returned query results and API returned a body
            Map<String, Object> singleReport = new LinkedHashMap<>();
            // Use operationId if available, otherwise fallback to method + endpoint for identification
            String operationIdentifier = testInteraction.getFuzzedOperation().getOperationId();
            if (operationIdentifier == null || operationIdentifier.isEmpty()) {
                operationIdentifier = testInteraction.getFuzzedOperation().getMethod().toString() + "_" +
                        testInteraction.getFuzzedOperation().getEndpoint();
            }
            singleReport.put("operation", operationIdentifier);
            singleReport.put("endpoint", testInteraction.getFuzzedOperation().getEndpoint());
            singleReport.put("method", testInteraction.getFuzzedOperation().getMethod().toString());
            singleReport.put("apiStatus", statusCode.getCode());
            singleReport.put("sqlStatus", sqlInteraction.getStatus().toString());

            // Add detailed info for logging
            singleReport.put("diffStatus", diffStatus);
            singleReport.put("diffMessage", diffMessage);
            singleReport.put("executedSql", sqlInteraction.getExecutedSql());
            singleReport.put("sqlErrorMessage", sqlInteraction.getErrorMessage());
            // Store request details if available (assuming TestInteraction has them, otherwise might be partial)
            // Note: FuzzedOperation has parameter values.
            // For now, we rely on extracting this in writeReportToFile or here.
            // Let's store the whole FuzzedOperation object string or similar for reference in JSON,
            // but for the log file, we will format it nicely in writeReportToFile using the testInteraction object if possible,
            // but testInteraction is not passed to writeReportToFile explicitly for each item, only the list.
            // So we MUST put everything needed for the log file into 'singleReport'.

            // Format Request Headers
            singleReport.put("requestHeaders", testInteraction.getRequestHeaders().toString());
            // Format Response Headers
            singleReport.put("responseHeaders", testInteraction.getResponseHeaders().toString());
            // Store Request Body
            singleReport.put("requestBody", testInteraction.getRequestBody());

            // Extract and store all API parameters (path, query, request body)
            Map<String, Object> apiParameters = extractApiParameters(testInteraction.getFuzzedOperation());
            singleReport.put("apiParameters", apiParameters);

            List<Map<String, Object>> queryResults = sqlInteraction.getQueryResults();
            String responseBody = testInteraction.getResponseBody();

            // Store raw results in the report map for file output
            singleReport.put("sqlQueryResults", queryResults);
            singleReport.put("apiResponseBody", responseBody);

            // Parse responseBody to JsonElement if possible
            JsonElement responseJson = null;
            if (responseBody != null && responseBody.trim().length() > 0) {
                try {
                    responseJson = JsonParser.parseString(responseBody);
                } catch (JsonSyntaxException e) {
                    // Not a JSON body - store raw
                    singleReport.put("responseBodyRaw", responseBody);
                }
            }

            List<Map<String, Object>> comparisonResults = new ArrayList<>();

            if (queryResults != null && !queryResults.isEmpty() && responseJson != null) {
                // If response is an array, compare with rows; if object, compare to first row
                if (responseJson.isJsonArray()) {
                    int rows = Math.max(queryResults.size(), responseJson.getAsJsonArray().size());
                    for (int i = 0; i < rows; i++) {
                        Map<String, Object> rowReport = new LinkedHashMap<>();
                        Map<String, Object> sqlRow = i < queryResults.size() ? queryResults.get(i) : null;
                        JsonElement jsonElem = i < responseJson.getAsJsonArray().size() ? responseJson.getAsJsonArray().get(i) : null;
                        rowReport.put("rowIndex", i);
                        rowReport.put("sqlRowPresent", sqlRow != null);
                        rowReport.put("apiElemPresent", jsonElem != null);
                        rowReport.put("fieldDifferences", compareRowAndJson(sqlRow, jsonElem, testInteraction.getFuzzedOperation()));
                        comparisonResults.add(rowReport);
                    }
                } else if (responseJson.isJsonObject()) {
                    Map<String, Object> rowReport = new LinkedHashMap<>();
                    Map<String, Object> sqlRow = queryResults.size() > 0 ? queryResults.get(0) : null;
                    rowReport.put("rowIndex", 0);
                    rowReport.put("sqlRowPresent", sqlRow != null);
                    rowReport.put("apiElemPresent", true);
                    rowReport.put("fieldDifferences", compareRowAndJson(sqlRow, responseJson, testInteraction.getFuzzedOperation()));
                    comparisonResults.add(rowReport);
                } else {
                    singleReport.put("responseNotObjectOrArray", true);
                }
            } else {
                // No query results or no response json - record what's missing
                singleReport.put("queryResultsPresent", queryResults != null && !queryResults.isEmpty());
                singleReport.put("responseJsonPresent", responseJson != null);
            }

            singleReport.put("comparisons", comparisonResults);
            interactionReports.add(singleReport);
        }

        // Write interactionReports to disk for later review
        try {
            writeReportToFile(testSequence, interactionReports);
        } catch (IOException e) {
            // If writing fails, still attach test result but notify
            testResult.setUnknown("Comparison computed but failed to write report: " + e.getMessage());
        }

        testSequence.addTestResult(this, testResult);
        return testResult;
    }

    private List<Map<String, Object>> compareRowAndJson(Map<String, Object> sqlRow, JsonElement jsonElem, io.resttestgen.core.openapi.Operation operation) {
        List<Map<String, Object>> differences = new ArrayList<>();
        if (sqlRow == null && (jsonElem == null || jsonElem.isJsonNull())) {
            return differences; // both absent - no diff
        }
        JsonObject jsonObject = null;
        if (jsonElem != null && jsonElem.isJsonObject()) {
            jsonObject = jsonElem.getAsJsonObject();
        }
        // Strategy: Recursively flatten API JSON object and compare with SQL row
        // This handles both:
        // 1. Object fields that are flattened in DB (user.name -> user_name column)
        // 2. Array fields that are stored as JSON strings in DB

        // 1. Flatten and check all API fields against SQL Row
        Set<String> matchedSqlColumns = new HashSet<>();

        if (jsonObject != null) {
            // Recursively flatten the JSON object and compare each field
            flattenAndCompareJsonObject(jsonObject, "", sqlRow, matchedSqlColumns, differences, operation);
        }

        // 2. Check for SQL columns that were NOT matched by any API field (MISSING_IN_API)
        // This is tricky: SQL columns are canonical names. We don't easily know which API field they correspond to if we don't have the reverse map.
        // However, if we assume we only care about API fields that *should* be there, maybe we skip this reverse check
        // OR we simply iterate remaining SQL columns and report them.

        if (sqlRow != null) {
            for (Map.Entry<String, Object> entry : sqlRow.entrySet()) {
                String sqlCol = entry.getKey();
                if (!matchedSqlColumns.contains(sqlCol)) {
                     // This SQL column was not visited via API keys.
                     // It might be an internal column (like ID generated by DB) or a field missing in API response.
                     // We report it as potentially missing in API, but with caution.
                     // For now, let's include it but users can filter "id".
                     if (sqlCol.equalsIgnoreCase("id")) continue; // Skip ID usually

                     Map<String, Object> diff = new LinkedHashMap<>();
                     diff.put("path", "? (mapped to " + sqlCol + ")");
                     diff.put("sqlColumn", sqlCol);
                     diff.put("sqlValue", entry.getValue());
                     diff.put("apiValuePresent", false);
                     diff.put("difference", "MISSING_IN_API");
                     differences.add(diff);
                }
            }
        }

        return differences;
    }

    /**
     * Recursively flatten a JSON object and compare each field with SQL row.
     * This handles nested objects that are flattened in the database.
     * For example: {"user": {"name": "John", "age": 30}} -> user_name, user_age columns
     *
     * Note: The mapping (API field name -> DB column) is based on the leaf field name only,
     * because during parameter flattening, each parameter keeps its original name (not the full path).
     * The clustering process handles disambiguation, and canonical names use the full flattened path.
     *
     * @param jsonObject The JSON object to flatten
     * @param parentPath The parent path for display purposes (e.g., "user.profile" for nested fields)
     * @param sqlRow The SQL row to compare against
     * @param matchedSqlColumns Set to track which SQL columns have been matched
     * @param differences List to accumulate differences
     * @param operation The operation being compared
     */
    private void flattenAndCompareJsonObject(JsonObject jsonObject, String parentPath,
                                             Map<String, Object> sqlRow,
                                             Set<String> matchedSqlColumns,
                                             List<Map<String, Object>> differences,
                                             io.resttestgen.core.openapi.Operation operation) {
        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            String apiKey = entry.getKey();
            // Use dot for display path (human-readable: user.name)
            String displayPath = parentPath.isEmpty() ? apiKey : parentPath + "." + apiKey;
            JsonElement value = entry.getValue();

            // Case 1: Value is a nested object - need to flatten it
            if (value.isJsonObject()) {
                // Recursively flatten nested object
                flattenAndCompareJsonObject(value.getAsJsonObject(), displayPath, sqlRow, matchedSqlColumns, differences, operation);
            }
            // Case 2: Value is an array - stored as JSON string in DB
            else if (value.isJsonArray()) {
                // Arrays are stored as JSON strings in the database
                // Use the leaf field name (apiKey) to find the column mapping
                String dbColumn = apiKey; // default: use the field name
                if (tableConverter != null) {
                    String mapped = tableConverter.getColumnNameByName(apiKey, operation);
                    if (mapped != null) {
                        dbColumn = mapped;
                    }
                }

                if (sqlRow == null || !sqlRow.containsKey(dbColumn)) {
                    // API has array field, SQL does not have column OR row is missing
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("path", displayPath);
                    diff.put("sqlColumn", dbColumn);
                    diff.put("sqlValuePresent", false);
                    diff.put("apiValue", parsedToJava(value));
                    diff.put("difference", "MISSING_IN_SQL");
                    differences.add(diff);
                } else {
                    // SQL has this column, compare values
                    matchedSqlColumns.add(dbColumn);
                    Object sqlValue = sqlRow.get(dbColumn);

                    // SQL value should be a JSON string, parse it and compare
                    if (sqlValue instanceof String) {
                        try {
                            JsonElement parsedSql = JsonParser.parseString((String) sqlValue);
                            compareValueRecursive(displayPath, parsedToJava(parsedSql), value, differences);
                        } catch (JsonSyntaxException e) {
                            // Not valid JSON, compare as-is
                            compareValueRecursive(displayPath, sqlValue, value, differences);
                        }
                    } else {
                        compareValueRecursive(displayPath, sqlValue, value, differences);
                    }
                }
            }
            // Case 3: Value is a primitive - map to DB column
            else {
                // For leaf fields, use the field name (apiKey) to find the DB column name
                // The clustering process handles cases where multiple nested objects have the same field name
                String dbColumn = apiKey; // default: use the field name
                if (tableConverter != null) {
                    String mapped = tableConverter.getColumnNameByName(apiKey, operation);
                    if (mapped != null) {
                        dbColumn = mapped;
                    }
                }

                if (sqlRow == null || !sqlRow.containsKey(dbColumn)) {
                    // API has field, SQL does not have column OR row is missing
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("path", displayPath);
                    diff.put("sqlColumn", dbColumn);
                    diff.put("sqlValuePresent", false);
                    diff.put("apiValue", parsedToJava(value));
                    diff.put("difference", "MISSING_IN_SQL");
                    differences.add(diff);
                } else {
                    // SQL has this column, compare values
                    matchedSqlColumns.add(dbColumn);
                    Object sqlValue = sqlRow.get(dbColumn);
                    // Recursive compare
                    compareValueRecursive(displayPath, sqlValue, value, differences);
                }
            }
        }
    }

    /**
     * Recursively compare a SQL value (which may be a primitive, Map, List, or JSON string) with a JSON element
     * from the API response. Differences are appended to the diffs list with a path awareness.
     */
    private void compareValueRecursive(String path, Object sqlValue, JsonElement apiElem, List<Map<String, Object>> diffs) {
        // Normalize path
        String normalizedPath = path == null ? "" : path;

        // Both null or absent
        if ((sqlValue == null) && (apiElem == null || apiElem.isJsonNull())) {
            return;
        }

        // If SQL value is a Map, recurse into its entries
        if (sqlValue instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) sqlValue;
            JsonObject jsonObject = (apiElem != null && apiElem.isJsonObject()) ? apiElem.getAsJsonObject() : null;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String childPath = normalizedPath + "." + e.getKey();
                JsonElement childApi = jsonObject != null && jsonObject.has(e.getKey()) ? jsonObject.get(e.getKey()) : null;
                compareValueRecursive(childPath, e.getValue(), childApi, diffs);
            }
            // Also detect API fields not present in SQL map
            if (jsonObject != null) {
                for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                    if (!map.containsKey(entry.getKey())) {
                        Map<String, Object> diff = new LinkedHashMap<>();
                        diff.put("path", normalizedPath + "." + entry.getKey());
                        diff.put("sqlValuePresent", false);
                        diff.put("apiValue", parsedToJava(entry.getValue()));
                        diff.put("difference", "MISSING_IN_SQL");
                        diffs.add(diff);
                    }
                }
            }
            return;
        }

        // If SQL value is a List, compare to JSON array
        if (sqlValue instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) sqlValue;
            if (apiElem != null && apiElem.isJsonArray()) {
                int max = Math.max(list.size(), apiElem.getAsJsonArray().size());
                for (int i = 0; i < max; i++) {
                    Object childSql = i < list.size() ? list.get(i) : null;
                    JsonElement childApi = i < apiElem.getAsJsonArray().size() ? apiElem.getAsJsonArray().get(i) : null;
                    compareValueRecursive(normalizedPath + "[" + i + "]", childSql, childApi, diffs);
                }
            } else {
                // API not an array - record mismatch
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("path", normalizedPath);
                diff.put("sqlValue", list);
                diff.put("apiValuePresent", apiElem != null && !apiElem.isJsonNull());
                diff.put("difference", "TYPE_MISMATCH");
                diffs.add(diff);
            }
            return;
        }

        // If SQL value is a String that contains JSON, try to parse and recurse
        if (sqlValue instanceof String) {
            String s = (String) sqlValue;
            try {
                JsonElement parsed = JsonParser.parseString(s);
                if (parsed.isJsonObject() || parsed.isJsonArray()) {
                    compareValueRecursive(normalizedPath, parsedToJava(parsed), apiElem, diffs);
                    return;
                }
            } catch (JsonSyntaxException ignored) {
                // not JSON string
            }
        }

        // At this point treat SQL value as primitive
        Object apiPrimitive = parsedToJava(apiElem);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("path", normalizedPath);
        diff.put("sqlValue", sqlValue);
        diff.put("apiValue", apiPrimitive);
        if (apiElem == null || apiElem.isJsonNull()) {
            diff.put("apiValuePresent", false);
            diff.put("difference", "MISSING_IN_API");
        } else if (!objectsEqual(sqlValue, apiPrimitive)) {
            diff.put("apiValuePresent", true);
            diff.put("difference", "VALUE_MISMATCH");
        } else {
            diff.put("apiValuePresent", true);
            diff.put("difference", "EQUAL");
        }
        diffs.add(diff);
    }

    /**
     * Helper to convert a parsed JsonElement back into Java Map/List/primitive to reuse recursive comparisons when
     * parsing JSON strings from SQL columns. If element is object -> Map<String,Object>, array -> List<Object>, primitive -> corresponding Java.
     */
    private Object parsedToJava(JsonElement elem) {
        if (elem == null || elem.isJsonNull()) return null;
        if (elem.isJsonPrimitive()) {
            if (elem.getAsJsonPrimitive().isNumber()) return elem.getAsNumber();
            if (elem.getAsJsonPrimitive().isBoolean()) return elem.getAsBoolean();
            return elem.getAsString();
        }
        if (elem.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            JsonObject obj = elem.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                map.put(e.getKey(), parsedToJava(e.getValue()));
            }
            return map;
        }
        if (elem.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            elem.getAsJsonArray().forEach(j -> list.add(parsedToJava(j)));
            return list;
        }
        return elem.toString();
    }

    private Object jsonElementToPrimitive(JsonElement elem) {
        return parsedToJava(elem);
    }

    private boolean objectsEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // Normalize numbers: compare as double if both are numbers or parseable as numbers
        Double da = tryParseDouble(a);
        Double db = tryParseDouble(b);
        if (da != null && db != null) {
            return Math.abs(da - db) <= DOUBLE_TOLERANCE;
        }
        // Try date/time parsing
        Instant ia = tryParseInstant(a);
        Instant ib = tryParseInstant(b);
        if (ia != null && ib != null) {
            return Math.abs(ia.toEpochMilli() - ib.toEpochMilli()) <= DATE_TOLERANCE_MS;
        }
        // Boolean coercion
        Boolean ba = tryParseBoolean(a);
        Boolean bb = tryParseBoolean(b);
        if (ba != null && bb != null) {
            return ba.equals(bb);
        }
        // Fallback to string equality
        return a.toString().equals(b.toString());
    }

    private String getPrimitiveDifference(Object sqlValue, Object apiValue) {
        if (sqlValue == null && apiValue == null) return "EQUAL";
        if (apiValue == null) return "MISSING_IN_API";
        // Numbers
        Double da = tryParseDouble(sqlValue);
        Double db = tryParseDouble(apiValue);
        if (da != null && db != null) {
            return Math.abs(da - db) <= DOUBLE_TOLERANCE ? "EQUAL" : "VALUE_MISMATCH";
        }
        // Dates
        Instant ia = tryParseInstant(sqlValue);
        Instant ib = tryParseInstant(apiValue);
        if (ia != null && ib != null) {
            return Math.abs(ia.toEpochMilli() - ib.toEpochMilli()) <= DATE_TOLERANCE_MS ? "EQUAL" : "VALUE_MISMATCH";
        }
        // Booleans
        Boolean ba = tryParseBoolean(sqlValue);
        Boolean bb = tryParseBoolean(apiValue);
        if (ba != null && bb != null) {
            return ba.equals(bb) ? "EQUAL" : "VALUE_MISMATCH";
        }
        // Fallback: string compare
        if (sqlValue.toString().equals(apiValue.toString())) return "EQUAL";
        // Try loose numeric compare (one is string of number)
        if (da != null || db != null) {
            if (da == null || db == null) return "VALUE_MISMATCH";
        }
        return "VALUE_MISMATCH";
    }

    private Double tryParseDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            String s = o.toString().trim();
            if (s.isEmpty()) return null;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    private Instant tryParseInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant) return (Instant) o;
        try {
            String s = o.toString().trim();
            if (s.isEmpty()) return null;
            // Try parse as OffsetDateTime or Instant
            try {
                OffsetDateTime odt = OffsetDateTime.parse(s);
                return odt.toInstant();
            } catch (DateTimeParseException ignored) {
            }
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException ignored) {
            }
            // Could add more flexible formats if needed
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Boolean tryParseBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim().toLowerCase();
        if (s.equals("true") || s.equals("false")) return Boolean.parseBoolean(s);
        return null;
    }

    /**
     * Extract all API parameters (path, query, request body) from the fuzzed operation.
     * Returns a map with three keys: "pathParameters", "queryParameters", "requestBodyParameters".
     * Each key maps to a list of maps containing parameter name and value.
     */
    private Map<String, Object> extractApiParameters(io.resttestgen.core.openapi.Operation operation) {
        Map<String, Object> allParams = new LinkedHashMap<>();

        // 1. Path Parameters
        List<Map<String, Object>> pathParams = new ArrayList<>();
        if (operation.getPathParameters() != null) {
            for (Parameter p : operation.getPathParameters()) {
                Map<String, Object> paramInfo = new LinkedHashMap<>();
                paramInfo.put("name", p.getName().toString());
                paramInfo.put("value", p.getValueAsFormattedString());
                paramInfo.put("type", p.getType() != null ? p.getType().toString() : "unknown");
                pathParams.add(paramInfo);
            }
        }
        allParams.put("pathParameters", pathParams);

        // 2. Query Parameters
        List<Map<String, Object>> queryParams = new ArrayList<>();
        if (operation.getQueryParameters() != null) {
            for (Parameter p : operation.getQueryParameters()) {
                Map<String, Object> paramInfo = new LinkedHashMap<>();
                paramInfo.put("name", p.getName().toString());
                paramInfo.put("value", p.getValueAsFormattedString());
                paramInfo.put("type", p.getType() != null ? p.getType().toString() : "unknown");
                queryParams.add(paramInfo);
            }
        }
        allParams.put("queryParameters", queryParams);

        // 3. Request Body Parameters (flatten structured parameters)
        List<Map<String, Object>> bodyParams = new ArrayList<>();
        StructuredParameter requestBody = operation.getRequestBody();
        if (requestBody != null) {
            extractParametersRecursive(requestBody, "", bodyParams);
        }
        allParams.put("requestBodyParameters", bodyParams);

        return allParams;
    }

    /**
     * Recursively extract parameters from a structured parameter (ObjectParameter or ArrayParameter).
     * Adds each leaf parameter with its full path.
     */
    private void extractParametersRecursive(Parameter param, String parentPath, List<Map<String, Object>> result) {
        String currentPath = parentPath.isEmpty() ? param.getName().toString() : parentPath + "." + param.getName().toString();

        if (param instanceof StructuredParameter) {
            // Recursively process children
            StructuredParameter structured = (StructuredParameter) param;
            for (Parameter child : structured.getChildren()) {
                extractParametersRecursive(child, currentPath, result);
            }
        } else {
            // Leaf parameter
            Map<String, Object> paramInfo = new LinkedHashMap<>();
            paramInfo.put("path", currentPath);
            paramInfo.put("name", param.getName().toString());
            paramInfo.put("value", param.getValueAsFormattedString());
            paramInfo.put("type", param.getType() != null ? param.getType().toString() : "unknown");
            result.add(paramInfo);
        }
    }

    /**
     * Extract SQL column names and values from query results.
     * Returns a list of maps, each containing column name -> value pairs for each row.
     */
    private List<Map<String, Object>> extractSqlColumnsAndValues(List<Map<String, Object>> queryResults) {
        if (queryResults == null || queryResults.isEmpty()) {
            return new ArrayList<>();
        }
        // Return all rows with their column names and values
        return queryResults;
    }

    private void writeReportToFile(TestSequence testSequence, List<Map<String, Object>> interactionReports) throws IOException {
        // Use baseReportDir if set, otherwise fallback to "reports/sql-diff/report_current_time"
        Path reportsDir;
        if (baseReportDir != null) {
            reportsDir = baseReportDir;
        } else {
            long timestamp = System.currentTimeMillis();
            String reportName = "report_" + timestamp;
            reportsDir = Paths.get("reports", "sql-diff", reportName);
        }
        Files.createDirectories(reportsDir);

        // DateTimeFormatter for human-readable timestamps in folder names
        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

        int sequenceIndex = 0; // Index to ensure time-based ordering
        for (Map<String, Object> report : interactionReports) {
            String opId = (String) report.get("operation");
            if (opId == null) opId = "unknown";

            // Extract statuses for folder naming to provide quick visibility
            Object apiStatusObj = report.get("apiStatus");
            String apiStatusStr = apiStatusObj != null ? apiStatusObj.toString() : "NULL";

            Object sqlStatusObj = report.get("sqlStatus");
            String sqlStatusStr = sqlStatusObj != null ? sqlStatusObj.toString() : "NULL";

            // Generate readable timestamp for this operation
            String formattedTimestamp = LocalDateTime.now().format(dtf);

            // Format: {sequenceIndex}_{timestamp}_op_{opId}_API_{statusCode}_SQL_{status}
            // The sequenceIndex ensures lexicographic ordering matches execution order
            String opFolderName = String.format("%03d_%s_op_%s_API_%s_SQL_%s",
                    sequenceIndex, formattedTimestamp, opId, apiStatusStr, sqlStatusStr);

            // Sanitize for file system characters
            opFolderName = opFolderName.replaceAll("[^a-zA-Z0-9._-]", "_");

            Path opDir = reportsDir.resolve(opFolderName);
            Files.createDirectories(opDir);
            Path logsDir = opDir.resolve("logs");
            Files.createDirectories(logsDir);

            sequenceIndex++;

            // 1. Write API Response (single object list for API JSON format)
            List<Map<String, Object>> apiResults = new ArrayList<>();
            Map<String, Object> apiData = new LinkedHashMap<>();
            apiData.put("method", report.get("method"));
            apiData.put("endpoint", report.get("endpoint"));
            apiData.put("status", report.get("apiStatus"));
            // Add all API parameters (path, query, request body)
            apiData.put("apiParameters", report.get("apiParameters"));
            apiData.put("responseBody", report.get("apiResponseBody"));
            // Add request header/body if available in source report
            apiData.put("requestHeaders", report.get("requestHeaders"));
            apiData.put("requestBody", report.get("requestBody"));
            apiResults.add(apiData);
            try (FileWriter fw = new FileWriter(opDir.resolve("api_responses.json").toFile())) {
                gson.toJson(apiResults, fw);
            }

            // 2. Write SQL Results
            List<Map<String, Object>> sqlResults = new ArrayList<>();
            Map<String, Object> sqlData = new LinkedHashMap<>();
            sqlData.put("method", report.get("method"));
            sqlData.put("endpoint", report.get("endpoint"));
            sqlData.put("status", report.get("sqlStatus"));
            sqlData.put("executedSql", report.get("executedSql"));
            // Add detailed SQL column names and values
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queryResults = (List<Map<String, Object>>) report.get("sqlQueryResults");
            if (queryResults != null && !queryResults.isEmpty()) {
                // Extract column names from first row
                Set<String> columnNames = queryResults.get(0).keySet();
                sqlData.put("columnNames", new ArrayList<>(columnNames));
                // Add detailed rows with column name -> value pairs
                List<Map<String, Object>> detailedRows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex < queryResults.size(); rowIndex++) {
                    Map<String, Object> row = queryResults.get(rowIndex);
                    Map<String, Object> detailedRow = new LinkedHashMap<>();
                    detailedRow.put("rowIndex", rowIndex);
                    List<Map<String, Object>> columns = new ArrayList<>();
                    for (Map.Entry<String, Object> col : row.entrySet()) {
                        Map<String, Object> colDetail = new LinkedHashMap<>();
                        colDetail.put("columnName", col.getKey());
                        colDetail.put("value", col.getValue());
                        colDetail.put("valueType", col.getValue() != null ? col.getValue().getClass().getSimpleName() : "null");
                        columns.add(colDetail);
                    }
                    detailedRow.put("columns", columns);
                    detailedRows.add(detailedRow);
                }
                sqlData.put("detailedRows", detailedRows);
            }
            sqlData.put("queryResults", report.get("sqlQueryResults"));
            if (report.get("sqlErrorMessage") != null) {
                sqlData.put("error", report.get("sqlErrorMessage"));
            }
            sqlResults.add(sqlData);
            try (FileWriter fw = new FileWriter(opDir.resolve("sql_results.json").toFile())) {
                gson.toJson(sqlResults, fw);
            }

            // 3. Write Diff Report
            List<Map<String, Object>> diffResults = new ArrayList<>();
            Map<String, Object> diffData = new LinkedHashMap<>();
            diffData.put("method", report.get("method"));
            diffData.put("endpoint", report.get("endpoint"));
            diffData.put("apiStatus", report.get("apiStatus"));
            diffData.put("sqlStatus", report.get("sqlStatus"));
            diffData.put("operation", report.get("operation"));
            // Add API parameters to diff report for reference
            diffData.put("apiParameters", report.get("apiParameters"));
            diffData.put("diffStatus", report.get("diffStatus"));
            diffData.put("diffMessage", report.get("diffMessage"));
            diffData.put("checks", report.get("comparisons"));
            diffResults.add(diffData);
            try (FileWriter fw = new FileWriter(opDir.resolve("diff_report.json").toFile())) {
                gson.toJson(diffResults, fw);
            }

            // 4. Write Log File
            // Sanitize opId for file name (remove illegal characters like /)
            String sanitizedOpId = opId.replaceAll("[^a-zA-Z0-9._-]", "_");
            String logFileName = sanitizedOpId + "_log.txt";
            StringBuilder logContent = new StringBuilder();
            logContent.append("=== Detailed Interaction Log ===\n");
            logContent.append("Operation ID: ").append(opId).append("\n\n");

            logContent.append("--- API Operation ---\n");
            logContent.append("Endpoint: ").append(report.get("endpoint")).append("\n");
            logContent.append("Method: ").append(report.get("method")).append("\n\n");

            // Add API Parameters section
            logContent.append("--- API Parameters ---\n");
            @SuppressWarnings("unchecked")
            Map<String, Object> apiParams = (Map<String, Object>) report.get("apiParameters");
            if (apiParams != null) {
                // Path Parameters
                logContent.append("Path Parameters:\n");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> pathParams = (List<Map<String, Object>>) apiParams.get("pathParameters");
                if (pathParams != null && !pathParams.isEmpty()) {
                    for (Map<String, Object> param : pathParams) {
                        logContent.append("  - ").append(param.get("name"))
                                .append(" = ").append(param.get("value"))
                                .append(" (type: ").append(param.get("type")).append(")\n");
                    }
                } else {
                    logContent.append("  <none>\n");
                }

                // Query Parameters
                logContent.append("Query Parameters:\n");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> queryParams = (List<Map<String, Object>>) apiParams.get("queryParameters");
                if (queryParams != null && !queryParams.isEmpty()) {
                    for (Map<String, Object> param : queryParams) {
                        logContent.append("  - ").append(param.get("name"))
                                .append(" = ").append(param.get("value"))
                                .append(" (type: ").append(param.get("type")).append(")\n");
                    }
                } else {
                    logContent.append("  <none>\n");
                }

                // Request Body Parameters
                logContent.append("Request Body Parameters:\n");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> bodyParams = (List<Map<String, Object>>) apiParams.get("requestBodyParameters");
                if (bodyParams != null && !bodyParams.isEmpty()) {
                    for (Map<String, Object> param : bodyParams) {
                        logContent.append("  - ").append(param.get("path"))
                                .append(" = ").append(param.get("value"))
                                .append(" (type: ").append(param.get("type")).append(")\n");
                    }
                } else {
                    logContent.append("  <none>\n");
                }
            } else {
                logContent.append("  <no parameters extracted>\n");
            }
            logContent.append("\n");

            logContent.append("--- API Request ---\n");
            logContent.append("Headers: ").append(report.get("requestHeaders")).append("\n");
            Object reqBody = report.get("requestBody");
            if (reqBody != null) {
                try {
                    logContent.append("Body:\n").append(gson.toJson(reqBody)).append("\n");
                } catch (Exception e) {
                    logContent.append("Body: ").append(reqBody.toString()).append("\n");
                }
            } else {
                logContent.append("Body: <null>\n");
            }
            logContent.append("\n");

            logContent.append("--- API Response ---\n");
            logContent.append("Status Code: ").append(report.get("apiStatus")).append("\n");
            logContent.append("Headers: ").append(report.get("responseHeaders")).append("\n");
            logContent.append("Body:\n").append(report.get("apiResponseBody")).append("\n\n");

            logContent.append("--- SQL Simulation ---\n");
            logContent.append("Status: ").append(report.get("sqlStatus")).append("\n");
            Object sqlErr = report.get("sqlErrorMessage");
            if (sqlErr != null) {
                logContent.append("Error Message: ").append(sqlErr).append("\n");
            }
            logContent.append("Executed SQL:\n").append(report.get("executedSql")).append("\n");

            // Add detailed SQL columns and values
            logContent.append("SQL Query Results (Column Details):\n");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sqlQueryResults = (List<Map<String, Object>>) report.get("sqlQueryResults");
            if (sqlQueryResults != null && !sqlQueryResults.isEmpty()) {
                // Print column names
                Set<String> colNames = sqlQueryResults.get(0).keySet();
                logContent.append("  Columns: ").append(String.join(", ", colNames)).append("\n");
                // Print each row
                for (int rowIdx = 0; rowIdx < sqlQueryResults.size(); rowIdx++) {
                    Map<String, Object> row = sqlQueryResults.get(rowIdx);
                    logContent.append("  Row ").append(rowIdx).append(":\n");
                    for (Map.Entry<String, Object> col : row.entrySet()) {
                        String valueType = col.getValue() != null ? col.getValue().getClass().getSimpleName() : "null";
                        logContent.append("    - ").append(col.getKey())
                                .append(" = ").append(col.getValue())
                                .append(" (").append(valueType).append(")\n");
                    }
                }
            } else {
                logContent.append("  <no results>\n");
            }
            logContent.append("\n");

            logContent.append("--- Oracle Comparison ---\n");
            logContent.append("Status: ").append(report.get("diffStatus")).append("\n");
            logContent.append("Message: ").append(report.get("diffMessage")).append("\n");
            logContent.append("Field Differences:\n").append(gson.toJson(report.get("comparisons"))).append("\n");

            Files.write(logsDir.resolve(logFileName), logContent.toString().getBytes());
        }
    }
}
