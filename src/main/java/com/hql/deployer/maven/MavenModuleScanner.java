package com.hql.deployer.maven;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 递归扫描项目下的所有 {@code pom.xml}，构建 Maven 模块清单。
 *
 * <p>跳过 {@code target}、{@code .git}、{@code node_modules}、{@code .idea} 等目录，
 * 解析出 {@code artifactId}、{@code groupId}、{@code packaging}、{@code name}。</p>
 *
 * <p>使用 {@code <modules>} 做可达性收敛：从根 pom 的 modules 出发按声明顺序递归，
 * 避免把项目里无关的样例 pom 也收进来。根 pom 缺失或无法解析时退化为目录全量扫描。</p>
 *
 * @author hql on 2026/9/28
 */
public final class MavenModuleScanner {

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            "target", "build", "out", ".git", ".idea", ".svn", ".hg",
            "node_modules", "bin", "logs", ".gradle", ".mvn", "dist");

    private static final int MAX_SCAN_DEPTH = 12;

    private final File projectRoot;

    public MavenModuleScanner(@NotNull File projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * 扫描模块列表，按项目结构排序（父模块在前，同层按目录名）。
     */
    @NotNull
    public List<MavenModule> scan() {
        File rootPom = findRootPom(projectRoot);
        List<MavenModule> modules = new ArrayList<>();
        // 只用于去重「同一个 pom 被多次声明」的情况，不参与递归的终止判断，
        // 否则子模块的孙模块会被提前跳过，导致只能收集到一层。
        Set<String> declaredPoms = new LinkedHashSet<>();

        if (rootPom != null) {
            // 根 pom 自身若不是聚合模块，也要收录（例如单模块项目）
            addModule(rootPom, modules, declaredPoms);
            collectFromModules(rootPom, modules, declaredPoms, 0);
        }

        if (modules.isEmpty()) {
            // 退化：全量扫描
            List<File> poms = new ArrayList<>();
            scanAllPoms(projectRoot, 0, poms);
            for (File pom : poms) {
                addModule(pom, modules, declaredPoms);
            }
        }

        MavenModuleScanner scanner = this;
        modules.sort(Comparator.comparingInt(scanner::depthOf)
                .thenComparing(MavenModule::getPathSeparator));
        return modules;
    }

    /**
     * 查找根 pom。优先取含 {@code <modules>} 或 {@code <packaging>pom</packaging>} 的顶层 pom，
     * 否则取顶层第一个 pom.xml。
     */
    @Nullable
    private static File findRootPom(@NotNull File root) {
        File[] children = root.listFiles();
        if (children == null) {
            return null;
        }
        List<File> topPoms = new ArrayList<>();
        for (File child : children) {
            if (child.isFile() && "pom.xml".equals(child.getName())) {
                topPoms.add(child);
            }
        }
        if (topPoms.isEmpty()) {
            return null;
        }
        for (File pom : topPoms) {
            Element project = parsePom(pom);
            if (project != null && "pom".equals(childText(project, "packaging"))) {
                return pom;
            }
        }
        return topPoms.get(0);
    }

    /**
     * 顺着 {@code <modules>} 声明递归收集。
     *
     * <p>递归终止只依赖 {@code depth} 与「本层是否已展开过」，<b>不能</b>用记录模块的
     * 去重集合做终止判断：子模块被收录后若同时进入去重集合，其下的孙模块就会被跳过。</p>
     */
    private void collectFromModules(@NotNull File pom,
                                   @NotNull List<MavenModule> modules,
                                   @NotNull Set<String> declaredPoms,
                                   int depth) {
        if (depth > MAX_SCAN_DEPTH || !declaredPoms.add("expand:" + pom.getAbsolutePath())) {
            return;
        }
        Element project = parsePom(pom);
        if (project == null) {
            return;
        }
        Element modulesElement = firstChildElement(project, "modules");
        if (modulesElement == null) {
            return;
        }
        NodeList children = modulesElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"module".equals(node.getNodeName())) {
                continue;
            }
            String modulePath = node.getTextContent();
            if (modulePath == null || modulePath.isBlank()) {
                continue;
            }
            File moduleDir = new File(pom.getParentFile(), modulePath.trim());
            File childPom = new File(moduleDir, "pom.xml");
            if (childPom.isFile()) {
                addModule(childPom, modules, declaredPoms);
                collectFromModules(childPom, modules, declaredPoms, depth + 1);
            }
        }
    }

    private static void addModule(@NotNull File pom,
                                  @NotNull List<MavenModule> modules,
                                  @NotNull Set<String> declaredPoms) {
        if (!declaredPoms.add(pom.getAbsolutePath())) {
            return;
        }
        Element project = parsePom(pom);
        if (project == null) {
            return;
        }
        Element parent = firstChildElement(project, "parent");
        String artifactId = childText(project, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            return;
        }
        String groupId = childText(project, "groupId");
        if (groupId == null && parent != null) {
            groupId = childText(parent, "groupId");
        }
        String packaging = childText(project, "packaging");
        if (packaging == null || packaging.isBlank()) {
            packaging = "jar";
        }
        String name = childText(project, "name");

        modules.add(new MavenModule(
                artifactId,
                name == null || name.isBlank() ? artifactId : name,
                groupId == null ? "" : groupId,
                packaging,
                pom,
                pom.getParentFile(),
                "pom".equals(packaging)));
    }

    /**
     * 目录全量扫描，作为无根 pom 时的退化路径。
     */
    private static void scanAllPoms(@NotNull File directory, int depth, @NotNull List<File> result) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (IGNORED_DIRECTORIES.contains(child.getName()) || child.getName().startsWith(".")) {
                    continue;
                }
                scanAllPoms(child, depth + 1, result);
            } else if ("pom.xml".equals(child.getName())) {
                result.add(child);
            }
        }
    }

    /**
     * 解析 pom.xml，只取直接子节点以兼容带命名空间的默认 pom。
     */
    @Nullable
    static Element parsePom(@NotNull File pom) {
        if (!pom.isFile()) {
            return null;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 关闭外部实体，防 XXE
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(pom);
            document.getDocumentElement().normalize();
            return document.getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    static Element firstChildElement(@Nullable Element parent, @NotNull String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    @Nullable
    static String childText(@Nullable Element parent, @NotNull String tagName) {
        Element child = firstChildElement(parent, tagName);
        if (child == null) {
            return null;
        }
        String text = child.getTextContent();
        return text == null ? null : text.trim();
    }

    private int depthOf(@NotNull MavenModule module) {
        String root = projectRoot.getAbsolutePath();
        String dir = module.moduleDir().getAbsolutePath();
        if (dir.equals(root)) {
            return 0;
        }
        if (!dir.startsWith(root)) {
            return 0;
        }
        String relative = dir.substring(root.length());
        int count = 0;
        for (int i = 0; i < relative.length(); i++) {
            if (relative.charAt(i) == File.separatorChar) {
                count++;
            }
        }
        return count;
    }

    /**
     * 解析 POM 中的 {@code <properties>} 占位符（如 {@code ${project.version}}），
     * 用于把目标文件名里的变量替换成实际值。
     */
    @Nullable
    public static String resolveProperty(@NotNull File pom, @NotNull String value) {
        int index = value.indexOf("${");
        if (index < 0) {
            return value;
        }
        Element project = parsePom(pom);
        if (project == null) {
            return value;
        }
        String result = value;
        for (int i = 0; i < 10; i++) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start);
            if (start < 0 || end < 0) {
                break;
            }
            String key = result.substring(start + 2, end);
            String replacement = switch (key) {
                case "project.version", "version" -> childText(project, "version");
                case "project.artifactId" -> childText(project, "artifactId");
                case "project.name" -> childText(project, "name");
                case "project.basedir", "basedir" -> null;
                default -> {
                    Element properties = firstChildElement(project, "properties");
                    yield properties == null ? null : childText(properties, key);
                }
            };
            if (replacement == null) {
                return null;
            }
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    /**
     * 递归收集目录下所有文件（按相对路径升序），供上层做产物匹配。
     */
    @NotNull
    public static List<File> listFilesRecursively(@NotNull File root) throws IOException {
        List<File> files = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) {
            return files;
        }
        java.util.Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                files.addAll(listFilesRecursively(child));
            } else {
                files.add(child);
            }
        }
        return files;
    }
}
