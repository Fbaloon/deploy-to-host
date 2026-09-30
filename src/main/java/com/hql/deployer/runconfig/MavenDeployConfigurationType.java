package com.hql.deployer.runconfig;

import com.hql.deployer.util.DeployBundle;
import com.intellij.execution.configurations.ConfigurationTypeBase;
import com.intellij.openapi.util.IconLoader;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * 「部署到服务器」配置类型。
 *
 * <p>出现在 Run/Debug Configurations 面板的 {@code +} 列表中，形态对齐
 * Alibaba Cloud Toolkit 的 {@code Deploy to Host}。</p>
 *
 * @author hql on 2026/9/28
 */
public final class MavenDeployConfigurationType extends ConfigurationTypeBase {

    public static final String ID = "MavenDeployToHost";

    /**
     * 由 {@code plugin.xml} 中的 {@code configurationType} 扩展点反射调用，
     * 因此必须保留 public 无参构造，不能私有化。
     */
    public MavenDeployConfigurationType() {
        super(ID,
                DeployBundle.message("plugin.name"),
                "将指定模块或目录构建并发布到远程服务器",
                IconLoader.getIcon("/icons/deployToHostColor.svg", MavenDeployConfigurationType.class));
    }

    @NotNull
    @Override
    public MavenDeployConfigurationFactory[] getConfigurationFactories() {
        return new MavenDeployConfigurationFactory[]{new MavenDeployConfigurationFactory(this)};
    }

    @Nls
    @NotNull
    @Override
    public String getTag() {
        return "maven-deploy";
    }
}
