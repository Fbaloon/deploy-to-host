package com.hql.deployer.config;

import com.intellij.credentialStore.Credentials;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 密码写入凭据的构造方式测试。
 *
 * <p>{@code Credentials(String)} 的真实签名是 {@code Credentials(userName, password = null)}，
 * 传单个字符串会被当成 userName，密码字段为 {@code null}。PasswordSafe 对此既不报错也不存密码，
 * 表现为「保存成功但读出来是空」。这里把这个平台行为钉死，防止回退到单参构造器。</p>
 *
 * @author hql on 2026/9/28
 */
class PasswordStoreCredentialsTest {

    @Nested
    @DisplayName("平台 Credentials 的构造器语义")
    class PlatformSemantics {

        @Test
        @DisplayName("单参构造器设置的是 userName，密码为 null")
        void singleArgConstructorSetsUserName() {
            Credentials credentials = new Credentials("secret");

            assertNull(credentials.getPasswordAsString(), "单参构造器不应写入密码");
            assertEquals("secret", credentials.getUserName());
        }

        @Test
        @DisplayName("(userName, char[]) 构造器正确写入密码")
        void charArrayConstructorSetsPassword() {
            Credentials credentials = new Credentials(null, "secret".toCharArray());

            assertEquals("secret", credentials.getPasswordAsString());
            assertNull(credentials.getUserName());
        }
    }

    @Nested
    @DisplayName("PasswordStore.credentials")
    class StoreFactory {

        @Test
        @DisplayName("构造出的凭据带密码且不带用户名")
        void createsPasswordOnlyCredentials() throws Exception {
            Credentials credentials = invokeCredentials("p@ssw0rd-密码");

            assertEquals("p@ssw0rd-密码", credentials.getPasswordAsString(),
                    "PasswordStore 必须用 (userName, char[]) 构造 Credentials，否则密码不会被保存");
            assertNull(credentials.getUserName());
        }

        private Credentials invokeCredentials(String password) {
            try {
                Method method = PasswordStore.class.getDeclaredMethod("credentials", String.class);
                method.setAccessible(true);
                return (Credentials) method.invoke(null, password);
            } catch (NoSuchMethodException e) {
                fail("PasswordStore.credentials(String) 不存在，密码写入路径已被改动，请重新确认构造方式", e);
                return null;
            } catch (Exception e) {
                fail(e);
                return null;
            }
        }
    }
}
