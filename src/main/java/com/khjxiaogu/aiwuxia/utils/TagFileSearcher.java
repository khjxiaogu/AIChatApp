package com.khjxiaogu.aiwuxia.utils;
import java.util.*;

public class TagFileSearcher {

    static class FileInfo {
        String name;
        Set<String> tags;

        FileInfo(String name, Set<String> tags) {
            this.name = name;
            this.tags = tags;
        }
    }

    /**
     * 根据用户选择的标签，返回最佳匹配的文件名或文件名列表。
     *
     * @param files        所有文件及其标签
     * @param requiredTags 用户选择的标签集合
     * @return 若存在单文件包含所有所需标签，返回该文件名(String)；
     *         否则返回满足条件的最短文件列表(List<String>)
     */
    public static Object search(List<FileInfo> files, Set<String> requiredTags) {
        // 1. 先查找是否存在包含所有 requiredTags 的单个文件
        List<FileInfo> fullMatchFiles = new ArrayList<>();
        for (FileInfo file : files) {
            if (file.tags.containsAll(requiredTags)) {
                fullMatchFiles.add(file);
            }
        }

        if (!fullMatchFiles.isEmpty()) {
            // 找额外标签最少的文件
            int minExtra = fullMatchFiles.stream()
                    .mapToInt(f -> f.tags.size() - requiredTags.size())
                    .min()
                    .orElse(0);

            // 筛选出额外标签数等于最小值的文件，并按名称排序
            return fullMatchFiles.stream()
                    .filter(f -> f.tags.size() - requiredTags.size() == minExtra)
                    .map(f -> f.name)
                    .sorted()
                    .findFirst()
                    .orElse(null);  // 实际不会为 null
        }

        // 2. 没有单文件能覆盖全部所需标签，进行组合查找
        // 过滤掉与所需标签完全无交集的“无用文件”
        List<FileInfo> candidates = new ArrayList<>();
        for (FileInfo file : files) {
            Set<String> intersection = new HashSet<>(file.tags);
            intersection.retainAll(requiredTags);
            if (!intersection.isEmpty()) {
                candidates.add(file);
            }
        }

        if (candidates.isEmpty()) {
            return Collections.emptyList(); // 无法覆盖任何所需标签
        }

        // 方便通过文件名查找 FileInfo
        Map<String, FileInfo> nameToFile = new HashMap<>();
        for (FileInfo f : files) {
            nameToFile.put(f.name, f);
        }

        // 迭代加深搜索，寻找最短的不相交覆盖列表
        List<List<String>> bestSolutions = null;
        for (int depth = 1; depth <= candidates.size(); depth++) {
            List<List<String>> solutions = new ArrayList<>();
            search(depth, 0, candidates, new HashSet<>(requiredTags),
                   new HashSet<>(), new ArrayList<>(), solutions);
            if (!solutions.isEmpty()) {
                bestSolutions = solutions;
                break;
            }
        }

        if (bestSolutions == null) {
            return Collections.emptyList(); // 理论上不应出现，除非标签无法完全覆盖
        }

        // 在长度最短的解中，按第二准则选择最优解：
        //   1. 总标签数最大
        //   2. 相同时按列表文件名的字典序
        bestSolutions.sort((list1, list2) -> {
            int sum1 = list1.stream().mapToInt(name -> nameToFile.get(name).tags.size()).sum();
            int sum2 = list2.stream().mapToInt(name -> nameToFile.get(name).tags.size()).sum();
            if (sum1 != sum2) {
                return Integer.compare(sum1, sum2); // 总标签数降序
            }
            // 字典序比较（先排序各列表）
            List<String> sorted1 = new ArrayList<>(list1);
            List<String> sorted2 = new ArrayList<>(list2);
            Collections.sort(sorted1);
            Collections.sort(sorted2);
            for (int i = 0; i < Math.min(sorted1.size(), sorted2.size()); i++) {
                int cmp = sorted1.get(i).compareTo(sorted2.get(i));
                if (cmp != 0) return cmp;
            }
            return Integer.compare(sorted1.size(), sorted2.size());
        });

        return bestSolutions.get(0);
    }

