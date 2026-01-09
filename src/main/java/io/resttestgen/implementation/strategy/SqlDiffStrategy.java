package io.resttestgen.implementation.strategy;

import io.resttestgen.core.Environment;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.operationdependencygraph.OperationDependencyGraph;
import io.resttestgen.core.testing.Strategy;
import io.resttestgen.core.testing.TestRunner;
import io.resttestgen.core.testing.TestSequence;
import io.resttestgen.core.testing.operationsorter.OperationsSorter;
import io.resttestgen.implementation.fuzzer.ErrorFuzzer;
import io.resttestgen.implementation.fuzzer.NominalFuzzer;
import io.resttestgen.implementation.operationssorter.DiffBasedGraphSorter;
import io.resttestgen.implementation.operationssorter.GraphBasedOperationsSorter;
import io.resttestgen.implementation.oracle.SqlDiffOracle;
import io.resttestgen.implementation.sql.ConvertSequenceToTable;
import io.resttestgen.implementation.sql.SqlInteraction;
import io.resttestgen.implementation.sql.factory.RestStrategyFactory;
import io.resttestgen.implementation.writer.CoverageReportWriter;
import io.resttestgen.implementation.writer.ReportWriter;
import io.resttestgen.implementation.writer.RestAssuredWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.PriorityQueue;

@SuppressWarnings("unused")
public class SqlDiffStrategy extends Strategy {

    private static final Logger logger = LogManager.getLogger(SqlDiffStrategy.class);

    private final TestSequence globalNominalTestSequence = new TestSequence();
    //整个过程循环次数
    private final int numberOfOperationSorter = 30;

    public void start() {

        // According to the order provided by the graph, execute the nominal fuzzer
        //搜集当前API规范文件里面的所有信息，进行数据库建表操作
        //用一个list来存储当前建好的表里面的所有的列名
//        ConvertSequenceToTable convertSequenceToTable = new ConvertSequenceToTable(); // Removed unused variable
//        convertSequenceToTable.createTableByColumns();
        //循环选取numberOfOperationSorter条不同的测试序列，每次从postQueue中随机选择一个节点作为起始节点，从ODG中dfs选择测试序列，
        int i = 0;
        while (i < numberOfOperationSorter) {
            ConvertSequenceToTable convertSequenceToTable = null;
            try {
                //生成测试序列
                OperationsSorter sorter = new DiffBasedGraphSorter();
                //使用queue生成建表，宽表的数据范围比较小
                convertSequenceToTable = new ConvertSequenceToTable(sorter.getQueue());
                convertSequenceToTable.createTableByColumns();
                logger.info("Starting strategy iteration " + (i + 1) + "/" + numberOfOperationSorter);

                // Create a single directory for the entire strategy iteration
                long timestamp = System.currentTimeMillis();
                String sequenceDirName = "sequence_" + i + "_" + timestamp;
                Path sequenceReportDir = Paths.get("reports", "sql-diff", sequenceDirName);
                Files.createDirectories(sequenceReportDir);

                //对操作序列进行赋值操作
                while (!sorter.isEmpty()) {
                    Operation operationToTest = sorter.getFirst();
                    logger.debug("Testing operation " + operationToTest);
                    NominalFuzzer nominalFuzzer = new NominalFuzzer(operationToTest);
                    //每一个操作生成20个测试用例
                    List<TestSequence> nominalSequences = nominalFuzzer.generateTestSequences(20);

                    for (TestSequence testSequence : nominalSequences) {
                         SqlInteraction sqlInteraction = RestStrategyFactory.getStrategy(testSequence.getFirst().getFuzzedOperation().getMethod())
                                .operationToSQL(testSequence.getFirst().getFuzzedOperation(), convertSequenceToTable);

                        // Attach SqlInteraction to the TestInteraction for the Oracle to use
                        testSequence.getFirst().addTag(SqlDiffOracle.SQL_INTERACTION_TAG, sqlInteraction);

                        // Run test sequence
                        TestRunner testRunner = TestRunner.getInstance();
                        testRunner.run(testSequence);
                        // Evaluate sequence with oracles
                        SqlDiffOracle sqlDiffOracle = new SqlDiffOracle(convertSequenceToTable);
                        sqlDiffOracle.setBaseReportDir(sequenceReportDir); // Set the shared report directory
                        sqlDiffOracle.assertTestSequence(testSequence);

                        // Write report to file
                        try {
                            ReportWriter reportWriter = new ReportWriter(testSequence);
                            reportWriter.write();
                            RestAssuredWriter restAssuredWriter = new RestAssuredWriter(testSequence);
                            restAssuredWriter.write();
                        } catch (IOException e) {
                            logger.warn("Could not write report to file.");
                            e.printStackTrace();
                        }
                    }
                    globalNominalTestSequence.append(nominalSequences);
                    sorter.removeFirst();
                }
                // 这里已经将测试序列中的所有操作的测试用例都执行完毕
                globalNominalTestSequence.filterBySuccessfulStatusCode();

                //GraphTestCase.generateGraph(globalNominalTestSequence);

//                ErrorFuzzer errorFuzzer = new ErrorFuzzer(globalNominalTestSequence);
//                errorFuzzer.generateTestSequences(10);

                try {
                    CoverageReportWriter coverageReportWriter = new CoverageReportWriter(TestRunner.getInstance().getCoverage());
                    coverageReportWriter.write();
                } catch (IOException e) {
                    logger.warn("Could not write Coverage report to file.");
                    e.printStackTrace();
                }
            } catch (Exception e) {
                logger.error("An error occurred during the strategy execution: " + e.getMessage());
                e.printStackTrace();
            } finally  {
//                if (convertSequenceToTable != null) {
//                    convertSequenceToTable.dropTable();
//                }
                TestRunner.getInstance();
                i++;
            }

        }

    }
}
