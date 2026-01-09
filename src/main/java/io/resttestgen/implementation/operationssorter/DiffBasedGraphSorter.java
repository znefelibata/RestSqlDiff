package io.resttestgen.implementation.operationssorter;

import com.google.common.collect.Sets;
import io.resttestgen.core.Environment;
import io.resttestgen.core.datatype.HttpMethod;
import io.resttestgen.core.datatype.NormalizedParameterName;
import io.resttestgen.core.datatype.parameter.leaves.LeafParameter;
import io.resttestgen.core.helper.ExtendedRandom;
import io.resttestgen.core.openapi.Operation;
import io.resttestgen.core.operationdependencygraph.DependencyEdge;
import io.resttestgen.core.operationdependencygraph.OperationDependencyGraph;
import io.resttestgen.core.operationdependencygraph.OperationNode;
import io.resttestgen.core.testing.operationsorter.StaticOperationsSorter;
import io.resttestgen.core.testing.parametervalueprovider.ParameterValueProviderCachedFactory;
import io.resttestgen.implementation.parametervalueprovider.ParameterValueProviderType;
import io.resttestgen.implementation.parametervalueprovider.single.ResponseDictionaryParameterValueProvider;

import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
/*
  该sorter用来从ODG中选择一条测试序列  --- 使用dfs从Post节点开始，优先选择出度多的节点，有多少个Post节点就有多少个对应的Delete节点，用一个map来进行post和delete的映射
 */
public class DiffBasedGraphSorter extends StaticOperationsSorter {

    private int maximumAttempts = 10;

    private final OperationDependencyGraph graph = Environment.getInstance().getOperationDependencyGraph();
    private final ExtendedRandom random = Environment.getInstance().getRandom();
    //用来记录post和delete的映射关系
    private final Map<Operation, Operation> postToDeleteMap = new HashMap<>();

    // 序列长度配置
    private static final int MIN_SEQUENCE_LENGTH = 5;   // 最小序列长度
    private static final int MAX_SEQUENCE_LENGTH = 25;  // 最大序列长度
    private int currentMaxDepth;  // 当前序列的最大深度（动态生成）
    
    // 前置依赖链配置
    private static final int MIN_PREREQUISITE_CHAIN = 1;  // 最小前置链长度
    private static final int MAX_PREREQUISITE_CHAIN = 5;  // 最大前置链长度（避免过长的依赖链）
    
    // DELETE 操作策略枚举
    public enum DeleteStrategy {
        CORRESPONDING_ONLY,   // 只添加对应 POST 的 DELETE（严格模式）
        RANDOM_ONLY,          // 只随机添加 DELETE（探索模式）
        MIXED                 // 混合模式：部分对应 + 部分随机（平衡模式）
    }
    
    // DELETE 配置
    private static final int MIN_DELETE_COUNT = 1;      // 最少 DELETE 数量
    private static final int MAX_DELETE_COUNT = 5;      // 最多 DELETE 数量
    private DeleteStrategy currentDeleteStrategy;       // 当前使用的 DELETE 策略

    //用来记录所有的post节点
    private final List<OperationNode> postNodes = new ArrayList<>();
    private final List<OperationNode> deleteNodes = new ArrayList<>();


    public DiffBasedGraphSorter() {
        computePostToDeleteMap();
        computeAllPost();
        computeAllDelete();
        getTestSequence();
    }

    public Map<Operation, Operation> getPostToDeleteMap() {
        return postToDeleteMap;
    }

    public List<OperationNode> getAllPostNodes() {
        return postNodes;
    }

    public Set<OperationNode> getAllNodes() {
        return graph.getGraph().vertexSet();
    }

    public List<OperationNode> getAllDeleteNodes() {
        return deleteNodes;
    }

    @Override
    public Operation removeFirst() {
        Operation removedOperation = super.removeFirst();
        graph.increaseOperationTestingAttempts(removedOperation);
        return removedOperation;
    }
    /**,
     * 获得所有的post节点
     */
    public void computeAllPost() {
        postNodes.addAll(graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.POST)
                .collect(Collectors.toList()));
        Collections.shuffle(postNodes);
    }
    /**.
     * 获得所有的delete节点
     */
    public void computeAllDelete() {
        deleteNodes.addAll(graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.DELETE)
                .collect(Collectors.toList()));
        Collections.shuffle(deleteNodes);
    }
    /**.
     * 用来生成post和delete的映射关系
     */
    public void computePostToDeleteMap() {
        List<OperationNode> postNodes = graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.POST)
                .collect(Collectors.toList());
        List<OperationNode> deleteNodes = graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.DELETE)
                .collect(Collectors.toList());
