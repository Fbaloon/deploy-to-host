package com.hql.deployer.runconfig;

import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * {@link MavenDeployConfigurationType} 的工厂。
 *
 * @author hql on 2026/9/28
 */
public final class MavenDeployConfigurationFactory extends ConfigurationFactory {

    public MavenDeployConfigurationFactory(@NotNull ConfigurationType type) {
        super(type);
    }

    @Override
    @Nls
    @NotNull
    public String getId() {
        return "MavenDeploy";
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
        return "部署到服务器";
    }

    @NotNull
    @Override
    public RunConfiguration createTemplateConfiguration(@NotNull Project project) {
        MavenDeployRunConfiguration configuration = new MavenDeployRunConfiguration(project, this);
        configuration.setName("部署配置");
        configuration.setGeneratedName();
        return configuration;
    }
}
