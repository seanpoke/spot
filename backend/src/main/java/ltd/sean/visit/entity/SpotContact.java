package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 打卡点的一条联系方式，对应表 {@code spot_contact}。
 *
 * <p>为什么不是 spot 上的四个固定列：需求是「同一个类型可以有多条」（两个手机号），
 * 固定列一列只能存一个值；而且加「小红书」这种新类型不该改表结构。
 * 拆表后 {@code type} 是数据，类型清单在 {@code SpotService.CONTACT_TYPES}。
 *
 * <p>{@code sortOrder} 就是用户的添加顺序，详情页按它排；同类型多条也靠它保序。
 * 删除打卡点时由外键 ON DELETE CASCADE 连带清掉，业务代码不用手动删。
 */
@Data
@TableName("spot_contact")
public class SpotContact {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String spotId;

    /** phone / wechat / qq / telegram / youyu */
    private String type;

    /** 电话号码 / 微信号 / QQ号 / 用户名 / ID */
    private String value;

    /** 展示顺序，小在前（即添加顺序） */
    private Integer sortOrder;

    private LocalDateTime createdAt;
}