    /**
     * 回溯搜索：选择互不相交的文件，覆盖 remaining 中的所需标签。
     *
     * @param maxDepth  当前搜索深度（文件数量上限）
     * @param start     从 candidates 的哪个下标开始选取（避免重复组合）
     * @param cands     候选文件列表
     * @param remaining 还需要覆盖的所需标签
     * @param usedTags  当前已选文件的所有标签（用于保证互不相交）
     * @param current   当前已选文件的名称列表
     * @param solutions 存放所有找到的完整解
     */
    private static void search(int maxDepth, int start, List<FileInfo> cands,
                               Set<String> remaining, Set<String> usedTags,
                               List<String> current, List<List<String>> solutions) {
        if (current.size() == maxDepth) {
            if (remaining.isEmpty()) {
                solutions.add(new ArrayList<>(current));
            }
            return;
        }

        // 简单剪枝：若剩余需求标签已为0，但深度未满，可提前终止（因为我们追求最短，深度必然刚好满足）
        // 实际上迭代加深已经控制深度，但这里可稍作剪枝：
        // 若即使把剩下所有文件都选上也不够覆盖 remaining，可以退出
        // 为简化，不做复杂剪枝。

        for (int i = start; i < cands.size(); i++) {
            FileInfo f = cands.get(i);

            // 文件不能与已选文件的标签有交集
            if (!Collections.disjoint(f.tags, usedTags)) {
                continue;
            }

            // 文件必须包含至少一个还需要的标签，否则没有贡献
            Set<String> contribution = new HashSet<>(f.tags);
            contribution.retainAll(remaining);
            if (contribution.isEmpty()) {
                continue;
            }

            // 选择该文件
            current.add(f.name);
            Set<String> newRemaining = new HashSet<>(remaining);
            newRemaining.removeAll(f.tags);
            Set<String> newUsed = new HashSet<>(usedTags);
            newUsed.addAll(f.tags);

            search(maxDepth, i + 1, cands, newRemaining, newUsed, current, solutions);

            current.remove(current.size() - 1); // 回溯
        }
    }

    // ---------- 简单测试 ----------
    public static void main(String[] args) {
        List<FileInfo> files = Arrays.asList(
            new FileInfo("A", new HashSet<>(Arrays.asList("java", "tutorial"))),
            new FileInfo("B", new HashSet<>(Arrays.asList("java", "advanced", "concurrency"))),
            new FileInfo("C", new HashSet<>(Arrays.asList("python", "tutorial"))),
            new FileInfo("D", new HashSet<>(Arrays.asList("java", "python", "guide"))),
            new FileInfo("E", new HashSet<>(Arrays.asList("advanced", "concurrency")))
        );

        // 测试1: 单文件包含所有所需标签
        System.out.println("Test 1: " + search(files, new HashSet<>(Arrays.asList("java", "advanced"))));
        // 预期: "B" (因为 B 包含 java,advanced,concurrency, 额外标签数=1; 比 D 的额外标签数少)

        // 测试2: 需要组合才能覆盖
        System.out.println("Test 2: " + search(files, new HashSet<>(Arrays.asList("java", "python"))));
        // 预期: ["D"] ? D 包含 java,python,guide 所以单文件覆盖，返回 D
        // 再加一个测试真的需要组合的情况
        files = Arrays.asList(
            new FileInfo("X", new HashSet<>(Arrays.asList("a", "b"))),
            new FileInfo("Y", new HashSet<>(Arrays.asList("c"))),
            new FileInfo("Z", new HashSet<>(Arrays.asList("b", "c"))),
            new FileInfo("W", new HashSet<>(Arrays.asList("a", "d")))
        );
        System.out.println("Test 3: " + search(files, new HashSet<>(Arrays.asList("a","b", "c"))));
        // 没有单文件包含 a 和 c. 最短列表: [X,Y] 或 [Z,W] 都长度2.
        // X:{a,b}, Y:{c,d} -> 不相交且覆盖 a,c, 总标签数4.
        // Z:{b,c}, W:{a,d} -> 不相交覆盖 a,c, 总标签数4. 两者总标签数相同，按名字排序:
        // [W, Z] 和 [X, Y], 按字典序 [W, Z] < [X, Y]? 排序后 W,Z 为 [W, Z], X,Y 为 [X, Y], "W" < "X" -> 选 [W, Z].
        System.out.println("Expected: [W, Z] or [Z, W]? The returned list should be one of them.");
    }
}