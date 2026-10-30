package ltd.sean.visit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到访记录，对应表 {@code visit}。
 *
 * <p>一次到访一条记录，评分、感受都挂在这里，所以没有单独的评论表。
 * 照片不在这张表里：统一存到 {@code photo} 表（owner_type='visit'），
 * 只在查到访详情时联表取出。{@code scores} 是 JSON 列，Entity 里保留原始字符串，
 * DTO 层再解析 —— 分项结构只由前端 {@code config/scoreDimensions.ts} 定义。
 */
@Data
@TableName("visit")
public class Visit {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String spotId;

    /** 只到天，避免同一晚的两次记录排不出先后 */
    private LocalDate visitedAt;

    /** 整体星级 1~5，参与段位计算 */
    private Integer rating;

    /** 分项评分 JSON：[{"key":"env","label":"环境","value":4}] */
    private String scores;

    private BigDecimal cost;

    private String companions;

    private String note;

    private LocalDateTime createdAt;
}
