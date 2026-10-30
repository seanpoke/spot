package ltd.sean.visit.dto;

import java.util.List;

/**
 * 联系方式查重入参。
 *
 * <p>传的是<b>表单里的当前值</b>而不是库里已存的：用户改完还没点保存就要能看见冲突，
 * 所以不能等落库后再查。
 *
 * @param spotId   正在编辑的打卡点 id；新建时为 null（新建没有「自己」要排除）
 * @param contacts 当前表单里的联系方式（空行由 Service 丢掉）
 */
public record ContactCheckRequest(String spotId, List<ContactItem> contacts) {
}
