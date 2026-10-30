package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一照片表的一行。打卡点照片与到访记录照片共用，靠 {@code ownerType} + {@code ownerId} 区分归属。
 *
 * <p>只存相对路径（large / thumb），完整 URL 在 Service 拼 {@code CDN_BASE_URL} 时组装，
 * 与本项目「数据库只存相对路径」的硬约定一致。
 */
@Data
@TableName("photo")
public class Photo {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 归属类型：spot=打卡点，visit=到访记录 */
    private String ownerType;

    /** 归属ID：spot.id 或 visit.id */
    private String ownerId;

    /** 同归属内的展示顺序，小在前 */
    private Integer sortOrder;

    /** 文件名里的 UUID，便于去重与缓存比对 */
    private String imageId;

    private String large;

    private String thumb;

    private Integer width;

    private Integer height;

    private Long size;

    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
