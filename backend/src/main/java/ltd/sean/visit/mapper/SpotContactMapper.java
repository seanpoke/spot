package ltd.sean.visit.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ltd.sean.visit.dto.ContactOwnerRow;
import ltd.sean.visit.entity.SpotContact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 联系方式表 Mapper。
 *
 * <p>读写都很简单（按 spot_id 取、整组先删后插），排序在 {@code sortOrder} 上，
 * 靠 QueryWrapper 的 orderByAsc 即可，不需要自定义 SQL。
 *
 * <p>唯一的自定义查询是「联系方式查重」的候选集：一次捞回指定类型的全部联系方式，
 * 连同所属打卡点名。归一化（电话剥分隔符、微信忽略大小写）放在 Service 里做 ——
 * SQL 里堆 REPLACE 链既难读又没法处理「+86 与不带 +86」这种规则。
 * 个人自用的数据量（千级以内）全取一次完全够，别为此再引一张索引表。
 */
@Mapper
public interface SpotContactMapper extends BaseMapper<SpotContact> {

    /**
     * 取指定类型的联系方式 + 所属打卡点名，排除某个打卡点（编辑时的「自己」）。
     *
     * @param types         只取这些类型（同类型才可能重复，跨类型不用比）
     * @param excludeSpotId 要排除的打卡点 id；null 表示不排除（新建场景）
     */
    @Select("<script>"
            + "SELECT c.spot_id AS spotId, s.name AS spotName, c.type AS type, c.value AS value "
            + "FROM spot_contact c JOIN spot s ON s.id = c.spot_id "
            + "WHERE c.type IN "
            + "<foreach collection='types' item='type' open='(' separator=',' close=')'>#{type}</foreach>"
            + "<if test='excludeSpotId != null'> AND c.spot_id &lt;&gt; #{excludeSpotId}</if>"
            + " ORDER BY s.name, c.sort_order"
            + "</script>")
    List<ContactOwnerRow> selectContactsWithSpotName(@Param("types") Collection<String> types,
                                                     @Param("excludeSpotId") String excludeSpotId);
}
