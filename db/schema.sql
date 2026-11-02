-- 个人打卡定位项目 · 数据库结构
-- MySQL 8.0 / utf8mb4；可重复执行；坐标统一 GCJ-02

CREATE DATABASE IF NOT EXISTS `visit_db`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE `visit_db`;

-- 先删子表再删父表
DROP TABLE IF EXISTS `visit`;
DROP TABLE IF EXISTS `spot_tag`;
DROP TABLE IF EXISTS `tag`;
DROP TABLE IF EXISTS `tag_category`;
DROP TABLE IF EXISTS `base_config`;
DROP TABLE IF EXISTS `spot`;

-- 打卡点：场所或人物，人物用 parent_spot_id 挂靠场所
CREATE TABLE `spot` (
  `id`             CHAR(36)      NOT NULL                COMMENT 'UUID',
  `kind`           ENUM('place','person') NOT NULL DEFAULT 'place' COMMENT 'place=场所，person=人物',
  `name`           VARCHAR(120)  NOT NULL                COMMENT '名称',
  `avatar`         VARCHAR(255)  DEFAULT NULL            COMMENT '头像路径',
  `cover`          VARCHAR(255)  DEFAULT NULL            COMMENT '封面路径',
  `description`    TEXT                                  COMMENT '描述 / 备注',
  `lat`            DECIMAL(10,7) DEFAULT NULL            COMMENT '纬度，GCJ-02',
  `lng`            DECIMAL(10,7) DEFAULT NULL            COMMENT '经度，GCJ-02',
  `address`        VARCHAR(255)  DEFAULT NULL            COMMENT '地址文本',
  `city`           VARCHAR(60)   DEFAULT NULL            COMMENT '城市',
  `province`       VARCHAR(60)   DEFAULT NULL            COMMENT '省 / 直辖市',
  `district`       VARCHAR(60)   DEFAULT NULL            COMMENT '区 / 县',
  `packages`       JSON                                  COMMENT '价目表：[{"name":"头部按摩","price":88}]',
  `price_min`      INT           DEFAULT NULL            COMMENT '最低价（元），由 packages 算出',
  `price_max`      INT           DEFAULT NULL            COMMENT '最高价（元），由 packages 算出',
  `rank_level`     TINYINT       NOT NULL DEFAULT 0      COMMENT '段位：0青铜 1白银 2黄金 3铂金 4钻石 5星耀 6王者',
  `rank_manual`    TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '1=手动定过段位',
  `parent_spot_id` CHAR(36)      DEFAULT NULL            COMMENT '所属场所 ID',
  `status`         ENUM('visited','wishlist','blacklist','unvisited') NOT NULL DEFAULT 'unvisited'
                                                         COMMENT 'unvisited / visited / wishlist / blacklist',
  `created_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`),
  KEY `idx_rank`   (`rank_level`),
  KEY `idx_city`   (`city`),
  KEY `idx_geo`    (`lat`, `lng`),
  KEY `idx_parent` (`parent_spot_id`),
  CONSTRAINT `fk_spot_parent` FOREIGN KEY (`parent_spot_id`)
    REFERENCES `spot` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打卡点';

