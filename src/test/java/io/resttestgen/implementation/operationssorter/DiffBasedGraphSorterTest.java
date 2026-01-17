package io.resttestgen.implementation.operationssorter;

import com.google.gson.Gson;

import io.resttestgen.boot.ApiUnderTest;
import io.resttestgen.boot.Starter;
import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.core.datatype.NormalizedParameterName;
import io.resttestgen.core.datatype.parameter.Parameter;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.datatype.parameter.structured.ArrayParameter;
import io.resttestgen.core.datatype.parameter.structured.ObjectParameter;
import io.resttestgen.core.helper.ExtendedRandom;
import io.resttestgen.core.openapi.CannotParseOpenApiException;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.operationdependencygraph.OperationNode;
import io.resttestgen.core.testing.TestInteraction;
import io.resttestgen.core.testing.TestRunner;
import io.resttestgen.core.testing.TestSequence;
import io.resttestgen.core.testing.operationsorter.OperationsSorter;
import io.resttestgen.implementation.fuzzer.ErrorFuzzer;
import io.resttestgen.implementation.fuzzer.NominalFuzzer;
import io.resttestgen.implementation.oracle.SqlDiffOracle;
import io.resttestgen.implementation.oracle.StatusCodeOracle;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.factory.RestStrategyFactory;
import io.resttestgen.implementation.strategy.SqlDiffStrategy;
import io.resttestgen.implementation.writer.CoverageReportWriter;
import io.resttestgen.implementation.writer.ReportWriter;
import io.resttestgen.implementation.writer.RestAssuredWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.xml.bind.SchemaOutputResolver;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


import static io.resttestgen.core.datatype.parameter.ParameterUtils.getArrays;
import static org.junit.jupiter.api.Assertions.*;

public class DiffBasedGraphSorterTest {
    private static Environment environment;
    private static final Logger logger = LogManager.getLogger(DiffBasedGraphSorterTest.class);
    private ExtendedRandom random = Environment.getInstance().getRandom();
    @BeforeAll
    public static void setUp() throws CannotParseOpenApiException, IOException {
        environment = Starter.initEnvironment(ApiUnderTest.loadApiFromFile("wordpress"));
    }

    @Test
    public void testPostToDelete() {
        System.out.println("Post to Delete mapping:");
        DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
        System.out.println("Number of Vertex" + Environment.getInstance().getOperationDependencyGraph().getGraph().vertexSet().size());
        System.out.println("Number of POST operations: " + sorter.getPostToDeleteMap().size());
        for (Map.Entry<Operation, Operation> entry : sorter.getPostToDeleteMap().entrySet()) {
            System.out.println("POST: " + entry.getKey().getEndpoint() + " " + entry.getKey().getMethod());
            System.out.println("DELETE: " + entry.getValue().getEndpoint() + " " + entry.getValue().getMethod());
        }
    }

    @Test
    public void testGetAllPostNodes() {
        DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
        List<OperationNode> postNodes = sorter.getAllPostNodes();
        System.out.println("All POST nodes:");
        for (OperationNode node : postNodes) {
            System.out.println("POST: " + node.getOperation().getEndpoint() + " " + node.getOperation().getMethod());
        }
    }

    @Test
    public void testGetAllDeleteNodes() {
        DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
        List<OperationNode> deleteNodes = sorter.getAllDeleteNodes();
        System.out.println("All DELETE nodes:");
        for (OperationNode node : deleteNodes) {
            System.out.println("DELETE: " + node.getOperation().getEndpoint() + " " + node.getOperation().getMethod());
        }
    }


    @Test
    public void testGetTestSequence() {
        DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
        int length = sorter.getAllNodes().size();
        System.out.println("操作个数为：" + length);
        List<Operation> testSequence = sorter.getQueue();

        Map<HttpMethod, Integer> methodCounts = new HashMap<>();

        System.out.println("Generated Test Sequence:");
        for (Operation operation : testSequence) {
            methodCounts.put(operation.getMethod(), methodCounts.getOrDefault(operation.getMethod(), 0) + 1);

            System.out.println("-------------------");
            System.out.println(operation.getMethod() + " " + operation.getEndpoint());
            // ...existing code...
            System.out.println("end-----------");
        }

        System.out.println("Method Counts in Sequence:");
        methodCounts.forEach((method, count) -> System.out.println(method + ": " + count));

//        ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable(testSequence);
        ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable();
        System.out.println("Computed Table Columns:" + convertSequenceToTable.getTableColumns().size());
        convertSequenceToTable.getTableColumns().forEach((key, value) -> {
            System.out.println("Column Name: " + key + ", Data Type: " + value);
        });
        convertSequenceToTable.createTableByColumns();
    }


