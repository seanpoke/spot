package ltd.sean.visit.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 口令门登录入参。只有一个字段，不设长度上限：
 * 口令由所有者自己定，写死上限只会在某天拦住一个长口令。
 */
public record LoginRequest(
        @NotBlank(message = "请输入口令") String password
) {
}
