package com.hql.deployer.runconfig;

import com.hql.deployer.core.DeployEvent;
import com.hql.deployer.core.DeployException;
import com.hql.deployer.core.DeployListener;
import com.hql.deployer.core.DeployResult;
import com.hql.deployer.core.DeployStage;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.ui.components.JBScrollPane;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;

/**
 * 部署日志控制台。
 *
 * <p>自实现 {@link ExecutionConsole} 而非复用平台控制台：部署输出是「一次性、带时间戳、
 * 按级别着色」的文本流，用轻量组件渲染更可控，也避免依赖 {@code impl} 包下的内部类。</p>
 *
 * <p>样式统一跟随 IDE 主题：背景来自全局配色的控制台背景，文字颜色取自平台标准
 * {@link ConsoleViewContentType}（与 Run/Gradle 控制台同源），字体使用编辑器等宽字体。
 * 这样无论浅色还是深色主题，控制台都与 IDE 自身观感一致。</p>
 *
 * <p>所有回调都来自后台线程，因此统一通过 {@link #append} 切回 EDT 后再操作文档。</p>
 *
 * @author hql on 2026/9/28
 */
public final class DeployLogConsole implements ExecutionConsole, Disposable {

    private static final String SECTION_INDENT = "  ";
    private static final String LINE_INDENT = "    ";

    private final JTextPane area = new JTextPane();
    private final JPanel root = new JPanel(new BorderLayout());
    private final String title;
    /** 主题决定的字体家族；NULL 时退回系统等宽字 */
    private final String consoleFontFamily;
    /** 主题决定的字体大小 */
    private final int consoleFontSize;

    public DeployLogConsole(@NotNull String title) {
        this.title = title;
        EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
        Color background = scheme.getColor(ConsoleViewContentType.CONSOLE_BACKGROUND_KEY);
        Color foreground = scheme.getDefaultForeground();
        String fontFamily = scheme.getEditorFontName();
        int fontSize = scheme.getEditorFontSize();

        area.setEditable(false);
        consoleFontFamily = fontFamily == null || fontFamily.isBlank() ? Font.MONOSPACED : fontFamily;
        consoleFontSize = fontSize > 0 ? fontSize : 12;
        area.setFont(new Font(consoleFontFamily, Font.PLAIN, consoleFontSize));
        area.setBackground(background != null ? background : scheme.getDefaultBackground());
        area.setForeground(foreground);
        JBScrollPane scrollPane = new JBScrollPane(area);
        scrollPane.setBorder(null);
        root.add(scrollPane, BorderLayout.CENTER);
    }

    @Override
    @NotNull
    public JComponent getComponent() {
        return root;
    }

    @Override
    @NotNull
    public JComponent getPreferredFocusableComponent() {
        return area;
    }

    @Override
    public void dispose() {
        // 组件随 Swing 层级一起回收，无需额外清理
    }

    @NotNull
    public String getTitle() {
        return title;
    }

    /**
     * 构造一个把流水线事件渲染成日志的监听器。
     */
    @NotNull
    public DeployListener asListener() {
        return new DeployListener() {
            @Override
            public void onStageStart(@NotNull DeployStage stage) {
                append(SECTION_INDENT, stage.getDisplayName(), Level.SECTION);
            }

            @Override
            public void onEvent(@NotNull DeployEvent event) {
                for (DeployEvent line : DeployEvent.lines(event.stage(), event.level(), event.text())) {
                    append(LINE_INDENT, line.text(), levelOf(line), false);
                }
            }

            @Override
            public void onSuccess(@NotNull DeployResult result) {
                append(SECTION_INDENT,
                        "部署完成，用时 " + (result.elapsedMillis() / 1000) + " 秒", Level.SUCCESS);
                if (result.backupDirectory() != null) {
                    append(LINE_INDENT, "远端备份目录: " + result.backupDirectory(), Level.INFO, false);
                }
            }

            @Override
            public void onFailure(@NotNull DeployException exception) {
                append(SECTION_INDENT, exception.toUserMessage(), Level.ERROR);
                append(SECTION_INDENT, "后续阶段已中止，线上服务未受影响", Level.INFO);
            }

            @Override
            public void onCancelled() {
                // 生效与否由流水线用事件说清，这里只负责给出结论行
                append(SECTION_INDENT, "已取消部署", Level.WARN);
            }
        };
    }

    /**
     * 追加一行日志。线程安全：非 EDT 调用时自动投递到 EDT。
     *
     * @param indent 行首缩进
     * @param text   内容
     * @param level  着色级别
     */
    public void append(@NotNull String indent, @NotNull String text, @NotNull Level level) {
        append(indent, text, level, true);
    }

    private void append(@NotNull String indent, @NotNull String text, @NotNull Level level, boolean timestamped) {
        String line = (timestamped ? "[" + java.time.LocalTime.now() + "] " : "") + indent + text;
        Runnable appendTask = () -> insert(line, level);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            appendTask.run();
        } else {
            ApplicationManager.getApplication().invokeLater(appendTask, ModalityState.any());
        }
    }

    /**
     * 在 EDT 上把一行插入文档末尾并着色。
     */
    private void insert(@NotNull String line, @NotNull Level level) {
        StyledDocument document = area.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, level.foreground(consoleFontFamily, consoleFontSize));
        StyleConstants.setBold(attributes, level.bold());
        StyleConstants.setFontFamily(attributes, consoleFontFamily);
        StyleConstants.setFontSize(attributes, consoleFontSize);
        try {
            document.insertString(document.getLength(), line + "\n", attributes);
            area.setCaretPosition(document.getLength());
        } catch (BadLocationException e) {
            // 文档已被清理时忽略，部署本身不受影响
        }
    }

    @NotNull
    private static Level levelOf(@NotNull DeployEvent event) {
        return switch (event.level()) {
            case COMMAND -> Level.COMMAND;
            case ERROR -> Level.ERROR;
            case WARN -> Level.WARN;
            case SUCCESS -> Level.SUCCESS;
            case INFO -> Level.INFO;
        };
    }

    /**
     * 日志着色级别。
     *
     * <p>颜色全部映射到平台标准 {@link ConsoleViewContentType}，随 IDE 主题自动适配；
     * 不再自造色值，保证与 Run 工具窗里其它控制台观感统一。</p>
     */
    public enum Level {
        INFO(ConsoleViewContentType.NORMAL_OUTPUT, false),
        COMMAND(ConsoleViewContentType.SYSTEM_OUTPUT, false),
        SUCCESS(ConsoleViewContentType.NORMAL_OUTPUT, true),
        WARN(ConsoleViewContentType.LOG_WARNING_OUTPUT, false),
        ERROR(ConsoleViewContentType.ERROR_OUTPUT, false),
        /** 阶段小节标题：粗体，不带时间戳 */
        SECTION(ConsoleViewContentType.NORMAL_OUTPUT, true);

        private final ConsoleViewContentType contentType;
        private final boolean bold;

        Level(@NotNull ConsoleViewContentType contentType, boolean bold) {
            this.contentType = contentType;
            this.bold = bold;
        }

        /**
         * 解析该级别在当前主题下的前景色。配置缺省时退回全局默认前景色。
         */
        @NotNull
        Color foreground(@NotNull String fontFamily, int fontSize) {
            EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
            TextAttributes attributes = scheme.getAttributes(contentType.getAttributesKey(), true);
            Color color = attributes == null ? null : attributes.getForegroundColor();
            return color != null ? color : scheme.getDefaultForeground();
        }

        boolean bold() {
            return bold;
        }
    }
}