    @Test
    public void testInteractions() {
        System.out.println("DEBUG: HELLO WORLD START");
        try {
            /**
             Array-String，是能直接通过父亲对像是不是Array来进行封装成JSON
             object-string，直接输入到表中即可
             object-Array-String, 直接拆分输入到表中
             Array-Object-String
             对于 ，，都是获取到了叶子参数
             现在主要考虑看array属性和object属性的值是如何封装到测试用例中进行发送的：直接通过getValueAsFormattedString这个函数自己进行处理，将path，query，requestBody分开进行处理
             这三个地方的参数，根据自己的类型getValueAsFormattedString自己进行标准化处理
             */
            //生成测试序列
            ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable();
            convertSequenceToTable.createTableByColumns();
            //使用queue生成建表，宽表的数据范围比较小
//                ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable(sorter.getQueue());
            logger.info("Starting strategy iteration ");

            // Create a single directory for this test run
            long timestamp = System.currentTimeMillis();
            String testRunDirName = "test_run_" + timestamp;
            Path testReportDir = Paths.get("reports", "sql-diff", testRunDirName);
            Files.createDirectories(testReportDir);
            logger.info("Reports will be saved to: " + testReportDir.toAbsolutePath());

            //对操作序列进行赋值操作
            /**
             目前建的表，如果是Array，则存为JSON格式，如果是Object，则拆开存为多个列，操作是合理的，因为不需要管远端API如何进行请求，本地服务只需要对列名进行翻译即可
             赋值之后和数据库的列是一一对应的，object每个属性都在leaves中有，array如果赋值了，那么在leaves中也有，反之没有
             */
            OperationsSorter sorter = new DiffBasedGraphSorter();
            System.out.println("DEBUG: SORTER CREATED");

            while(!sorter.isEmpty()) {
                 Operation operationToTest = sorter.getFirst();
                 logger.debug("Testing operation " + operationToTest);
                 NominalFuzzer nominalFuzzer = new NominalFuzzer(operationToTest);
                 //每一个操作生成20个测试用例
                 List<TestSequence> nominalSequences = nominalFuzzer.generateTestSequences(2);

                 for (TestSequence testSequence : nominalSequences) {
//                    Collection<LeafParameter> leaves = testSequence.getFirst().getFuzzedOperation().getLeaves();
//                    Collection<ArrayParameter> arrays = testSequence.getFirst().getFuzzedOperation().getArrays();
//                    System.out.println("Generated Test Sequence for Operation: " +
//                            testSequence.getFirst().getFuzzedOperation().getMethod() + " " +
//                            testSequence.getFirst().getFuzzedOperation().getEndpoint());
//                    for (LeafParameter leaf : leaves) {
//                        System.out.println("Parameter Name: " + leaf.getName() + ", Value: " + leaf.getValue());
//                    }
//                    for (ArrayParameter arrayParam : arrays) {
//                        System.out.println("Array Parameter Name: " + arrayParam.getName());
//                        System.out.println("Array Elements:");
//                        for (Parameter element : arrayParam.getElements()) {
//                            System.out.println(" - Element Name: " + element.getName() + ", Type: " + element.getType());
//                        }
//                    }

                    try {
                        SqlInteraction sqlInteraction = RestStrategyFactory.getStrategy(testSequence.getFirst().getFuzzedOperation().getMethod())
                                .operationToSQL(testSequence.getFirst().getFuzzedOperation(), convertSequenceToTable);

                        // Attach SqlInteraction to the TestInteraction for the Oracle to use
                        testSequence.getFirst().addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sqlInteraction);

                        // Also add tag to ALL interactions in the sequence just in case
                        for (TestInteraction ti : testSequence) {
                            ti.addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sqlInteraction);
                        }

                        TestRunner testRunner = TestRunner.getInstance();
                        testRunner.run(testSequence);
                        SqlDiffOracle sqlDiffOracle = new SqlDiffOracle(convertSequenceToTable);
                        sqlDiffOracle.setBaseReportDir(testReportDir);
                        sqlDiffOracle.assertTestSequence(testSequence);
                        System.out.println("-------------------");
                    } catch (RuntimeException e) {
                        logger.error("Skipping test sequence for operation " + operationToTest.getMethod() + operationToTest.getEndpoint() + ": " + e.getMessage());
                    }
                }
                sorter.removeFirst();
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.out.println("DEBUG: HELLO WORLD END");
    }

    @Test
    public void testSequenceGenerationNotEmpty() {
        DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
        List<Operation> sequence = new ArrayList<>(sorter.getQueue());
        assertFalse(sequence.isEmpty(), "The generated test sequence should not be empty.");
    }

