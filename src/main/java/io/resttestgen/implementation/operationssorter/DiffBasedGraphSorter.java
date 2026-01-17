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
    private static final int MAX_PREREQUISITE_CHAIN = 8;  // 最大前置链长度（避免过长的依赖链）
    
    // DELETE 操作策略枚举
    public enum DeleteStrategy {
        CORRESPONDING_ONLY,   // 只添加对应 POST 的 DELETE（严格模式）
        RANDOM_ONLY,          // 只随机添加 DELETE（探索模式）
        MIXED                 // 混合模式：部分对应 + 部分随机（平衡模式）
    }
    
    // DELETE 配置
    private static final int MIN_DELETE_COUNT = 1;      // 最少 DELETE 数量
    private static final int MAX_DELETE_COUNT = 3;      // 最多 DELETE 数量
    private DeleteStrategy currentDeleteStrategy;       // 当前使用的 DELETE 策略

    //用来记录所有的post节点
    private final List<OperationNode> postNodes = new ArrayList<>();
    private final List<OperationNode> deleteNodes = new ArrayList<>();
    private final List<OperationNode> getNodes = new ArrayList<>(); // 新增：记录所有的GET节点


    public DiffBasedGraphSorter() {
        computeAllPost();
        computeAllDelete();
        computeAllGet(); // 新增：计算所有GET节点
        computePostToDeleteMap();
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
     * 获得所有的get节点
     */
    public void computeAllGet() {
        getNodes.addAll(graph.getGraph().vertexSet().stream()
                .filter(n -> n.getOperation().getMethod() == HttpMethod.GET)
                .collect(Collectors.toList()));
        Collections.shuffle(getNodes);
    }
    /**.
     * 用来生成post和delete的映射关系
     * 匹配规则：
     * 1. POST /resources 应该匹配 DELETE /resources/{id}
     * 2. POST /parent/{parentId}/resources 应该匹配 DELETE /parent/{parentId}/resources/{id}
     * 核心逻辑：DELETE端点去掉最后一个路径参数后应该等于POST端点
     */
    public void computePostToDeleteMap() {
        for (OperationNode postNode : postNodes) {
            Operation postOperation = postNode.getOperation();
            String postEndpoint = postOperation.getEndpoint();

            // 标准化POST端点（移除尾部斜杠）
            String normalizedPostEndpoint = postEndpoint.endsWith("/")
                ? postEndpoint.substring(0, postEndpoint.length() - 1)
                : postEndpoint;

            Operation bestMatch = null;
            int bestMatchScore = -1;

            for (OperationNode deleteNode : deleteNodes) {
                String deleteEndpoint = deleteNode.getOperation().getEndpoint();
                Operation deleteOperation = deleteNode.getOperation();

                // 标准化DELETE端点（移除尾部斜杠）
                String normalizedDeleteEndpoint = deleteEndpoint.endsWith("/")
                    ? deleteEndpoint.substring(0, deleteEndpoint.length() - 1)
                    : deleteEndpoint;

                // 检查匹配条件
                int matchScore = calculateEndpointMatchScore(normalizedPostEndpoint, normalizedDeleteEndpoint);

                if (matchScore > bestMatchScore) {
                    bestMatchScore = matchScore;
                    bestMatch = deleteOperation;
                }
            }

            if (bestMatch != null && bestMatchScore > 0) {
                postToDeleteMap.put(postOperation, bestMatch);
            }
        }
    }

    /**
     * 计算POST和DELETE端点的匹配分数
     * 分数越高表示匹配越精确
     *
     * @param postEndpoint POST端点（已标准化）
     * @param deleteEndpoint DELETE端点（已标准化）
     * @return 匹配分数，0表示不匹配
     */
    private int calculateEndpointMatchScore(String postEndpoint, String deleteEndpoint) {
        // 将端点分割成路径片段
        String[] postSegments = postEndpoint.split("/");
        String[] deleteSegments = deleteEndpoint.split("/");

        // 场景1：DELETE端点比POST端点多一个路径参数
        // 例如：POST /resources -> DELETE /resources/{id}
        // 或者：POST /parent/{parentId}/resources -> DELETE /parent/{parentId}/resources/{id}
        if (deleteSegments.length == postSegments.length + 1) {
            // 检查最后一个DELETE片段是否是路径参数（以{开头并以}结尾）
            String lastDeleteSegment = deleteSegments[deleteSegments.length - 1];
            if (isPathParameter(lastDeleteSegment)) {
                // 检查前面的片段是否都匹配（考虑路径参数）
                boolean allMatch = true;
                for (int i = 0; i < postSegments.length; i++) {
                    if (!segmentsMatch(postSegments[i], deleteSegments[i])) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    // 精确匹配：DELETE = POST + /{id}
                    return 100;
                }
            }
        }

        // 场景2：端点完全相同（某些API设计中POST和DELETE共享端点）
        if (postEndpoint.equals(deleteEndpoint)) {
            return 50;
        }

        // 场景3：DELETE端点是POST端点加上固定后缀（如 /resources -> /resources:delete）
        // 这种情况较少见，给较低分数
        if (deleteEndpoint.startsWith(postEndpoint) &&
            (deleteEndpoint.length() > postEndpoint.length())) {
            String suffix = deleteEndpoint.substring(postEndpoint.length());
            // 确保后缀以/开头且只有一个路径参数
            if (suffix.startsWith("/") && suffix.split("/").length == 2) {
                String paramPart = suffix.substring(1);
                if (isPathParameter(paramPart)) {
                    return 90;
                }
            }
        }

        return 0; // 不匹配
    }

    /**
     * 检查一个路径片段是否是路径参数
     * 路径参数的格式为 {paramName}
     */
    private boolean isPathParameter(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /**
     * 检查两个路径片段是否匹配
     * 如果两者都是路径参数或者字面值相同，则认为匹配
     */
    private boolean segmentsMatch(String segment1, String segment2) {
        // 如果字面值相同
        if (segment1.equals(segment2)) {
            return true;
        }
        // 如果两者都是路径参数
        if (isPathParameter(segment1) && isPathParameter(segment2)) {
            return true;
        }
        return false;
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
        // 60% 的概率优先选择有出度（有后续依赖操作）的POST节点作为起始点，以生成更有意义的长序列
        // 40% 的概率从所有POST节点中随机选择，以确保那些独立的（出度为0）POST操作也能被测试到
        List<OperationNode> candidateNodes;

        if (random.nextDouble() < 0.6) {
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

        // ===== 第一阶段：向上追溯依赖，找到所有前置操作（递归完整导入）=====
        // 使用拓扑排序的思想，先执行没有未满足依赖的操作
        // 关键改进：完整追溯所有POST依赖链，确保资源创建顺序正确
        List<OperationNode> prerequisiteChain = findCompletePrerequisiteChain(targetNode, visited);

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

        // ===== 第二阶段：收集所有后续操作并按CRUD语义排序 =====
        // 关键改进：先收集所有候选操作，然后按照 POST -> GET -> PUT/PATCH 的顺序排序
        List<OperationNode> candidateOperations = new ArrayList<>();

        // 从前置链中的节点开始，收集所有依赖它们的操作
        for (OperationNode prereqNode : prerequisiteChain) {
            Set<DependencyEdge> incomingEdges = graph.getGraph().incomingEdgesOf(prereqNode);
            for (DependencyEdge edge : incomingEdges) {
                OperationNode consumerNode = graph.getGraph().getEdgeSource(edge);
                if (!visited.contains(consumerNode) &&
                    consumerNode.getOperation().getMethod() != HttpMethod.DELETE &&
                    !candidateOperations.contains(consumerNode)) {
                    candidateOperations.add(consumerNode);
                }
            }
        }

        // 按CRUD语义对候选操作排序：POST优先，然后GET，最后PUT/PATCH
        candidateOperations.sort((n1, n2) -> {
            int order1 = getCrudOrder(n1.getOperation().getMethod());
            int order2 = getCrudOrder(n2.getOperation().getMethod());
            if (order1 != order2) {
                return order1 - order2;
            }
            // 同类型操作按出度排序（出度大的优先）
            return graph.getGraph().outDegreeOf(n2) - graph.getGraph().outDegreeOf(n1);
        });

        // 添加排序后的候选操作，同时检查依赖是否满足
        for (OperationNode candidateNode : candidateOperations) {
            if (visited.contains(candidateNode) || queue.size() >= currentMaxDepth) {
                continue;
            }

            Operation candidateOp = candidateNode.getOperation();

            // 对于非POST操作（GET/PUT/PATCH），检查其依赖的POST是否已在序列中
            if (candidateOp.getMethod() != HttpMethod.POST) {
                if (!areDependenciesSatisfied(candidateNode, visited)) {
                    // 尝试先添加缺失的POST依赖
                    List<OperationNode> missingDeps = findMissingPostDependencies(candidateNode, visited);
                    for (OperationNode missingDep : missingDeps) {
                        if (!visited.contains(missingDep) && queue.size() < currentMaxDepth) {
                            queue.addLast(missingDep.getOperation());
                            visited.add(missingDep);
                            if (missingDep.getOperation().getMethod() == HttpMethod.POST) {
                                postOperationsInSequence.add(missingDep.getOperation());
                            }
                        }
                    }
                }
            }

            // 添加当前操作
            queue.addLast(candidateOp);
            visited.add(candidateNode);

            if (candidateOp.getMethod() == HttpMethod.POST) {
                postOperationsInSequence.add(candidateOp);
            }
        }

        // ===== 第三阶段：强制添加GET操作（确保序列中有GET）=====
        // 关键改进：如果序列中没有GET操作，从getNodes中添加相关的GET操作
        boolean hasGetInSequence = queue.stream()
                .anyMatch(op -> op.getMethod() == HttpMethod.GET);

        if (!hasGetInSequence && !getNodes.isEmpty()) {
            // 优先添加与序列中POST操作相关的GET操作
            List<OperationNode> relatedGetNodes = findRelatedGetOperations(postOperationsInSequence);

            if (!relatedGetNodes.isEmpty()) {
                // 添加至少一个相关的GET操作
                int getCountToAdd = Math.min(2, relatedGetNodes.size());
                for (int i = 0; i < getCountToAdd && queue.size() < currentMaxDepth; i++) {
                    OperationNode getNode = relatedGetNodes.get(i);
                    if (!visited.contains(getNode)) {
                        // GET操作应该在POST之后，找到合适的插入位置
                        insertGetOperationAfterRelatedPost(getNode, postOperationsInSequence);
                        visited.add(getNode);
                    }
                }
            } else {
                // 如果没有相关GET，随机添加一个GET操作到序列末尾（DELETE之前）
                OperationNode randomGetNode = getNodes.get(random.nextInt(getNodes.size()));
                if (!visited.contains(randomGetNode)) {
                    queue.addLast(randomGetNode.getOperation());
                    visited.add(randomGetNode);
                }
            }

            System.out.println("DEBUG: Added GET operations to ensure sequence has GET");
        }

        // ===== 第四阶段：最终排序验证，确保CRUD顺序正确 =====
        // 关键改进：重新检查并调整序列，确保POST在PUT之前
        reorderSequenceForCrudSemantics();

        // ===== 第五阶段：添加DELETE操作（按CRUD语义，DELETE应该在最后）=====
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

        // 打印最终序列用于调试
        System.out.println("DEBUG: Final sequence:");
        int index = 0;
        for (Operation op : queue) {
            System.out.println("  " + (index++) + ": " + op.getMethod() + " " + op.getEndpoint());
        }
    }

    /**
     * 获取HTTP方法的CRUD顺序值
     * 顺序：POST(创建) -> GET(读取) -> PUT/PATCH(更新) -> DELETE(删除)
     */
    private int getCrudOrder(HttpMethod method) {
        switch (method) {
            case POST: return 1;
            case GET: return 2;
            case PUT: return 3;
            case PATCH: return 3;
            case DELETE: return 4;
            default: return 5;
        }
    }

    /**
     * 检查节点的POST依赖是否已满足
     */
    private boolean areDependenciesSatisfied(OperationNode node, Set<OperationNode> visited) {
        Set<DependencyEdge> outgoingEdges = graph.getGraph().outgoingEdgesOf(node);
        for (DependencyEdge edge : outgoingEdges) {
            OperationNode depNode = graph.getGraph().getEdgeTarget(edge);
            if (depNode.getOperation().getMethod() == HttpMethod.POST && !visited.contains(depNode)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 查找节点缺失的POST依赖
     */
    private List<OperationNode> findMissingPostDependencies(OperationNode node, Set<OperationNode> visited) {
        List<OperationNode> missingDeps = new ArrayList<>();
        Set<OperationNode> localVisited = new HashSet<>();
        findMissingPostDepsRecursive(node, visited, localVisited, missingDeps);

        // 反转列表，使依赖在前
        Collections.reverse(missingDeps);
        return missingDeps;
    }

    private void findMissingPostDepsRecursive(OperationNode node, Set<OperationNode> globalVisited,
                                               Set<OperationNode> localVisited, List<OperationNode> result) {
        if (localVisited.contains(node)) return;
        localVisited.add(node);

        Set<DependencyEdge> outgoingEdges = graph.getGraph().outgoingEdgesOf(node);
        for (DependencyEdge edge : outgoingEdges) {
            OperationNode depNode = graph.getGraph().getEdgeTarget(edge);
            if (depNode.getOperation().getMethod() == HttpMethod.POST &&
                !globalVisited.contains(depNode) && !result.contains(depNode)) {
                // 先递归处理更深层的依赖
                findMissingPostDepsRecursive(depNode, globalVisited, localVisited, result);
                result.add(depNode);
            }
        }
    }

    /**
     * 查找与POST操作相关的GET操作
     * 相关性判断：GET操作的端点与POST操作的端点匹配
     */
    private List<OperationNode> findRelatedGetOperations(List<Operation> postOperations) {
        List<OperationNode> relatedGets = new ArrayList<>();

        for (Operation postOp : postOperations) {
            String postEndpoint = postOp.getEndpoint();

            for (OperationNode getNode : getNodes) {
                String getEndpoint = getNode.getOperation().getEndpoint();

                // GET /resources/{id} 与 POST /resources 相关
                // GET /resources 与 POST /resources 相关
                if (isGetRelatedToPost(getEndpoint, postEndpoint) && !relatedGets.contains(getNode)) {
                    relatedGets.add(getNode);
                }
            }
        }

        return relatedGets;
    }
    
    /**
     * 判断GET端点是否与POST端点相关
     */
    private boolean isGetRelatedToPost(String getEndpoint, String postEndpoint) {
        // 标准化端点
        String normalizedGet = getEndpoint.endsWith("/") ? getEndpoint.substring(0, getEndpoint.length() - 1) : getEndpoint;
        String normalizedPost = postEndpoint.endsWith("/") ? postEndpoint.substring(0, postEndpoint.length() - 1) : postEndpoint;

        // 完全匹配：GET /resources 与 POST /resources
        if (normalizedGet.equals(normalizedPost)) {
            return true;
        }

        // GET端点是POST端点加上路径参数：GET /resources/{id} 与 POST /resources
        if (normalizedGet.startsWith(normalizedPost + "/")) {
            String suffix = normalizedGet.substring(normalizedPost.length() + 1);
            // 检查后缀是否只是一个路径参数
            if (isPathParameter(suffix) || !suffix.contains("/")) {
                return true;
            }
        }

        return false;
    }
    
    /**
     * 在相关POST操作之后插入GET操作
     */
    private void insertGetOperationAfterRelatedPost(OperationNode getNode, List<Operation> postOperations) {
        String getEndpoint = getNode.getOperation().getEndpoint();
        Operation relatedPost = null;
        int insertIndex = queue.size(); // 默认插入到末尾

        // 找到最相关的POST操作
        for (Operation postOp : postOperations) {
            if (isGetRelatedToPost(getEndpoint, postOp.getEndpoint())) {
                relatedPost = postOp;
                break;
            }
        }

        if (relatedPost != null) {
            // 在相关POST之后找到合适的插入位置
            List<Operation> tempList = new ArrayList<>(queue);
            for (int i = 0; i < tempList.size(); i++) {
                if (tempList.get(i).equals(relatedPost)) {
                    // 在POST之后、下一个POST或DELETE之前插入
                    insertIndex = i + 1;
                    for (int j = i + 1; j < tempList.size(); j++) {
                        HttpMethod method = tempList.get(j).getMethod();
                        if (method == HttpMethod.POST || method == HttpMethod.DELETE) {
                            insertIndex = j;
                            break;
                        }
                        insertIndex = j + 1;
                    }
                    break;
                }
            }
        }

        // 插入GET操作
        List<Operation> tempList = new ArrayList<>(queue);
        tempList.add(Math.min(insertIndex, tempList.size()), getNode.getOperation());
        queue.clear();
        queue.addAll(tempList);
    }

    /**
     * 重新排序序列，确保CRUD语义正确
     * 主要解决：POST应该在依赖它的PUT之前
     */
    private void reorderSequenceForCrudSemantics() {
        List<Operation> tempList = new ArrayList<>(queue);
        boolean changed = true;
        int maxIterations = tempList.size() * 2; // 防止无限循环
        int iterations = 0;

        while (changed && iterations < maxIterations) {
            changed = false;
            iterations++;

            for (int i = 0; i < tempList.size(); i++) {
                Operation currentOp = tempList.get(i);

                // 对于PUT/PATCH操作，检查其依赖的POST是否在它之前
                if (currentOp.getMethod() == HttpMethod.PUT || currentOp.getMethod() == HttpMethod.PATCH) {
                    OperationNode currentNode = findNodeByOperation(currentOp);
                    if (currentNode != null) {
                        // 查找依赖的POST操作
                        Set<DependencyEdge> outgoingEdges = graph.getGraph().outgoingEdgesOf(currentNode);
                        for (DependencyEdge edge : outgoingEdges) {
                            OperationNode depNode = graph.getGraph().getEdgeTarget(edge);
                            if (depNode.getOperation().getMethod() == HttpMethod.POST) {
                                Operation depPost = depNode.getOperation();
                                int depIndex = tempList.indexOf(depPost);

                                // 如果依赖的POST在PUT之后，将POST移动到PUT之前
                                if (depIndex > i) {
                                    tempList.remove(depIndex);
                                    tempList.add(i, depPost);
                                    changed = true;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (changed) break; // 重新开始检查
            }
        }

        // 更新队列
        queue.clear();
        queue.addAll(tempList);
    }

    /**
     * 根据Operation查找对应的OperationNode
     */
    private OperationNode findNodeByOperation(Operation operation) {
        for (OperationNode node : graph.getGraph().vertexSet()) {
            if (node.getOperation().equals(operation)) {
                return node;
            }
        }
        return null;
    }

    /**
     * 完整追溯前置依赖链，确保所有POST依赖都被包含
     * 关键改进：递归追溯所有层级的POST依赖，而不是只追溯有限深度
     */
    private List<OperationNode> findCompletePrerequisiteChain(OperationNode targetNode, Set<OperationNode> globalVisited) {
        LinkedHashSet<OperationNode> orderedResult = new LinkedHashSet<>();
        Set<OperationNode> localVisited = new HashSet<>();

        // 递归拓扑排序，完整追溯所有POST依赖
        completeTopologicalSort(targetNode, localVisited, globalVisited, orderedResult);

        List<OperationNode> result = new ArrayList<>(orderedResult);

        // 动态限制前置链的长度，但确保关键依赖不被截断
        Random random = new Random();
        int maxPrerequisites = MIN_PREREQUISITE_CHAIN + random.nextInt(MAX_PREREQUISITE_CHAIN - MIN_PREREQUISITE_CHAIN + 1);
        
        // 如果链太长，优先保留目标节点及其直接依赖
        if (result.size() > maxPrerequisites) {
            // 确保至少保留目标节点及其直接POST依赖
            int minRequired = countDirectPostDependencies(targetNode) + 1;
            int actualMax = Math.max(maxPrerequisites, minRequired);

            if (result.size() > actualMax) {
                result = result.subList(result.size() - actualMax, result.size());
                System.out.println("DEBUG: Prerequisite chain truncated to " + actualMax + " nodes (min required: " + minRequired + ")");
            }
        }

        return result;
    }

    /**
     * 计算节点的直接POST依赖数量
     */
    private int countDirectPostDependencies(OperationNode node) {
        int count = 0;
        Set<DependencyEdge> outgoingEdges = graph.getGraph().outgoingEdgesOf(node);
        for (DependencyEdge edge : outgoingEdges) {
            OperationNode depNode = graph.getGraph().getEdgeTarget(edge);
            if (depNode.getOperation().getMethod() == HttpMethod.POST) {
                count++;
            }
        }
        return count;
    }

    /**
     * 完整的拓扑排序 - 追溯所有POST依赖
     */
    private void completeTopologicalSort(OperationNode node, Set<OperationNode> localVisited,
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

            // 递归处理所有POST类型的依赖节点
            if (dependencyNode.getOperation().getMethod() == HttpMethod.POST) {
                completeTopologicalSort(dependencyNode, localVisited, globalVisited, result);
            }
        }

        // 后序添加：所有依赖处理完毕后，再添加当前节点
        if (node.getOperation().getMethod() == HttpMethod.POST) {
            result.add(node);
        }
    }


    /**
     * 添加对应的DELETE操作（按LIFO原则：后创建的先删除）
     */
    private void addCorrespondingDeletes(List<Operation> postOperations, List<Operation> deleteList, int maxCount) {
        // 逆序遍历POST操作，添加对应的DELETE
        List<Operation> reversedPosts = new ArrayList<>(postOperations);
        Collections.reverse(reversedPosts);

        for (Operation postOp : reversedPosts) {
            if (deleteList.size() >= maxCount) {
                break;
            }
            Operation correspondingDelete = postToDeleteMap.get(postOp);
            if (correspondingDelete != null && !deleteList.contains(correspondingDelete)) {
                deleteList.add(correspondingDelete);
            }
        }
    }

    /**
     * 随机添加DELETE操作（探索模式）
     */
    private void addRandomDeletes(List<Operation> deleteList, int count, Random random) {
        if (deleteNodes.isEmpty()) {
            return;
        }

        List<OperationNode> shuffledDeleteNodes = new ArrayList<>(deleteNodes);
        Collections.shuffle(shuffledDeleteNodes, random);

        for (OperationNode deleteNode : shuffledDeleteNodes) {
            if (deleteList.size() >= count) {
                break;
            }
            Operation deleteOp = deleteNode.getOperation();
            if (!deleteList.contains(deleteOp)) {
                deleteList.add(deleteOp);
            }
        }
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
