package ltd.sean.visit.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 打卡点详情。一个请求给全，前端不再为了「建议段位」「所属场所」「挂靠人物」补三次。
 *
 * @param avatar         完整图片 URL（已拼 CDN 前缀）
 * @param cover          完整图片 URL
 * @param avatarFromPhotos true = 用户没设头像，这个值是「第一张照片」兜底出来的
 * @param coverFromPhotos  同上，对应封面
 * @param parentSpotId   人物所属场所；场所自己为 null
 * @param parentSpotName 所属场所名，供详情页返回按钮用
 * @param children       场所下的人物列表；人物为空数组
 * @param rankManual     true = 手动定过段位，前端不再展示「建议」入口
 * @param suggestedRankLevel 按历史到访算出的建议段位（PRD 5.1），0 次到访时为 null。
 *                           与 {@code rankManual} 无关：手动锁过也给值，前端据此决定要不要露「建议」入口
 * @param visitCount     到访次数，0 表示没去过（**不代表不能定级**，见 PRD 5.1）
 * @param avgRating      平均星级，无到访时为 null
 * @param lastVisitAt    最近到访日期，无到访时为 null
 * @param province       省 / 直辖市（位置卡「省市区」第一行）
 * @param district       区 / 县（位置卡「省市区」第一行）
 * @param contacts       联系方式，按添加顺序（同类型可能多条，如两个手机号）
 * @param photos         打卡点自己的照片（URL 已拼 CDN 前缀），最多 9 张
 */
public record SpotDetail(
        String id,
        String kind,
        String name,
        String avatar,
        String cover,
        boolean avatarFromPhotos,
        boolean coverFromPhotos,
        String description,
        BigDecimal lat,
        BigDecimal lng,
        String address,
        String city,
        String province,
        String district,
        List<ContactItem> contacts,
        List<ImageRef> photos,
        List<PackageItem> packages,
        Integer rankLevel,
        Boolean rankManual,
        Integer suggestedRankLevel,
        String parentSpotId,
        String parentSpotName,
        List<SpotChild> children,
        String status,
        List<TagBrief> tags,
        long visitCount,
        BigDecimal avgRating,
        LocalDate lastVisitAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
