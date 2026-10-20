package com.khjxiaogu.aiwuxia.mcp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.khjxiaogu.aiwuxia.llm.ToolData;
import com.khjxiaogu.aiwuxia.llm.ToolProvider;
import com.khjxiaogu.aiwuxia.llm.toolcall.ToolCallPresenter;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;
import com.khjxiaogu.aiwuxia.utils.FileUtil;
import com.khjxiaogu.webserver.loging.SimpleLogger;

/**
 * 角色设定查询 MCP 模块。
 * <p>
 * 全部数据来自人类可读的索引文件 {@code character-list.txt}（文件名见
 * {@link ConfigPaths#SDXL_CHARACTER_LIST}），不再读取或依赖 {@code charaset.txt}：
 * </p>
 * <pre>
 * [work] 作品名
 * [public]
 * 校服样式：……
 * [character] 角色名
 * 别名：简称, 拼音
 * 性别：女
 * 外貌：……
 * </pre>
 * <p>
 * 索引文件同时承担“角色列表”和“角色设定数据”两个职责：{@code [public]} 是该作品的公共设定，
 * {@code [character]} 下逐行是角色的规范化设定，{@code 别名：} 只用于模糊搜索、不会出现在设定正文里。
 * 文件在每次调用时重新读取，修改后立即生效，无需重启。
 * </p>
 * <p>
 * 文件位置同时兼容新目录 {@code config/sdxl/agent} 与旧目录 {@code save/painteragent}。
 * </p>
 * <p>
 * 本模块提供三个工具：
 * </p>
 * <ul>
 *   <li>{@code list_characters}：列出全部作品与角色；</li>
 *   <li>{@code search_characters}：按角色名、别名或作品名模糊搜索；</li>
 *   <li>{@code get_character_setting}：获取角色完整设定，并同时返回所属作品的公共设定。</li>
 * </ul>
 */
public class CharacterSettingMcp implements ToolProvider {

    /** 角色设定索引文件名。 */
    public static final String CONFIG_NAME = ConfigPaths.SDXL_CHARACTER_LIST;
    /** 搜索最多返回的候选数。 */
    private static final int MAX_SEARCH_RESULTS = 10;
    /** 归一化时去掉的空白与标点（中英文）。 */
    private static final Pattern NORMALIZE_STRIP = Pattern
            .compile("[\\s\\p{Punct}，。、；：！？（）【】《》〈〉“”‘’·—…～]+");

    private static final SimpleLogger logger = new SimpleLogger("角色设定");

    /** 角色设定数据目录。 */
    private final File dataFolder;

    /**
     * 创建角色设定工具集。
     *
     * @param dataFolder 数据目录（通常是 {@code save}），不能为 null
     */
    public CharacterSettingMcp(File dataFolder) {
        if (dataFolder == null) {
            throw new IllegalArgumentException("dataFolder 不能为 null");
        }
        this.dataFolder = dataFolder;
    }

    /**
     * 角色设定查询工具：列表、搜索、获取完整设定共用同一份索引数据。
     */
    @Override
    public Collection<ToolData> getTools() {
        final File folder = dataFolder;
        List<ToolData> tools = new ArrayList<>();
        tools.add(new ToolData.Builder("list_characters",
                "列出角色设定库中的全部作品与角色（含别名）。想知道有哪些角色可以查询时先调用它。")
                .presenter(ToolCallPresenter.ofName("列出角色"))
                .tool(arguments -> list(folder))
                .build());
        tools.add(new ToolData.Builder("search_characters",
                "按角色名字、别名（支持拼音、外号）或作品名模糊搜索角色，返回匹配的角色及所属作品。"
                        + "需要确认某个角色是否存在、同名角色属于哪部作品时使用；拿到完整角色名后再用 get_character_setting 获取设定。")
                .putParam("query", "角色名字、别名（拼音/外号）或作品名，支持部分匹配。")
                .presenter(ToolCallPresenter.ofArgument("搜索角色", "query"))
                .tool(arguments -> runSearch(folder, arguments))
                .build());
        tools.add(new ToolData.Builder("get_character_setting",
                "获取指定角色的完整设定，并同时返回其所属作品的公共设定（校服、世界观等）。"
                        + "character 支持模糊匹配与别名；同名角色（如归海枫）需要用 work 指定作品。")
                .putParam("character", "角色名字，支持模糊匹配与别名，例如“枫茜”或“fengxi”。")
                .putParam("work", "作品名，用于区分同名角色（例如“雾之本境”“雪之本境”）；只有一个匹配时填空字符串。")
                .presenter(ToolCallPresenter.ofArgument("获取角色设定", "character"))
                .tool(arguments -> runSetting(folder, arguments))
                .build());
        return tools;
    }

