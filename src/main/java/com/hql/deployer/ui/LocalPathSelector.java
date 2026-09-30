package com.hql.deployer.ui;

import com.hql.deployer.util.PathUtil;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.io.File;
import java.util.List;

/**
 * 「待上传路径」输入控件：可编辑文本框 + 两个语义明确的入口（选文件 / 选文件夹）。
 *
 * <p>为什么不用单个「…」按钮：IDE 通用选择器一次只允许一种目标类型，选文件要切到
 * 「文件」页签、选目录要留在「目录」页签，用户很难一眼看出当前能选什么。这里把两种
 * 意图拆成两个按钮，并把「选中的到底是文件还是目录、会上传什么」直接写在提示行里，
 * 省掉一次试错部署。</p>
 *
 * <p>选中的路径尽量转成「相对项目根」保存；只有项目外的路径才保留绝对值，
 * 这样配置才能随项目迁移到别的机器。</p>
 *
 * @author hql on 2026/9/28
 */
public final class LocalPathSelector extends JPanel {

    private final TextFieldWithBrowseButton pathField = new TextFieldWithBrowseButton();
    private final JBLabel hintLabel = new JBLabel(" ");

    private final Project project;

    public LocalPathSelector(@Nullable Project project) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);
        buildUi();
    }

    private void buildUi() {
        // 只用它的文本框，自带的浏览按钮关掉，改由语义明确的两个按钮承担
        pathField.setButtonEnabled(false);
        pathField.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull javax.swing.event.DocumentEvent e) {
                updateHint();
            }
        });

        JButton fileButton = createButton("选择文件…", this::chooseFile);
        JButton folderButton = createButton("选择文件夹…", this::chooseFolder);
        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(fileButton);
        buttons.add(Box.createHorizontalStrut(JBUI.scale(4)));
        buttons.add(folderButton);

        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        inputRow.setOpaque(false);
        inputRow.add(pathField, BorderLayout.CENTER);
        inputRow.add(buttons, BorderLayout.EAST);

        JPanel root = new JPanel();
        root.setOpaque(false);
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.add(inputRow);
        hintLabel.setBorder(JBUI.Borders.emptyTop(2));
        root.add(hintLabel);
        add(root, BorderLayout.CENTER);
        updateHint();
    }

    @NotNull
    private static JButton createButton(@NotNull String text, @NotNull Runnable action) {
        JButton button = new JButton(text);
        button.addActionListener(e -> action.run());
        return button;
    }

    /**
     * 当前路径文本（已去空白）。
     */
    @NotNull
    public String getPath() {
        return pathField.getText().trim();
    }

    /**
     * 写入路径并刷新提示。
     */
    public void setPath(@Nullable String path) {
        pathField.setText(path == null ? "" : path);
        updateHint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        // 容器整体置灰时，单独禁用文本框与按钮，否则会留下「能点但没反应」的幽灵控件
        setEnabledRecursively(this, enabled);
    }

    private static void setEnabledRecursively(@NotNull java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }

    private void chooseFile() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor();
        descriptor.withTitle("选择待上传的文件");
        descriptor.withDescription("jar / war / zip 或任意静态资源文件");
        pick(descriptor);
    }

    private void chooseFolder() {
        FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
        descriptor.withTitle("选择待上传的文件夹");
        descriptor.withDescription("文件夹内的全部内容将保持原有结构上传");
        pick(descriptor);
    }

    private void pick(@NotNull FileChooserDescriptor descriptor) {
        VirtualFile selected = FileChooser.chooseFile(descriptor, project, getDefaultDirectory());
        if (selected == null) {
            // 用户点了取消，保持原值不动
            return;
        }
        setPath(toConfigPath(selected));
    }

    /**
     * 尽量存相对项目根的路径；项目外的路径只能存绝对值。
     */
    @NotNull
    private String toConfigPath(@NotNull VirtualFile selected) {
        File root = resolveProjectRoot();
        File absolute = new File(selected.getPath());
        if (root != null) {
            String relative = PathUtil.relativeTo(root, absolute);
            if (relative != null && !relative.isEmpty()) {
                return relative;
            }
        }
        return absolute.getPath();
    }

    /**
     * 选择器的起始目录：优先当前路径所在目录，其次项目根。
     */
    @Nullable
    private VirtualFile getDefaultDirectory() {
        String current = getPath();
        File start;
        if (current.isEmpty()) {
            start = resolveProjectRoot();
        } else {
            File resolved = PathUtil.resolve(resolveProjectRootOrSelf(), current);
            start = resolved.isDirectory() ? resolved : resolved.getParentFile();
        }
        return start == null ? null : LocalFileSystem.getInstance().findFileByIoFile(start);
    }

    /**
     * 根据当前路径给出「是什么、会传多少」的即时反馈。
     *
     * <p>最常见的困惑是「选了目录，传的是目录本身还是里面的内容」，把语义写进提示
     * 比让用户先跑一次部署去看结果便宜得多。</p>
     */
    private void updateHint() {
        String path = getPath();
        if (path.isEmpty()) {
            hintLabel.setText(" ");
            return;
        }
        File resolved = PathUtil.resolve(resolveProjectRootOrSelf(), path);
        if (!resolved.exists()) {
            hintLabel.setText("<html><font color='#C55'>路径不存在: " + escape(resolved.getPath()) + "</font></html>");
            return;
        }
        if (resolved.isFile()) {
            hintLabel.setText("将上传单个文件 " + escape(resolved.getName())
                    + "（" + formatSize(resolved.length()) + "）到目标目录");
            return;
        }
        if (resolved.isDirectory()) {
            PathUtil.FileStats stats = PathUtil.collectStats(resolved, List.of());
            hintLabel.setText("将上传该目录下的 " + stats.fileCount() + " 个文件（"
                    + formatSize(stats.totalBytes()) + "），不含目录本身");
            return;
        }
        hintLabel.setText(" ");
    }

    @NotNull
    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes / 1024.0;
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    @NotNull
    private static String escape(@NotNull String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Nullable
    private File resolveProjectRoot() {
        if (project == null) {
            return null;
        }
        String basePath = project.getBasePath();
        if (basePath != null && !basePath.isBlank()) {
            return new File(basePath);
        }
        VirtualFile projectFile = project.getProjectFile();
        if (projectFile == null || projectFile.getParent() == null) {
            return null;
        }
        return VfsUtilCore.virtualToIoFile(projectFile.getParent());
    }

    @NotNull
    private File resolveProjectRootOrSelf() {
        File root = resolveProjectRoot();
        return root != null ? root : new File(".");
    }

    /**
     * 供外部（如测试、其它编辑器）定位文本框组件。
     */
    @NotNull
    public JComponent getFieldComponent() {
        return pathField;
    }
}