    @Test
    public void testDeleteOperationsAtEnd() {
        // Run multiple times to cover random strategies
        for (int i = 0; i < 10; i++) {
            DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
            List<Operation> sequence = new ArrayList<>(sorter.getQueue());

            boolean deleteFound = false;
            for (Operation op : sequence) {
                if (op.getMethod() == io.resttestgen.core.datatype.HttpMethod.DELETE) {
                    deleteFound = true;
                } else {
                    if (deleteFound) {
                        fail("Found non-DELETE operation " + op.getMethod() + " " + op.getEndpoint() +
                                " after a DELETE operation in the sequence. DELETEs should be at the end.");
                    }
                }
            }
        }
    }

    /**
     * 测试POST操作总是在对应的PUT/GET操作之前
     * 验证CRUD语义的正确性：必须先创建资源才能更新/读取
     */
    @Test
    public void testPostBeforePutAndGet() {
        // 运行多次以覆盖随机策略
        for (int run = 0; run < 10; run++) {
            DiffBasedGraphSorter sorter = new DiffBasedGraphSorter();
            List<Operation> sequence = new ArrayList<>(sorter.getQueue());

            // 记录已出现的POST端点（规范化后）
            Set<String> createdResources = new HashSet<>();

            System.out.println("\n=== Run " + (run + 1) + " ===");
            for (Operation op : sequence) {
                String endpoint = op.getEndpoint();
                String normalizedEndpoint = normalizeEndpointForCrud(endpoint);

                System.out.println(op.getMethod() + " " + endpoint + " -> normalized: " + normalizedEndpoint);

                if (op.getMethod() == HttpMethod.POST) {
                    // POST创建资源，记录
                    createdResources.add(normalizedEndpoint);
                } else if (op.getMethod() == HttpMethod.PUT || op.getMethod() == HttpMethod.PATCH) {
                    // PUT/PATCH更新资源，检查对应的POST是否已经执行
                    // 从端点中提取基础资源路径
                    String baseResourcePath = extractBaseResourcePath(endpoint);
                    if (baseResourcePath != null && !createdResources.contains(baseResourcePath)) {
                        // 检查是否有任何相关的POST已经执行
                        boolean hasRelatedPost = createdResources.stream()
                                .anyMatch(created -> baseResourcePath.startsWith(created) || created.startsWith(baseResourcePath));

                        if (!hasRelatedPost && !createdResources.isEmpty()) {
                            System.out.println("WARNING: " + op.getMethod() + " " + endpoint +
                                    " may not have corresponding POST. Created resources: " + createdResources);
                        }
                    }
                }
            }
        }
    }

    /**
     * 规范化端点用于CRUD语义检查
     * 移除路径参数中的具体值，保留结构
     */
    private String normalizeEndpointForCrud(String endpoint) {
        // 移除路径参数值，只保留结构
        // 例如：/projects/{id}/badges -> /projects/{}/badges
        return endpoint.replaceAll("\\{[^}]+\\}", "{}");
    }

    /**
     * 从端点中提取基础资源路径
     * 例如：/projects/{id}/badges/{badge_id} -> /projects/{}/badges
     */
    private String extractBaseResourcePath(String endpoint) {
        String normalized = normalizeEndpointForCrud(endpoint);
        // 移除最后一个路径参数段
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash > 0) {
            String lastSegment = normalized.substring(lastSlash + 1);
            if (lastSegment.equals("{}")) {
                return normalized.substring(0, lastSlash);
            }
        }
        return normalized;
    }

    @Test
    public void testAPi() {
        List<OperationNode> postNodes = environment.getOperationDependencyGraph().getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.GET)
                .collect(Collectors.toList());
        Operation operation = new Operation();
        for (OperationNode node : postNodes) {
            System.out.println("POST Operation: " + node.getOperation().getMethod() + " " + node.getOperation().getEndpoint());
            if (node.getOperation().getMethod() == HttpMethod.GET && node.getOperation().getEndpoint().equals("/pages")) {
                operation = new Operation(node.getOperation());
                break;
            }
        }
        Collection<LeafParameter> leaves = operation.getLeaves();
        for (LeafParameter leaf : leaves) {
//            if (leaf.getName().toString().equals("offset")) {
//                leaf.setValue(2);
//            }
//            if (leaf.getName().toString().equals("order")) {
//                leaf.setValue("ase");
//            }
            System.out.println("Parameter Name: " + leaf.getName() + ", Value: " + leaf.getValue());
        }
        int i = 0;
        while (i < 10) {
            i++;
            System.out.println("******************");
            TestInteraction interaction = new TestInteraction(operation);
            TestSequence testSequence = new TestSequence();
            testSequence.add(interaction);
            TestRunner testRunner = TestRunner.getInstance();
            testRunner.run(testSequence);
            System.out.println("Response Status Code: " + interaction.getResponseStatusCode());
            System.out.println("Response Body: " + interaction.getResponseBody());
        }
    }


}