//        Collections.shuffle(postNodes);
//        Collections.shuffle(deleteNodes);
        int size = Math.min(postNodes.size(), deleteNodes.size());
        for (OperationNode postNode : postNodes) {
            Operation postOperation = postNode.getOperation();
            String postEndpoint = postOperation.getEndpoint();
            for (OperationNode deleteNode : deleteNodes) {
                String deleteEndpoint = deleteNode.getOperation().getEndpoint();
                Operation deleteOperation = deleteNode.getOperation();
                if (postEndpoint.equals(deleteEndpoint) || deleteEndpoint.contains(postEndpoint) || postEndpoint.contains(deleteEndpoint)) {
                    postToDeleteMap.put(postOperation, deleteOperation);
                }
            }
        }
    }

    //用dfs得到测试序列
    public void getTestSequence() {
        emptyCurrentQueue();

        // 动态生成当前序列的最大深度（增加多样性）
        Random random = new Random();
        currentMaxDepth = MIN_SEQUENCE_LENGTH + random.nextInt(MAX_SEQUENCE_LENGTH - MIN_SEQUENCE_LENGTH + 1);
        
        // 随机选择 DELETE 策略（增加 fuzz 多样性）
        DeleteStrategy[] strategies = DeleteStrategy.values();
        currentDeleteStrategy = strategies[random.nextInt(strategies.length)];
        
        System.out.println("DEBUG: Current max sequence depth: " + currentMaxDepth);
        System.out.println("DEBUG: Current DELETE strategy: " + currentDeleteStrategy);

        // 改进策略：
        // 80% 的概率优先选择有出度（有后续依赖操作）的POST节点作为起始点，以生成更有意义的长序列
        // 20% 的概率从所有POST节点中随机选择，以确保那些独立的（出度为0）POST操作也能被测试到
        List<OperationNode> candidateNodes;

        if (random.nextDouble() < 0.8) {
            candidateNodes = postNodes.stream()
                    .filter(node -> graph.getGraph().outDegreeOf(node) > 0)
                    .collect(Collectors.toList());

            // 如果筛选后为空（说明所有POST节点都没有出度），则回退到所有POST节点
            if (candidateNodes.isEmpty()) {
                candidateNodes = postNodes;
            }
        } else {
            // 随机模式，选择所有节点作为候选
            candidateNodes = postNodes;
        }

        if (candidateNodes.isEmpty()) {
            throw new IllegalStateException("No POST operation found in the Operation Dependency Graph.");
        }

        // 从候选节点中随机选择一个作为"目标操作"
        OperationNode targetNode = candidateNodes.get(random.nextInt(candidateNodes.size()));

        System.out.println("DEBUG: Selected target node: " + targetNode.getOperation().getMethod() + " " + targetNode.getOperation().getEndpoint());
        System.out.println("DEBUG: Target node out-degree: " + graph.getGraph().outDegreeOf(targetNode));
        System.out.println("DEBUG: Target node in-degree: " + graph.getGraph().inDegreeOf(targetNode));

        Set<OperationNode> visited = new HashSet<>();
        // 记录已添加到序列中的POST操作，用于后续添加对应的DELETE（保持顺序）
        List<Operation> postOperationsInSequence = new ArrayList<>();

        // ===== 第一阶段：向上追溯依赖，找到所有前置操作 =====
        // 使用拓扑排序的思想，先执行没有未满足依赖的操作
        List<OperationNode> prerequisiteChain = findPrerequisiteChain(targetNode, visited);

        System.out.println("DEBUG: Prerequisite chain size: " + prerequisiteChain.size());
        for (OperationNode node : prerequisiteChain) {
            System.out.println("DEBUG: Prerequisite: " + node.getOperation().getMethod() + " " + node.getOperation().getEndpoint());
        }

        // 将前置操作按正确顺序添加到队列（依赖在前，被依赖在后）
        for (OperationNode prereqNode : prerequisiteChain) {
            Operation prereqOp = prereqNode.getOperation();
            if (prereqOp.getMethod() != HttpMethod.DELETE) {
                queue.addLast(prereqOp);
                visited.add(prereqNode);
                if (prereqOp.getMethod() == HttpMethod.POST) {
                    postOperationsInSequence.add(prereqOp);
                }
            }
        }

        // ===== 第二阶段：探索依赖目标节点的后续操作（如GET/PUT）=====
        // 这些操作依赖目标POST产出的数据，应该在POST之后执行
        // 使用优先队列，按照节点的"价值"排序
        PriorityQueue<OperationNode> priorityQueue = new PriorityQueue<>((n1, n2) -> {
            int score1 = computeNodeScore(n1, visited);
            int score2 = computeNodeScore(n2, visited);
            return score2 - score1; // 降序排列，价值高的优先
        });

        // 从目标节点的入边开始（这些是依赖目标节点的操作）
        // 入边的源节点是消费者，它们依赖目标节点（生产者）
        for (OperationNode prereqNode : prerequisiteChain) {
            Set<DependencyEdge> incomingEdges = graph.getGraph().incomingEdgesOf(prereqNode);
            for (DependencyEdge edge : incomingEdges) {
                OperationNode consumerNode = graph.getGraph().getEdgeSource(edge);
                if (!visited.contains(consumerNode) && consumerNode.getOperation().getMethod() != HttpMethod.DELETE) {
                    priorityQueue.add(consumerNode);
                }
            }
        }

        while (!priorityQueue.isEmpty() && queue.size() < currentMaxDepth) {
            OperationNode currentNode = priorityQueue.poll();

            if (visited.contains(currentNode)) {
                continue;
            }

            visited.add(currentNode);
            Operation currentOp = currentNode.getOperation();

            // 跳过DELETE操作，稍后统一添加
            if (currentOp.getMethod() == HttpMethod.DELETE) {
                continue;
            }

            queue.addLast(currentOp);

            // 记录POST操作
            if (currentOp.getMethod() == HttpMethod.POST) {
                postOperationsInSequence.add(currentOp);
            }

            // 继续探索当前节点的入边（依赖当前节点的操作）
            Set<DependencyEdge> incomingEdges = graph.getGraph().incomingEdgesOf(currentNode);
            for (DependencyEdge edge : incomingEdges) {
                OperationNode consumerNode = graph.getGraph().getEdgeSource(edge);
                if (!visited.contains(consumerNode) && consumerNode.getOperation().getMethod() != HttpMethod.DELETE) {
                    priorityQueue.add(consumerNode);
                }
            }
        }

        // ===== 第三阶段：添加DELETE操作（按CRUD语义，DELETE应该在最后）=====
        // 根据当前选择的策略添加 DELETE 操作
        List<Operation> deleteOperationsToAdd = new ArrayList<>();
        
        // 随机决定本次添加的 DELETE 数量
        int targetDeleteCount = MIN_DELETE_COUNT + random.nextInt(MAX_DELETE_COUNT - MIN_DELETE_COUNT + 1);
        
        switch (currentDeleteStrategy) {
            case CORRESPONDING_ONLY:
                // 策略1：只添加序列中 POST 操作对应的 DELETE（逆序，LIFO原则）
                addCorrespondingDeletes(postOperationsInSequence, deleteOperationsToAdd, targetDeleteCount);
                break;
                
            case RANDOM_ONLY:
                // 策略2：只随机添加 DELETE（探索模式，可能发现边界情况）
                addRandomDeletes(deleteOperationsToAdd, targetDeleteCount, random);
                break;
                
            case MIXED:
                // 策略3：混合模式 - 一半对应，一半随机
                int correspondingCount = targetDeleteCount / 2;
                int randomCount = targetDeleteCount - correspondingCount;
                addCorrespondingDeletes(postOperationsInSequence, deleteOperationsToAdd, correspondingCount);
                addRandomDeletes(deleteOperationsToAdd, randomCount, random);
                break;
        }

        System.out.println("DEBUG: DELETE operations to add: " + deleteOperationsToAdd.size());
        for (Operation deleteOp : deleteOperationsToAdd) {
            System.out.println("DEBUG: DELETE: " + deleteOp.getEndpoint());
        }

        // 将DELETE操作添加到队列末尾
        queue.addAll(deleteOperationsToAdd);

        System.out.println("DEBUG: Final sequence length: " + queue.size() + " (non-DELETE: " +
                          (queue.size() - deleteOperationsToAdd.size()) + ", DELETE: " + deleteOperationsToAdd.size() + ")");
    }
    
    /**
     * 添加与序列中 POST 操作对应的 DELETE（逆序，后创建的先删除）
     */
    private void addCorrespondingDeletes(List<Operation> postOperationsInSequence, 
                                          List<Operation> deleteOperationsToAdd, int maxCount) {
        for (int i = postOperationsInSequence.size() - 1; i >= 0 && deleteOperationsToAdd.size() < maxCount; i--) {
            Operation postOp = postOperationsInSequence.get(i);
            if (postToDeleteMap.containsKey(postOp)) {
                Operation deleteOp = postToDeleteMap.get(postOp);
                if (!deleteOperationsToAdd.contains(deleteOp)) {
                    deleteOperationsToAdd.add(deleteOp);
                }
            }
        }
    }
    
    /**
     * 随机添加 DELETE 操作（探索模式）
     */
    private void addRandomDeletes(List<Operation> deleteOperationsToAdd, int count, Random random) {
        if (deleteNodes.isEmpty()) return;
        
        int initialSize = deleteOperationsToAdd.size();
        int targetSize = initialSize + count;
        int attempts = 0;
        int maxAttempts = count * 3; // 防止无限循环
        
        while (deleteOperationsToAdd.size() < targetSize && attempts < maxAttempts) {
            OperationNode deleteNode = deleteNodes.get(random.nextInt(deleteNodes.size()));
            Operation deleteOp = deleteNode.getOperation();
            if (!deleteOperationsToAdd.contains(deleteOp)) {
                deleteOperationsToAdd.add(deleteOp);
            }
            attempts++;
        }
    }

    /**
     * 向上追溯依赖链，找到目标操作所需的所有前置操作
     * 只追溯 POST 类型的依赖（创建操作），忽略 GET/PUT 等操作
     *
     * 注意：ODG中边的方向是 消费者 -> 生产者
     * 所以要找前置操作，需要遍历出边，找到能提供所需参数的 POST 操作
     *
     * @param targetNode 目标操作节点（通常是一个需要前置操作的POST）
     * @param globalVisited 全局已访问集合（用于避免重复）
     * @return 按执行顺序排列的前置操作列表（依赖最少的在前，目标节点在最后）
     */
    private List<OperationNode> findPrerequisiteChain(OperationNode targetNode, Set<OperationNode> globalVisited) {
        // 使用 LinkedHashSet 保持插入顺序并去重
        LinkedHashSet<OperationNode> orderedResult = new LinkedHashSet<>();
        Set<OperationNode> localVisited = new HashSet<>();

        // 使用递归 DFS 进行拓扑排序
        // 后序遍历：先访问所有依赖，再添加当前节点
        // 只关注 POST 操作作为依赖
        topologicalSortPostOnly(targetNode, localVisited, globalVisited, orderedResult);

        // orderedResult 现在是拓扑排序的结果（依赖在前，被依赖在后）
        List<OperationNode> result = new ArrayList<>(orderedResult);

        // 动态限制前置链的长度（在配置的最大值内随机选择）
        // 这样可以增加测试多样性：有时测试长链，有时测试短链
        Random random = new Random();
        int maxPrerequisites = MIN_PREREQUISITE_CHAIN + random.nextInt(MAX_PREREQUISITE_CHAIN - MIN_PREREQUISITE_CHAIN + 1);
        
        if (result.size() > maxPrerequisites) {
            // 保留最后 maxPrerequisites 个节点（包括目标节点和最近的依赖）
            result = result.subList(result.size() - maxPrerequisites, result.size());
            System.out.println("DEBUG: Prerequisite chain truncated to " + maxPrerequisites + " nodes");
        }

        return result;
    }

    /**
     * 拓扑排序的辅助函数 - 后序 DFS
     * 只关注 POST 操作作为依赖，确保创建操作的顺序正确
     */
    private void topologicalSortPostOnly(OperationNode node, Set<OperationNode> localVisited,
                                  Set<OperationNode> globalVisited, LinkedHashSet<OperationNode> result) {
        if (localVisited.contains(node) || globalVisited.contains(node)) {
            return;
        }

        localVisited.add(node);

        // 跳过DELETE操作
        if (node.getOperation().getMethod() == HttpMethod.DELETE) {
            return;
        }

        // 获取当前节点的所有出边（当前节点依赖的操作）
        Set<DependencyEdge> outgoingEdges = graph.getGraph().outgoingEdgesOf(node);

        for (DependencyEdge edge : outgoingEdges) {
            OperationNode dependencyNode = graph.getGraph().getEdgeTarget(edge);

            // 只递归处理 POST 类型的依赖节点
            // 这确保我们只追溯创建操作链，而不是所有可能的依赖
            if (dependencyNode.getOperation().getMethod() == HttpMethod.POST) {
                topologicalSortPostOnly(dependencyNode, localVisited, globalVisited, result);
            }
        }

        // 后序添加：所有依赖处理完毕后，再添加当前节点
        // 但只添加 POST 节点（或者目标节点本身）
        if (node.getOperation().getMethod() == HttpMethod.POST) {
            result.add(node);
        }
    }

    /**
     * 计算节点的优先级分数
     * 分数越高，该节点越应该被优先访问
     */
    private int computeNodeScore(OperationNode node, Set<OperationNode> visited) {
        int score = 0;
        Operation op = node.getOperation();

        // 1. 出度加分：出度越大，后续可探索的路径越多
        int outDegree = graph.getGraph().outDegreeOf(node);
        score += outDegree * 10;

        // 2. 未满足的依赖加分：有更多未满足依赖的节点可能更有价值
        long unsatisfiedEdges = graph.getGraph().outgoingEdgesOf(node).stream()
                .filter(edge -> !edge.isSatisfied())
                .count();
        score += unsatisfiedEdges * 5;

        // 3. CRUD语义优先级：
        // GET/PUT 操作通常需要在 POST 之后执行，应该被优先选择（当POST已在序列中时）
        switch (op.getMethod()) {
            case GET:
                score += 8; // GET操作用于验证状态，优先级较高
                break;
            case PUT:
            case PATCH:
                score += 6; // 更新操作优先级中等
                break;
            case POST:
                score += 4; // POST已经作为起点处理，这里优先级较低
                break;
            case DELETE:
                score -= 20; // DELETE应该最后执行，负分
                break;
            default:
                break;
        }

        // 4. 如果节点有已访问的前驱节点，说明其依赖可能已满足，加分
        long visitedPredecessors = graph.getGraph().incomingEdgesOf(node).stream()
                .map(edge -> graph.getGraph().getEdgeSource(edge))
                .filter(visited::contains)
                .count();
        score += visitedPredecessors * 3;

        return score;
    }

    /**
     * Removes all elements in the queue
     */
    private void emptyCurrentQueue() {
        while (!queue.isEmpty()) {
            queue.removeFirst();
        }
    }

    /**
     * Compute the number of unsatisfied parameters by subtracting the set of satisfied parameters from the set of total
     * parameters in the operation. Moreover, removes the parameters that are not in the graph, but have a value stored
     * in the global dictionary.
     * @param node the node in the operation dependency graph.
     * @return the number of unsatisfied parameters.
     */
    private int computeNumberOfUnsatisfiedParameters(OperationNode node) {
        Set<NormalizedParameterName> satisfiedParameters = graph.getGraph().outgoingEdgesOf(node).stream()
                .filter(DependencyEdge::isSatisfied)
                .map(DependencyEdge::getNormalizedName)
                .collect(Collectors.toSet());
        Set<NormalizedParameterName> allParametersInOperation = node.getOperation().getReferenceLeaves().stream()
                .map(LeafParameter::getNormalizedName)
                .collect(Collectors.toSet());
        Set<NormalizedParameterName> unsatisfiedParameters = Sets.difference(allParametersInOperation, satisfiedParameters);

        ResponseDictionaryParameterValueProvider provider = (ResponseDictionaryParameterValueProvider) ParameterValueProviderCachedFactory.getParameterValueProvider(ParameterValueProviderType.RESPONSE_DICTIONARY);
        provider.setSameNormalizedNameValueSourceClass();

        Set<NormalizedParameterName> parametersInDictionary = new HashSet<>();
        for (NormalizedParameterName unsatisfiedParameter : unsatisfiedParameters) {
            List<LeafParameter> foundParameters = node.getOperation().searchReferenceRequestParametersByNormalizedName(unsatisfiedParameter)
                    .stream().filter(p -> p instanceof LeafParameter).map(p -> (LeafParameter) p).collect(Collectors.toList());
            if (foundParameters.size() > 0) {
                LeafParameter parameter = foundParameters.get(0);
                if (provider.countAvailableValuesFor(parameter) > 0) {
                    parametersInDictionary.add(unsatisfiedParameter);
                }
            }
        }
        return Sets.difference(unsatisfiedParameters, parametersInDictionary).size();
    }

    public int getMaximumAttempts() {
        return maximumAttempts;
    }

    public void setMaximumAttempts(int maximumAttempts) {
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("The number of maximum attempts must be greater or equal to 1.");
        }
        this.maximumAttempts = maximumAttempts;
    }
}