    /**
     * 角色设定索引文件的位置。
     * <p>
     * 依次在新目录 {@code config/sdxl/agent} 与旧目录 {@code save/painteragent} 中查找，
     * 都没有时返回新位置，便于错误信息直接指出应该放文件的地方。
     * </p>
     *
     * @param dataFolder 数据目录
     * @return 索引文件路径
     */
    public static File configFile(File dataFolder) {
        return locateAgentFile(dataFolder, CONFIG_NAME);
    }

    /** 在新旧 agent 目录中查找指定文件；都不存在时返回新位置的路径。 */
    private static File locateAgentFile(File dataFolder, String fileName) {
        List<File> directories = ConfigPaths.sdxlAgentCandidates(dataFolder);
        for (File directory : directories) {
            File file = new File(directory, fileName);
            if (file.isFile()) {
                return file;
            }
        }
        return new File(directories.get(0), fileName);
    }

    /** 索引文件缺失时的提示：列出所有查找过的位置。 */
    private static String configNotFoundMessage(File dataFolder) {
        StringBuilder sb = new StringBuilder("未找到角色设定索引，以下位置均不存在：");
        for (File directory : ConfigPaths.sdxlAgentCandidates(dataFolder)) {
            sb.append("\n  ").append(new File(directory, CONFIG_NAME).getPath());
        }
        sb.append("\n请创建该文件，文件内注释说明了格式。");
        return sb.toString();
    }

    /**
     * 列出全部作品与角色（按索引文件中的顺序）。
     *
     * @param dataFolder 数据目录
     * @return 给模型阅读的文本；配置有误时返回中文错误说明
     */
    public static String list(File dataFolder) {
        try {
            List<Work> works = loadIndex(dataFolder);
            int total = 0;
            StringBuilder sb = new StringBuilder();
            sb.append("角色设定库（索引：").append(configFile(dataFolder).getPath()).append("）\n");
            for (Work work : works) {
                total += work.characters.size();
                sb.append("\n【").append(work.name).append("】").append(work.characters.size()).append(" 人\n");
                for (CharacterInfo character : work.characters) {
                    sb.append("- ").append(character.name);
                    if (!character.aliases.isEmpty()) {
                        sb.append("（别名：").append(join(character.aliases, "、")).append("）");
                    }
                    sb.append('\n');
                }
            }
            sb.append("\n共 ").append(works.size()).append(" 部作品、").append(total).append(" 个角色。");
            return sb.toString();
        } catch (IndexException ex) {
            return ex.getMessage();
        } catch (IOException ex) {
            logger.printStackTrace(ex);
            return "读取角色设定索引失败：" + ex.getMessage();
        }
    }

    /**
     * 模糊搜索角色。
     *
     * @param dataFolder 数据目录
     * @param query      角色名、别名或作品名
     * @return 候选列表；没有匹配或参数为空时返回提示
     */
    public static String search(File dataFolder, String query) {
        try {
            List<Work> works = loadIndex(dataFolder);
            String normalized = normalize(query);
            if (normalized.isEmpty()) {
                return "参数格式错误：query 不能为空。";
            }
            List<Match> matches = match(works, normalized);
            if (matches.isEmpty()) {
                return "没有找到与「" + query.trim() + "」匹配的角色。\n"
                        + "可以换用角色全名、简称或拼音，或先调用 list_characters 查看全部角色。";
            }
            List<Match> shown = matches.size() > MAX_SEARCH_RESULTS ? matches.subList(0, MAX_SEARCH_RESULTS) : matches;
            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(matches.size()).append(" 个匹配「").append(query.trim()).append("」的角色");
            if (matches.size() > shown.size()) {
                sb.append("（只显示前 ").append(shown.size()).append(" 个）");
            }
            sb.append("：\n");
            int index = 0;
            for (Match match : shown) {
                sb.append(++index).append(". ").append(match.character.name)
                        .append("（").append(match.work.name).append("）");
                if (!match.character.aliases.isEmpty()) {
                    sb.append(" 别名：").append(join(match.character.aliases, "、"));
                }
                sb.append('\n');
            }
            sb.append("\n提示：获取完整设定请调用 get_character_setting（character 传完整角色名；同名角色再传 work）。");
            return sb.toString();
        } catch (IndexException ex) {
            return ex.getMessage();
        } catch (IOException ex) {
            logger.printStackTrace(ex);
            return "读取角色设定索引失败：" + ex.getMessage();
        }
    }

