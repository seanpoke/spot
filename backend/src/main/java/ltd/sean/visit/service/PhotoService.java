package ltd.sean.visit.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import ltd.sean.visit.config.AppProperties;
import ltd.sean.visit.dto.ImageRef;
import ltd.sean.visit.entity.Photo;
import ltd.sean.visit.mapper.PhotoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一照片表读写。打卡点（owner_type=spot）与到访记录（owner_type=visit）共用，
 * 不在 spot / visit 表里留 JSON 照片列。
 *
 * <p>两条硬约定：
 * <ul>
 *   <li>列表接口绝不取照片（带宽小），只有查「单条详情」才调 {@link #listByOwner}</li>
 *   <li>存的是相对路径，出参由 {@link AppProperties#toPublicUrl} 拼成可访问 URL</li>
 * </ul>
 */
@Service
public class PhotoService {

    /** 归属类型常量，避免魔法字符串散落在 spot / visit 两处 */
    public static final String OWNER_SPOT = "spot";
    public static final String OWNER_VISIT = "visit";

    private static final int MAX_PHOTOS = 9;

    private final PhotoMapper photoMapper;
    private final AppProperties appProperties;

    public PhotoService(PhotoMapper photoMapper, AppProperties appProperties) {
        this.photoMapper = photoMapper;
        this.appProperties = appProperties;
    }

    /** 某个归属下的全部照片，按展示顺序排，URL 已拼好前缀 */
    public List<ImageRef> listByOwner(String ownerType, String ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        return photoMapper.selectList(new QueryWrapper<Photo>()
                        .eq("owner_type", ownerType)
                        .eq("owner_id", ownerId)
                        .orderByAsc("sort_order")
                        .orderByAsc("created_at"))
                .stream()
                .map(this::toRef)
                .toList();
    }

    /**
     * 整体替换某归属下的照片：先删后插，一个事务内完成，顺序即入参顺序。
     * 入参是前端回传的完整 URL，落库前还原成相对路径；最多 9 张。
     */
    @Transactional
    public void replacePhotos(String ownerType, String ownerId, List<ImageRef> photos) {
        photoMapper.delete(new QueryWrapper<Photo>()
                .eq("owner_type", ownerType)
                .eq("owner_id", ownerId));

        if (photos == null) {
            return;
        }
        List<ImageRef> kept = photos.stream().limit(MAX_PHOTOS).toList();
        for (int index = 0; index < kept.size(); index++) {
            ImageRef ref = kept.get(index);
            Photo photo = new Photo();
            photo.setOwnerType(ownerType);
            photo.setOwnerId(ownerId);
            photo.setSortOrder(index);
            photo.setImageId(ref.id());
            photo.setLarge(appProperties.toStoredPath(ref.large()));
            photo.setThumb(appProperties.toStoredPath(ref.thumb()));
            photo.setWidth(ref.width() == 0 ? null : ref.width());
            photo.setHeight(ref.height() == 0 ? null : ref.height());
            photo.setSize(ref.size() == 0 ? null : ref.size());
            photoMapper.insert(photo);
        }
    }

    /** 删除某归属下的全部照片（删打卡点 / 到访记录时连带清掉，避免孤儿行） */
    @Transactional
    public void deleteByOwner(String ownerType, String ownerId) {
        if (ownerId == null) {
            return;
        }
        photoMapper.delete(new QueryWrapper<Photo>()
                .eq("owner_type", ownerType)
                .eq("owner_id", ownerId));
    }

    // ------------------------------------------------------- 没设头像/封面时的兜底

    /**
     * 兜底用的「第一张照片」：没单独设头像/封面时用它顶上（详情大图用 cover，标记用 avatar）。
     * 两个地址都已拼好前缀。
     *
     * @param avatar 96×96 派生图；老数据没有 image_id 时退回 thumb
     * @param cover  1280 长边大图
     */
    public record FirstImage(String avatar, String cover) {
    }

    /**
     * 批量取「每个归属的第一张照片」。
     *
     * <p>一次 IN 查询取回后按 ownerId 归组，只留每组排序最小的那条 ——
     * 禁止在循环里逐个查（列表一次几十条，AGENTS.md 第 12 条）。
     */
    public Map<String, FirstImage> firstImageByOwner(String ownerType, Collection<String> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        List<Photo> rows = photoMapper.selectList(new QueryWrapper<Photo>()
                .select("owner_id", "image_id", "large", "thumb")
                .eq("owner_type", ownerType)
                .in("owner_id", ownerIds)
                .orderByAsc("sort_order")
                .orderByAsc("created_at"));

        Map<String, FirstImage> first = new HashMap<>();
        for (Photo photo : rows) {
            // 顺序已在 SQL 里排好，同一个 owner 先到的那条就是第一张
            first.putIfAbsent(photo.getOwnerId(), toFirstImage(photo.getImageId(),
                    photo.getLarge(), photo.getThumb()));
        }
        return first;
    }

    /** 详情页已经把照片列表查出来了，直接用第一条兜底，不再多查一次库 */
    public FirstImage firstImageOf(ImageRef ref) {
        return toFirstImage(ref.id(), ref.large(), ref.thumb());
    }

    private FirstImage toFirstImage(String imageId, String largePath, String thumbPath) {
        // 96×96 派生图不落库，按上传目录约定从 image_id 推；没有 image_id 的老数据退回 thumb
        String avatar = StringUtils.hasText(imageId)
                ? appProperties.toPublicUrl(UploadService.avatarPath(imageId))
                : appProperties.toPublicUrl(thumbPath);
        return new FirstImage(avatar, appProperties.toPublicUrl(largePath));
    }

    private ImageRef toRef(Photo photo) {
        return new ImageRef(
                photo.getImageId(),
                appProperties.toPublicUrl(photo.getLarge()),
                appProperties.toPublicUrl(photo.getThumb()),
                photo.getWidth() == null ? 0 : photo.getWidth(),
                photo.getHeight() == null ? 0 : photo.getHeight(),
                photo.getSize() == null ? 0L : photo.getSize());
    }
}