-- 到访记录：一次到访一条，评分与感受都在这里
CREATE TABLE `visit` (
  `id`         CHAR(36)      NOT NULL                     COMMENT 'UUID',
  `spot_id`    CHAR(36)      NOT NULL                     COMMENT '所属打卡点 ID',
  `visited_at` DATE          NOT NULL                     COMMENT '到访日期',
  `rating`     TINYINT       NOT NULL                     COMMENT '整体星级 1~5',
  `scores`     JSON                                       COMMENT '分项评分：[{"key":"env","label":"环境","value":4}]',
  `cost`       DECIMAL(10,2) DEFAULT NULL                 COMMENT '花费（元）',
  `companions` VARCHAR(120)  DEFAULT NULL                 COMMENT '同行人',
  `note`       TEXT                                       COMMENT '一句话感受',
  `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_spot`    (`spot_id`),
  KEY `idx_visited` (`visited_at`),
  CONSTRAINT `fk_visit_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='到访记录';

-- 标签分类（类型）字典；other 为兜底，删不掉
CREATE TABLE `tag_category` (
  `code`       VARCHAR(16) NOT NULL                       COMMENT '分类 code，后端生成',
  `label`      VARCHAR(12) NOT NULL                       COMMENT '显示名，全库唯一',
  `sort_order` INT         NOT NULL DEFAULT 1             COMMENT '顺序，1 起连续',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`code`),
  UNIQUE KEY `uk_tag_category_label` (`label`),
  KEY `idx_tag_category_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签分类（类型）';

INSERT INTO `tag_category` (`code`, `label`, `sort_order`) VALUES
  ('person',  '人物', 1),
  ('place',   '场所', 2),
  ('service', '服务', 3),
  ('vibe',    '氛围', 4),
  ('other',   '其他', 5);

-- 标签字典；顺序唯一来源是 sort_order
CREATE TABLE `tag` (
  `id`         CHAR(36)    NOT NULL                       COMMENT 'UUID',
  `name`       VARCHAR(24) NOT NULL                       COMMENT '标签名，全库唯一',
  `color`      VARCHAR(9)  DEFAULT NULL                   COMMENT '#RRGGBB，未选为空',
  `category`   VARCHAR(16) NOT NULL DEFAULT 'other'       COMMENT '分类 code，对应 tag_category.code',
  `sort_order` INT         NOT NULL DEFAULT 1             COMMENT '顺序，1 起连续',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`name`),
  KEY `idx_tag_sort` (`sort_order`, `name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标签';

-- 打卡点与标签的多对多关系；标签顺序只认 tag.sort_order
CREATE TABLE `spot_tag` (
  `spot_id`    CHAR(36) NOT NULL                          COMMENT '打卡点 ID',
  `tag_id`     CHAR(36) NOT NULL                          COMMENT '标签 ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '打标签时间',
  PRIMARY KEY (`spot_id`, `tag_id`),
  KEY `idx_st_spot` (`spot_id`),
  KEY `idx_st_tag`  (`tag_id`),
  CONSTRAINT `fk_st_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_st_tag` FOREIGN KEY (`tag_id`)
    REFERENCES `tag` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打卡点标签关系';

-- 打卡点联系方式；同类型可多条，sort_order 即添加顺序
CREATE TABLE `spot_contact` (
  `id`         CHAR(36)     NOT NULL                      COMMENT 'UUID',
  `spot_id`    CHAR(36)     NOT NULL                      COMMENT '所属打卡点 ID',
  `type`       VARCHAR(16)  NOT NULL                      COMMENT 'phone / wechat / qq / telegram / youyu',
  `value`      VARCHAR(120) NOT NULL                      COMMENT '号码 / 微信号 / 用户名',
  `sort_order` INT          NOT NULL DEFAULT 0            COMMENT '展示顺序',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
  PRIMARY KEY (`id`),
  KEY `idx_contact_spot` (`spot_id`, `sort_order`),
  CONSTRAINT `fk_contact_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='打卡点联系方式';

-- 统一照片表：owner_type 区分 spot / visit，无外键，删除时由业务清理
CREATE TABLE `photo` (
  `id`         CHAR(36)     NOT NULL                      COMMENT 'UUID',
  `owner_type` VARCHAR(16)  NOT NULL                      COMMENT 'spot / visit',
  `owner_id`   CHAR(36)     NOT NULL                      COMMENT 'spot.id 或 visit.id',
  `sort_order` INT          NOT NULL DEFAULT 0            COMMENT '展示顺序',
  `image_id`   VARCHAR(64)  DEFAULT NULL                  COMMENT '文件名里的 UUID',
  `large`      VARCHAR(255) DEFAULT NULL                  COMMENT '1280 长边图，/uploads/...',
  `thumb`      VARCHAR(255) DEFAULT NULL                  COMMENT '400 长边缩略图，/uploads/...',
  `width`      INT          DEFAULT NULL                  COMMENT 'large 宽',
  `height`     INT          DEFAULT NULL                  COMMENT 'large 高',
  `size`       BIGINT       DEFAULT NULL                  COMMENT 'large 字节数',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  PRIMARY KEY (`id`),
  KEY `idx_photo_owner` (`owner_type`, `owner_id`, `sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='照片（打卡点与到访共用）';

-- 基础配置（业务字典）；key / value / desc 是关键字，SQL 里必须加反引号
CREATE TABLE `base_config` (
  `id`         BIGINT      NOT NULL AUTO_INCREMENT        COMMENT '自增主键，同一 code 下即展示顺序',
  `code`       VARCHAR(32) NOT NULL                       COMMENT '业务编码，如 priceItem',
  `key`        VARCHAR(32) NOT NULL                       COMMENT '配置项，如 1P',
  `value`      VARCHAR(64) NOT NULL DEFAULT ''            COMMENT '配置值，统一按字符串存；priceItem 里是默认价格，0 不预填',
  `desc`       VARCHAR(64) NOT NULL DEFAULT ''            COMMENT '说明，只给人看',
  `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bc_code_key` (`code`, `key`),
  KEY `idx_bc_code` (`code`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='基础配置（业务字典）';

INSERT INTO `base_config` (`code`, `key`, `value`, `desc`) VALUES
  ('priceItem', '1P', '0', '一次'),
  ('priceItem', '2P', '0', '两次'),
  ('priceItem', '夜', '0', '过夜');
