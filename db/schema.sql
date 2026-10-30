-- =============================================================================
-- 个人打卡定位项目 · 数据库结构
-- -----------------------------------------------------------------------------
-- 适用版本：MySQL 8.0
-- 字符集  ：utf8mb4 / utf8mb4_0900_ai_ci（8.0 默认排序规则，支持 emoji）
-- 说明    ：本脚本可重复执行，先建库再建表，表按依赖顺序创建
--           spot 先于 visit / spot_tag，tag 先于 spot_tag
-- =============================================================================

-- 建库：若不存在则创建，统一字符集
CREATE DATABASE IF NOT EXISTS `visit_db`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;


USE `visit_db`;

-- 先删子表再删父表，避免外键约束报错
DROP TABLE IF EXISTS `visit`;
DROP TABLE IF EXISTS `spot_tag`;
DROP TABLE IF EXISTS `tag`;
DROP TABLE IF EXISTS `tag_category`;
DROP TABLE IF EXISTS `spot`;


-- =============================================================================
-- 表：spot —— 打卡点
-- -----------------------------------------------------------------------------
-- 一个打卡点 = 一个场所或一个人物。人物可通过 parent_spot_id 挂靠到场所。
-- 坐标使用 GCJ-02（高德坐标系），与本项目地图底图保持一致。
-- =============================================================================
CREATE TABLE `spot` (
  `id`               CHAR(36)      NOT NULL                COMMENT '打卡点ID，UUID',
  `kind`             ENUM('place','person') NOT NULL DEFAULT 'place'
                                                           COMMENT '类型：place=场所，person=人物',
  `name`             VARCHAR(120)  NOT NULL                COMMENT '名称',
  `avatar`           VARCHAR(255)  DEFAULT NULL            COMMENT '头像路径，人物类型多用',
  `cover`            VARCHAR(255)  DEFAULT NULL            COMMENT '封面图路径，列表与详情头部展示',
  `description`      TEXT                                  COMMENT '服务描述或备注正文',
  `lat`              DECIMAL(10,7) DEFAULT NULL            COMMENT '纬度，GCJ-02 坐标系',
  `lng`              DECIMAL(10,7) DEFAULT NULL            COMMENT '经度，GCJ-02 坐标系',
  `address`          VARCHAR(255)  DEFAULT NULL            COMMENT '地址文本，用于展示与搜索',
  `city`             VARCHAR(60)   DEFAULT NULL            COMMENT '所在城市，用于分组统计',
  `province`         VARCHAR(60)   DEFAULT NULL            COMMENT '省 / 直辖市，地图选点反查地址填，位置卡第一行「省市区」用',
  `district`         VARCHAR(60)   DEFAULT NULL            COMMENT '区 / 县，地图选点反查地址填，位置卡第一行「省市区」用',
  `packages`         JSON                                  COMMENT '价目表，数组：[{"name":"头部按摩","price":88}]，price 只允许正整数或 null',
  `price_min`        INT           DEFAULT NULL            COMMENT '价目里的最低价（元），保存时由 packages 算出，供列表按价格范围筛选',
  `price_max`        INT           DEFAULT NULL            COMMENT '价目里的最高价（元），同上',
  `rank_level`       TINYINT       NOT NULL DEFAULT 0      COMMENT '段位：0青铜 1白银 2黄金 3铂金 4钻石 5星耀 6王者',
  `rank_manual`      TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '段位是否手动锁定：1=手动定过，系统不再给建议值',
  `parent_spot_id`   CHAR(36)      DEFAULT NULL            COMMENT '所属场所ID，人物挂靠用，场所本身为空',
  `status`           ENUM('visited','wishlist','blacklist','unvisited') NOT NULL DEFAULT 'unvisited'
                                                           COMMENT '状态：unvisited=还没去过，visited=已去过，wishlist=想去，blacklist=黑名单',
  `created_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                           COMMENT '创建时间',
  `updated_at`       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                                                           COMMENT '最后更新时间，由数据库自动维护',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)               COMMENT '按状态筛地图标记：未去/已去/想去/黑名单',
  KEY `idx_rank`   (`rank_level`)           COMMENT '按段位筛选与排序',
  KEY `idx_city`   (`city`)                 COMMENT '按城市分组统计 / 地图抽屉按城市取点',
  KEY `idx_geo`    (`lat`, `lng`)           COMMENT '地图视野矩形查询：lat 走范围，lng 在结果集内过滤',
  KEY `idx_parent` (`parent_spot_id`)       COMMENT '查某场所下挂靠的人物列表',
  CONSTRAINT `fk_spot_parent` FOREIGN KEY (`parent_spot_id`)
    REFERENCES `spot` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='打卡点';


-- =============================================================================
-- 表：visit —— 到访记录
-- -----------------------------------------------------------------------------
-- 每次去一次就记一条，同时承载评分、感受、照片，因此不再单独建评论表。
-- rating 是主评分（参与段位计算），scores 是分项评分（仅作参考）。
-- =============================================================================
CREATE TABLE `visit` (
  `id`         CHAR(36)      NOT NULL                      COMMENT '记录ID，UUID',
  `spot_id`    CHAR(36)      NOT NULL                      COMMENT '所属打卡点ID',
  `visited_at` DATE          NOT NULL                      COMMENT '到访日期，只到天',
  `rating`     TINYINT       NOT NULL                      COMMENT '整体星级 1~5，段位计算的依据',
  `scores`     JSON                                        COMMENT '分项评分：[{"key":"env","label":"环境","value":4}]',
  `cost`       DECIMAL(10,2) DEFAULT NULL                  COMMENT '本次花费，单位元',
  `companions` VARCHAR(120)  DEFAULT NULL                  COMMENT '同行人',
  `note`       TEXT                                        COMMENT '一句话感受',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                           COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_spot`    (`spot_id`)              COMMENT '查某个打卡点的全部到访记录',
  KEY `idx_visited` (`visited_at`)           COMMENT '时间线倒序展示',
  CONSTRAINT `fk_visit_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='到访记录';


-- =============================================================================
-- 表：tag_category —— 标签分类（类型）字典
-- -----------------------------------------------------------------------------
-- 标签按分类分组展示（编辑页分组、标签管理页的类型行），分类本身也能在
-- 「我的 → 编辑标签」里新增和删除，所以必须落库而不是写死在前端。
-- tag.category 存的就是这里的 code；两者之间**不建外键**：读接口对未知 code
-- 一律兜底成 other，删分类时也由应用层把标签改归 other —— 有外键反而会让
-- 一次删分类卡在约束上，还可能因为历史脏值连列表都读不出来。
-- other（其他）是兜底分类，删不掉。
-- =============================================================================
CREATE TABLE `tag_category` (
  `code`       VARCHAR(16)  NOT NULL                      COMMENT '分类 code，小写字母开头，最长 16；新建时后端生成，用户只填显示名',
  `label`      VARCHAR(12)  NOT NULL                      COMMENT '显示名，如「人物」「场所」，全库唯一',
  `sort_order` INT          NOT NULL DEFAULT 1            COMMENT '顺序：1 起连续，小的排前面。决定编辑页分组顺序与标签管理页的类型排布',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_tag_category_label` (`label`) COMMENT '类型名不可重复，兼作按名查找',
  KEY `idx_tag_category_sort` (`sort_order`)   COMMENT '类型列表排序'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='标签分类（类型）';

-- 种子分类：顺序即编辑页分组顺序。「其他」必须在，且不能删
INSERT INTO `tag_category` (`code`, `label`, `sort_order`) VALUES
  ('person',  '人物', 1),
  ('place',   '场所', 2),
  ('service', '服务', 3),
  ('vibe',    '氛围', 4),
  ('other',   '其他', 5);


-- =============================================================================
-- 表：tag —— 标签字典
-- -----------------------------------------------------------------------------
-- 用户自己维护的标签库（按摩、木桶浴、日式、安静……），不是代码里写死的枚举。
-- 标签在打卡点上多对多挂载，关系见 spot_tag。
-- 名称唯一的目的是防止「按摩 / 按摩 」这类重复项把筛选列表撑乱。
-- =============================================================================
CREATE TABLE `tag` (
  `id`         CHAR(36)     NOT NULL                      COMMENT '标签ID，UUID',
  `name`       VARCHAR(24)  NOT NULL                      COMMENT '标签名，建议不超过 8 个汉字，全库唯一',
  `color`      VARCHAR(9)   DEFAULT NULL                  COMMENT '标签颜色，前端新建标签时可选，存 #RRGGBB，未选为空',
  `category`   VARCHAR(16)  NOT NULL DEFAULT 'other'       COMMENT '分类 code，对应 tag_category.code（种子：person人物 / place场所 / service服务 / vibe氛围 / other其他）；分类可由用户增删，删分类时其下标签改归 other',
  `sort_order` INT          NOT NULL DEFAULT 1            COMMENT '优先级：1 开始连续，小的排前面。「我的 → 编辑标签」长按拖拽后全量重排，这是标签顺序的唯一来源',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`name`)             COMMENT '标签名不可重复，兼作按名查找',
  KEY `idx_tag_sort` (`sort_order`, `name`)     COMMENT '标签库与候选列表的统一排序'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='标签';


-- =============================================================================
-- 表：spot_tag —— 打卡点标签关系
-- -----------------------------------------------------------------------------
-- 一个打卡点可挂多个标签，一个标签也可被多个打卡点复用。
-- **关系表不存顺序**：标签在哪个点上都按 tag.sort_order 展示（全库优先级，
-- 在「我的 → 编辑标签」里长按拖拽维护）。以前这里有一个 sort_order 表示
-- 「这个标签在这个点上的次序」，等于同一件事两种口径，同一批标签在详情页、
-- 列表页、编辑页里会排出不同顺序 —— 已废弃。
-- =============================================================================
CREATE TABLE `spot_tag` (
  `spot_id`    CHAR(36)  NOT NULL                         COMMENT '打卡点ID',
  `tag_id`     CHAR(36)  NOT NULL                         COMMENT '标签ID',
  `created_at` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                         COMMENT '打标签时间',
  PRIMARY KEY (`spot_id`, `tag_id`),
  KEY `idx_st_spot` (`spot_id`)                COMMENT '按打卡点取标签（顺序由 tag.sort_order 决定）',
  KEY `idx_st_tag`  (`tag_id`)                 COMMENT '按标签反查打卡点，筛选与统计用',
  CONSTRAINT `fk_st_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_st_tag` FOREIGN KEY (`tag_id`)
    REFERENCES `tag` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='打卡点标签关系';


-- =============================================================================
-- 表：spot_contact —— 打卡点联系方式
-- -----------------------------------------------------------------------------
-- 为什么单独一张表：联系方式要「一个类型多条」（两个手机号、两个微信号），
-- 固定列只能一格一值，加类型还得改表结构。拆表后类型是数据不是列，
-- 以后加「小红书 / 抖音」只改 CONTACT_TYPES 常量，不动 DDL。
-- sort_order = 用户的添加顺序，也是详情页的展示顺序（同类型多条按它排）。
-- 删除打卡点由外键 ON DELETE CASCADE 连带清掉，不需要业务代码手动删。
-- =============================================================================
CREATE TABLE `spot_contact` (
  `id`         CHAR(36)     NOT NULL                      COMMENT '联系方式行ID，UUID',
  `spot_id`    CHAR(36)     NOT NULL                      COMMENT '所属打卡点ID',
  `type`       VARCHAR(16)  NOT NULL                      COMMENT '类型：phone=电话，wechat=微信，qq=QQ，telegram=Telegram，youyu=「与你」',
  `value`      VARCHAR(120) NOT NULL                      COMMENT '值：电话号码 / 微信号 / QQ号 / 用户名 / ID',
  `sort_order` INT          NOT NULL DEFAULT 0            COMMENT '展示顺序，小在前（即用户的添加顺序）',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '添加时间',
  PRIMARY KEY (`id`),
  KEY `idx_contact_spot` (`spot_id`, `sort_order`)        COMMENT '按打卡点取联系方式并保序（详情页主用）',
  CONSTRAINT `fk_contact_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='打卡点联系方式（多类型、同类型可多条）';


-- =============================================================================
-- 表：photo —— 统一照片表
-- -----------------------------------------------------------------------------
-- 打卡点照片与到访记录照片都存这里，用 owner_type + owner_id 区分归属：
--   owner_type = 'spot'  → owner_id 指向 spot.id
--   owner_type = 'visit' → owner_id 指向 visit.id
-- 不在 spot / visit 表里再留 JSON 照片列：列表接口从不取照片（带宽小），
-- 只在查「单条详情」时按 owner 联表取出，避免大字段污染所有查询。
-- 一条照片 = 一个文件的三档派生（large/thumb 同文件，只存相对路径）。
-- 删除打卡点 / 到访记录时，由业务逻辑在事务里按 owner 清掉对应行（无外键，跨两种 owner 类型）。
-- =============================================================================
CREATE TABLE `photo` (
  `id`         CHAR(36)      NOT NULL                      COMMENT '照片行ID，UUID',
  `owner_type` VARCHAR(16)   NOT NULL                      COMMENT '归属类型：spot=打卡点，visit=到访记录',
  `owner_id`   CHAR(36)      NOT NULL                      COMMENT '归属ID：spot.id 或 visit.id',
  `sort_order` INT           NOT NULL DEFAULT 0            COMMENT '同归属内的展示顺序，小在前',
  `image_id`   VARCHAR(64)   DEFAULT NULL                  COMMENT '文件名里的 UUID，便于去重与缓存比对',
  `large`      VARCHAR(255)  DEFAULT NULL                  COMMENT '1280 长边图，相对路径 /uploads/...',
  `thumb`      VARCHAR(255)  DEFAULT NULL                  COMMENT '400 长边缩略图，相对路径 /uploads/...',
  `width`      INT           DEFAULT NULL                  COMMENT 'large 实际宽',
  `height`     INT           DEFAULT NULL                  COMMENT 'large 实际高',
  `size`       BIGINT        DEFAULT NULL                  COMMENT 'large 字节数',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_photo_owner` (`owner_type`, `owner_id`, `sort_order`)
                                                        COMMENT '按归属取照片并按展示顺序排（详情页主用）'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='统一照片表：打卡点与到访记录共用';


-- =============================================================================
-- 常用查询示例
-- -----------------------------------------------------------------------------
-- 以下 SQL 供开发时参考，同样遵循全部写注释的约定。
-- 注意 MySQL 8.0 默认开启 ONLY_FULL_GROUP_BY，GROUP BY 时非聚合字段必须出现在分组中。
-- =============================================================================

-- 地图主页：拉取轻量字段供前端按视野过滤（不做空间索引，数据量小）
SELECT `id`, `lat`, `lng`, `rank_level`, `kind`, `status`, `name`, `avatar`
FROM `spot`
WHERE `lat` IS NOT NULL AND `lng` IS NOT NULL;

-- 详情页：某打卡点的建议段位依据（平均星级 + 到访次数 + 最近一次星级）
SELECT
  COUNT(*)        AS visit_count,   -- 到访次数，用于判断是否够 3 次
  AVG(`rating`)   AS avg_rating,    -- 平均星级，用于映射段位
  MAX(`visited_at`) AS last_visit   -- 最近一次到访日期
FROM `visit`
WHERE `spot_id` = ?;

-- 统计看板：按段位统计打卡点数量
SELECT
  `rank_level`      AS rank_level,  -- 段位 0~6
  COUNT(*)          AS spot_count   -- 该段位下的打卡点数量
FROM `spot`
WHERE `status` = 'visited'
GROUP BY `rank_level`
ORDER BY `rank_level` DESC;

-- 统计看板：某年度到访次数与总花费
SELECT
  COUNT(*)         AS visit_count,  -- 该年度到访总次数
  SUM(`cost`)      AS total_cost    -- 该年度总花费，未填花费的记录不计
FROM `visit`
WHERE YEAR(`visited_at`) = ?;

-- 详情页：某场所下挂靠的人物
SELECT `id`, `name`, `avatar`, `rank_level`
FROM `spot`
WHERE `parent_spot_id` = ?
ORDER BY `rank_level` DESC;

-- 列表页：一次取回一批打卡点的标签，避免逐个查（N+1）
-- 后端拿到结果后按 spot_id 归组，再按 list 顺序截断展示
SELECT
  st.`spot_id`      AS spot_id,   -- 归组用的打卡点ID
  t.`id`            AS tag_id,    -- 标签ID
  t.`name`          AS tag_name   -- 标签名，前端直接展示
FROM `spot_tag` st
JOIN `tag` t ON t.`id` = st.`tag_id`
WHERE st.`spot_id` IN (?, ?, ?)
ORDER BY st.`spot_id`, t.`sort_order`, t.`name`;

-- 按标签筛打卡点：点某一标签，列出所有挂了它的打卡点
SELECT s.`id`, s.`name`, s.`cover`, s.`rank_level`, s.`status`
FROM `spot_tag` st
JOIN `spot` s ON s.`id` = st.`spot_id`
WHERE st.`tag_id` = ?
ORDER BY s.`rank_level` DESC;

-- 标签库：带使用次数，用于「常用标签」排序与清理没人用的标签
SELECT
  t.`id`    AS tag_id,      -- 标签ID
  t.`name`  AS tag_name,    -- 标签名
  COUNT(st.`tag_id`) AS use_count  -- 被多少个打卡点使用，0 表示闲置
FROM `tag` t
LEFT JOIN `spot_tag` st ON st.`tag_id` = t.`id`
GROUP BY t.`id`, t.`name`, t.`sort_order`
ORDER BY t.`sort_order`, use_count DESC;

-- 保存某打卡点的标签：整组覆盖（先删后插），在一个事务里做
DELETE FROM `spot_tag` WHERE `spot_id` = ?;
INSERT INTO `spot_tag` (`spot_id`, `tag_id`) VALUES (?, ?), (?, ?);