    /**
     * 获取角色设定，并附带其所属作品的公共设定。
     *
     * @param dataFolder    数据目录
     * @param characterName 角色名（支持模糊匹配与别名）
     * @param workName      作品名，可为空；用于区分同名角色
     * @return 角色设定与作品公共设定；匹配到多个角色时返回候选列表
     */
    public static String setting(File dataFolder, String characterName, String workName) {
        try {
            List<Work> works = loadIndex(dataFolder);
            String normalized = normalize(characterName);
            if (normalized.isEmpty()) {
                return "参数格式错误：character 不能为空。";
            }
            List<Match> matches = match(works, normalized);
            String normalizedWork = normalize(workName);
            if (!normalizedWork.isEmpty()) {
                List<Match> filtered = new ArrayList<>();
                for (Match match : matches) {
                    if (workMatches(normalizedWork, match.work)) {
                        filtered.add(match);
                    }
                }
                if (filtered.isEmpty()) {
                    if (matches.isEmpty()) {
                        return "没有找到与「" + characterName.trim() + "」匹配的角色。";
                    }
                    return "在作品「" + workName.trim() + "」中没有找到与「" + characterName.trim() + "」匹配的角色。\n"
                            + "匹配到的角色属于：" + worksOf(matches) + "\n"
                            + "可以先用 search_characters 确认候选。";
                }
                matches = filtered;
            }
            if (matches.isEmpty()) {
                return "没有找到与「" + characterName.trim() + "」匹配的角色。\n"
                        + "可以换用全名、简称或拼音，或先调用 list_characters 查看全部角色。";
            }
            // 优先精确同名，避免“姚”这种短词把多个角色都算成同分候选
            List<Match> exact = new ArrayList<>();
            for (Match match : matches) {
                if (normalize(match.character.name).equals(normalized)) {
                    exact.add(match);
                }
            }
            if (!exact.isEmpty()) {
                matches = exact;
            }
            if (matches.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("「").append(characterName.trim()).append("」匹配到 ").append(matches.size())
                        .append(" 个角色，请提供更完整的名字，或在 work 参数中指定作品：\n");
                int index = 0;
                for (Match match : matches) {
                    sb.append(++index).append(". ").append(match.character.name)
                            .append("（").append(match.work.name).append("）");
                    if (!match.character.aliases.isEmpty()) {
                        sb.append(" 别名：").append(join(match.character.aliases, "、"));
                    }
                    sb.append('\n');
                }
                return sb.toString();
            }
            Match match = matches.get(0);
            return render(match.work, match.character);
        } catch (IndexException ex) {
            return ex.getMessage();
        } catch (IOException ex) {
            logger.printStackTrace(ex);
            return "读取角色设定索引失败：" + ex.getMessage();
        }
    }

    // ==================== 工具入参 ====================

    private static String runSearch(File dataFolder, String arguments) {
        JsonObject args = parseArguments(arguments);
        if (args == null) {
            return "参数格式错误：需要 JSON 对象，例如 {\"query\":\"枫茜\"}。";
        }
        String query = string(args, "query");
        if (query == null || query.trim().isEmpty()) {
            return "参数格式错误：query 不能为空。";
        }
        return search(dataFolder, query);
    }

    private static String runSetting(File dataFolder, String arguments) {
        JsonObject args = parseArguments(arguments);
        if (args == null) {
            return "参数格式错误：需要 JSON 对象，例如 {\"character\":\"姚枫茜\",\"work\":\"\"}。";
        }
        String character = string(args, "character");
        if (character == null || character.trim().isEmpty()) {
            return "参数格式错误：character 不能为空。";
        }
        return setting(dataFolder, character, string(args, "work"));
    }

