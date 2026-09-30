package com.hql.deployer.util;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * 插件消息资源访问。
 *
 * @author hql on 2026/9/28
 */
public final class DeployBundle extends DynamicBundle {

    @NonNls
    public static final String BUNDLE = "messages.DeployBundle";

    private static final DeployBundle INSTANCE = new DeployBundle();

    private DeployBundle() {
        super(BUNDLE);
    }

    @NotNull
    @Nls
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key,
                                 Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
