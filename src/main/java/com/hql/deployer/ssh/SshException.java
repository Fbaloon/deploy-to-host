package com.hql.deployer.ssh;

/**
 * SSH 相关异常。
 *
 * @author hql on 2026/9/28
 */
public class SshException extends RuntimeException {

    private static final long serialVersionUID = 6841225918374619723L;

    public SshException(String message) {
        super(message);
    }

    public SshException(String message, Throwable cause) {
        super(message, cause);
    }
}
