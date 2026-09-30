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
import com.intellij.ui.JBColor;
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

    public DeployLogConsole(@NotNull String title) {
        this.title = title;
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new JBColor(new Color(0x2B2B2B), new Color(0x2B2B2B)));
        area.setForeground(new JBColor(new Color(0xBBBBBB), new Color(0xBBBBBB)));
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
        StyleConstants.setForeground(attributes, level.color());
        StyleConstants.setFontFamily(attributes, Font.MONOSPACED);
        StyleConstants.setFontSize(attributes, 12);
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
     */
    public enum Level {
        INFO(JBColor.GRAY),
        COMMAND(new JBColor(new Color(0x2A7), new Color(0x2A7))),
        SUCCESS(new JBColor(new Color(0x3C9), new Color(0x3C9))),
        WARN(new JBColor(new Color(0xC90), new Color(0xC90))),
        ERROR(new JBColor(new Color(0xC55), new Color(0xC55))),
        /** 阶段小节标题：加粗，不带时间戳 */
        SECTION(new JBColor(new Color(0xDDD), new Color(0xDDD)));

        private final Color color;

        Level(@NotNull Color color) {
            this.color = color;
        }

        @NotNull
        Color color() {
            return color;
        }
    }
}
