package ltd.sean.visit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新建 / 更新打卡点的入参。<b>整体提交（PUT 语义）</b>：
 * 字段传 null 就是「清空」，不要用「没传即不改」那套局部更新语义，
 * 否则前端表单里删掉一个联系方式会毫无反应。
 *
 * <p>枚举与范围校验放在 Service（错误信息要能直接给用户看），这里只挡「必填」。
 *
 * @param kind         place / person，null 视为 place
 * @param status       unvisited / visited / wishlist / blacklist，null 视为 unvisited
 * @param rankLevel    0~6，null 视为 0
 * @param rankManual   是否手动定级，null 视为 false
 * @param packages     价目表，null 表示不填
 * @param parentSpotId 仅人物可填，必须指向一个 kind=place 的打卡点
 * @param tagIds       最多 8 个（PRD 5.6），顺序即展示顺序
 * @param province     省 / 直辖市
 * @param district     区 / 县
 * @param contacts     全部联系方式，顺序即展示顺序；null / 空数组 = 清空。
 *                     同类型可以多条（两个手机号），最多 20 条
 * @param photos       打卡点自己的照片，最多 9 张（按顺序），null 表示不填
 */
public record SpotSaveRequest(
        @NotBlank(message = "名称不能为空") String name,
        String kind,
        String status,
        Integer rankLevel,
        Boolean rankManual,
        String avatar,
        String cover,
        String description,
        BigDecimal lat,
        BigDecimal lng,
        String address,
        String city,
        String province,
        String district,
        @Size(max = 20, message = "联系方式最多 20 条") List<ContactItem> contacts,
        List<ImageRef> photos,
        List<PackageItem> packages,
        String parentSpotId,
        @Size(max = 8, message = "每个打卡点最多 8 个标签") List<String> tagIds
) {
}