    private static JsonObject parseArguments(String arguments) {
        try {
            JsonElement element = JsonParser.parseString(arguments == null ? "" : arguments);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    // ==================== 索引读取 ====================

    /**
     * 读取并解析角色设定索引。
     * <p>
     * 语法见 {@code character-list.txt} 文件头注释：{@code [work]} 开始作品，{@code [public]}
     * 开始作品公共设定，{@code [character]} 开始角色；其余行按原文保存。
     * </p>
     */
    private static List<Work> loadIndex(File dataFolder) throws IOException, IndexException {
        File file = configFile(dataFolder);
        if (file == null || !file.isFile()) {
            throw new IndexException(configNotFoundMessage(dataFolder));
        }
        String[] lines = stripBom(FileUtil.readString(file)).split("\\R", -1);
        List<Work> works = new ArrayList<>();
        Work currentWork = null;
        CharacterInfo currentCharacter = null;
        boolean inPublic = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNumber = i + 1;
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            if (line.regionMatches(true, 0, "[work]", 0, 6)) {
                String name = line.substring(6).trim();
                if (name.isEmpty()) {
                    throw new IndexException("第 " + lineNumber + " 行：[work] 后面缺少作品名。");
                }
                currentWork = new Work(name);
                works.add(currentWork);
                currentCharacter = null;
                inPublic = false;
                continue;
            }
            if (line.regionMatches(true, 0, "[public]", 0, 8)) {
                if (currentWork == null) {
                    throw new IndexException("第 " + lineNumber + " 行：[public] 必须写在 [work] 之后。");
                }
                currentCharacter = null;
                inPublic = true;
                continue;
            }
            if (line.regionMatches(true, 0, "[character]", 0, 11)) {
                if (currentWork == null) {
                    throw new IndexException("第 " + lineNumber + " 行：[character] 必须写在 [work] 之后。");
                }
                String name = line.substring(11).trim();
                if (name.isEmpty()) {
                    throw new IndexException("第 " + lineNumber + " 行：[character] 后面缺少角色名。");
                }
                currentCharacter = new CharacterInfo(name);
                currentWork.characters.add(currentCharacter);
                inPublic = false;
                continue;
            }
            if (line.startsWith("[")) {
                throw new IndexException("第 " + lineNumber + " 行：无法识别的配置项 " + line
                        + "，只支持 [work]、[public] 与 [character]。");
            }
            if (currentWork == null) {
                throw new IndexException("第 " + lineNumber + " 行：设定必须写在 [work] 之后。");
            }
            if (inPublic) {
                currentWork.publicLines.add(line);
            } else if (currentCharacter != null) {
                if (isAliasLine(line)) {
                    parseAliases(line, currentCharacter.aliases);
                } else {
                    currentCharacter.lines.add(line);
                }
            } else {
                throw new IndexException("第 " + lineNumber + " 行：设定必须写在 [public] 或 [character] 之后。");
            }
        }
        if (works.isEmpty()) {
            throw new IndexException("角色设定索引为空：" + file.getPath());
        }
        return works;
    }

    private static boolean isAliasLine(String line) {
        return line.startsWith("别名：") || line.startsWith("别名:");
    }

    private static void parseAliases(String line, List<String> aliases) {
        for (String alias : line.substring(3).split("[,，、]")) {
            String trimmed = alias.trim();
            if (!trimmed.isEmpty() && !aliases.contains(trimmed)) {
                aliases.add(trimmed);
            }
        }
    }

    /** 拼装角色设定 + 作品公共设定。 */
    private static String render(Work work, CharacterInfo character) {
        if (character.lines.isEmpty()) {
            return "角色「" + character.name + "」在 " + CONFIG_NAME + " 中没有配置设定。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(character.name).append('\n');
        sb.append("作品：").append(work.name).append('\n');
        if (!character.aliases.isEmpty()) {
            sb.append("别名：").append(join(character.aliases, "、")).append('\n');
        }
        sb.append("\n=== 角色设定 ===\n").append(join(character.lines, "\n"));
        sb.append("\n\n=== 作品公共设定（").append(work.name).append("） ===\n");
        sb.append(work.publicLines.isEmpty() ? "（该作品没有单独的公共设定）" : join(work.publicLines, "\n"));
        return sb.toString();
    }

    // ==================== 模糊匹配 ====================

    /** 对全部角色计算匹配分数，按分数从高到低排序。 */
    private static List<Match> match(List<Work> works, String query) {
        List<Match> result = new ArrayList<>();
        for (Work work : works) {
            for (CharacterInfo character : work.characters) {
                int best = scoreName(query, normalize(character.name));
                for (String alias : character.aliases) {
                    int aliasScore = scoreAlias(query, normalize(alias)) - 2;
                    if (aliasScore > best) {
                        best = aliasScore;
                    }
                }
                // 单字查询不参与作品名匹配，避免“枫”把整部《真恋~寄语枫秋~》的角色都算进来
                int workScore = query.length() >= 2 ? Math.min(45, scoreWork(query, normalize(work.name))) : 0;
                if (workScore > best) {
                    best = workScore;
                }
                if (best > 0) {
                    result.add(new Match(work, character, best));
                }
            }
        }
        Collections.sort(result, (left, right) -> {
            if (left.score != right.score) {
                return Integer.compare(right.score, left.score);
            }
            int byName = left.character.name.compareTo(right.character.name);
            return byName != 0 ? byName : left.work.name.compareTo(right.work.name);
        });
        return result;
    }

    /**
     * 角色名匹配：包含匹配优先；包含匹配不到时，中文名允许一个字的误差（如“姚风茜”），
     * 拉丁字母名要求更高的相似度，避免拼音之间互相误匹配。
     */
    private static int scoreName(String query, String target) {
        int base = scoreContains(query, target);
        if (base > 0) {
            return base;
        }
        double similarity = similarity(query, target);
        double threshold = containsCjk(target) ? 0.66 : 0.8;
        return similarity >= threshold ? (int) Math.round(similarity * 56) : 0;
    }

    /**
     * 别名匹配：只做包含匹配，不做编辑距离。
     * <p>
     * 拼音别名之间往往只差一两个字母（如 fengxi / fengyi），用编辑距离会把不同角色混在一起，
     * 因此别名只接受完全一致、前缀或包含。
     * </p>
     */
    private static int scoreAlias(String query, String target) {
        return scoreContains(query, target);
    }

    /** 作品名匹配：只做包含匹配，避免“雪之本境”和“雾之本境”因一字之差互相命中。 */
    private static int scoreWork(String query, String target) {
        return scoreContains(query, target);
    }

    /** 完全一致、前缀、包含、反向包含（查询比目标长）四种基础匹配。 */
    private static int scoreContains(String query, String target) {
        if (query.isEmpty() || target.isEmpty()) {
            return 0;
        }
        if (target.equals(query)) {
            return 100;
        }
        if (target.startsWith(query)) {
            return 84;
        }
        if (target.contains(query)) {
            return 72;
        }
        if (query.contains(target) && target.length() >= 2) {
            return 64;
        }
        return 0;
    }

    /** 作品名匹配（用于 work 参数过滤）：完全一致或互相包含。 */
    private static boolean workMatches(String query, Work work) {
        return matchesText(query, normalize(work.name));
    }

    private static boolean matchesText(String query, String target) {
        if (query.isEmpty() || target.isEmpty()) {
            return false;
        }
        return target.equals(query) || target.contains(query) || query.contains(target);
    }

    /** 是否包含中日韩统一表意文字（用于决定编辑距离的宽松程度）。 */
    private static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= '\u4E00' && ch <= '\u9FFF') {
                return true;
            }
        }
        return false;
    }

    /** 归一化：去首尾空白、转小写、去掉空白与常见中英文标点。 */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return NORMALIZE_STRIP.matcher(text.trim().toLowerCase(Locale.ROOT)).replaceAll("");
    }

    /** 去掉部分编辑器写入的 UTF-8 BOM。 */
    private static String stripBom(String text) {
        if (text == null) {
            return "";
        }
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** 1 - 编辑距离 / 较长串长度。 */
    private static double similarity(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int max = Math.max(left.length(), right.length());
        return 1.0 - (double) levenshtein(left, right) / max;
    }

    /** 编辑距离（滚动数组，Java 8 兼容）。 */
    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    /** 候选涉及的作品（去重后拼接）。 */
    private static String worksOf(List<Match> matches) {
        LinkedHashSet<String> works = new LinkedHashSet<>();
        for (Match match : matches) {
            works.add(match.work.name);
        }
        return join(new ArrayList<>(works), "、");
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(delimiter);
            }
            sb.append(value);
        }
        return sb.toString();
    }

    // ==================== 数据模型 ====================

    /** 一部作品及其公共设定、角色列表。 */
    private static final class Work {
        private final String name;
        private final List<String> publicLines = new ArrayList<>();
        private final List<CharacterInfo> characters = new ArrayList<>();

        private Work(String name) {
            this.name = name;
        }
    }

    /** 一个角色及其别名、规范化设定。 */
    private static final class CharacterInfo {
        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();

        private CharacterInfo(String name) {
            this.name = name;
        }
    }

    /** 模糊匹配结果。 */
    private static final class Match {
        private final Work work;
        private final CharacterInfo character;
        private final int score;

        private Match(Work work, CharacterInfo character, int score) {
            this.work = work;
            this.character = character;
            this.score = score;
        }
    }

    /** 索引文件不可用时的可读异常。 */
    private static final class IndexException extends Exception {
        private static final long serialVersionUID = 1L;

        private IndexException(String message) {
            super(message);
        }
    }
}
