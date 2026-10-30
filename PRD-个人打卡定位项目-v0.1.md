# 个人打卡定位项目 · 产品方案 v0.11

> 定位：**个人专属的城市生活地图与记忆库**。不是对外平台，是自用工具。
> 版本：v0.11（工程骨架落地，可直接开工）
> 访问入口：`https://visit.sean.ltd`

## 修订记录

| 版本 | 变更 |
|---|---|
| v0.1 | 首版方案：定位、功能结构、页面清单、段位规则、技术选型 |
| v0.2 | 技术栈迁移至 MySQL + Redis + Nginx；确定段位配色与地图标记；价目表简化；增加分项评分 |
| v0.3 | 确认 MySQL 8.0 / Redis DB3 / Nginx 自管；新增图片压缩方案；新增 V1 任务拆解 |
| v0.3.1 | DDL 全量补注释；补外键约束；新增 `db/schema.sql` |
| v0.4 | ① **后端切换为 Java**（Spring Boot 3 + JDK 21 + MyBatis-Plus），前端为 Vue 3 + TypeScript<br>② 分项评分取消按类型区分，统一四项且允许留空<br>③ **图片格式由 WebP 改为 JPEG**，改用 Thumbnailator 实现零原生依赖<br>④ 新增「域名可配置」设计<br>⑤ 新增 `AGENTS.md` 供 AI 后续维护 |
| v0.5 | ① 修正压缩分工：前端做**唯一一次**有损压缩，后端不重压 large，避免两次压缩叠加损失<br>② large 由 1600px 降至 **1280px**，三档合计约 325MB<br>③ 新增 **CDN 支持**：库内只存相对路径，`CDN_BASE_URL` 拼接<br>④ 新增「小带宽专项优化」（逐张上传、分级加载、懒加载）<br>⑤ 新增 JVM 内存与连接池约束（小内存机器） |
| v0.6 | ① 按**实测服务器数据**（4C/4G/40G盘/300G流量）重定资源参数，JVM 堆 384m → **512m**<br>② **CDN 结论修正**：流量配额不是约束（用量 0.24%），改为**首版不接 CDN**，只保留开关<br>③ 新增容器 `mem_limit` 硬上限与 `ExitOnOutOfMemoryError`，防止应用拖垮同机 MySQL<br>④ 新增 Docker 日志轮转、Nginx gzip、`/uploads` 长缓存头三条带宽优化 |
| v0.7 | ① 带宽确认为 **3 Mbps**，按此核算首屏耗时，**large 保持 1280px 不降级**<br>② **CDN 结论再次修正并定稿**：域名 `images.sean.ltd` 已存在，改为**接入**，并补输出 CDN 侧六条必对齐配置<br>③ 明确 `/uploads/**` **不做口令校验**及背后的安全取舍（含"回源令牌"备选方案）<br>④ 说明「接入 CDN 后首次访问可能更慢」的反直觉事实，避免上线误判 |
| v0.8 | ① **视觉定稿为「扫街榜」明亮卡片风**，新增 5.7 视觉规范整章（色板 / 卡片判据 / 标记 / 面板 / 控件 / 排版禁忌）<br>② **段位七色换新**（旧色白字压黄金仅 2.9:1，且黄金与王者色相只差 15°），并改为**只出现在 7 段刻度条里**，废弃「填充色块 + 白字」与「数字角标」<br>③ **5.3 地图标记重写**：环 = 状态（4 色）、条 = 等级（7 段），避雷改为浅灰环 + 头像去色，取消斜杠<br>④ 澄清 5.1「系统建议」不等于「不能定级」，未到访的点照样可手动定任意段位<br>⑤ 页面清单补「统计」页 |
| v0.9 | ① **部署章节按服务器实际状态重写**：Nginx 是五站点共用的公用实例，本应用改为「纯增量接入」，`sean.conf` 不改<br>② **探测更正 CDN 结论**：`images.sean.ltd` 确为腾讯云 EdgeOne（CNAME `eo.dnse4.com`），回源直落 Nginx 静态直出、不经 JVM；上一版「是裸 Nginx、CDN 配置作废」的判断作废<br>③ 反代地址由 `spot-api:8080` 改为 **`host.docker.internal:8080`**（跨 compose project 无法用容器名解析）<br>④ 查明**公共 `conf/nginx.conf` 未挂载进容器**，其 `gzip on` 从未生效；`gzip` 与 `client_max_body_size` 改写在 `visit.conf` 的 server 块<br>⑤ 新增 `deploy/nginx/visit.conf`，明确「`*.sean.ltd` 会吞掉 `visit.sean.ltd`」与「图片目录必须挂到 `picture_bed/upload/uploads`」两条硬约束<br>⑥ **修正缓存头作用的表述**：原文「不加就每次重新下载、是最贵的浪费」夸大了 —— EdgeOne 命中时本来就不回源，缓存头的真实收益是「省浏览器条件请求的一次往返」+「给 EdgeOne 一个可遵循的 TTL」，属有收益非红线<br>⑦ 回填 EdgeOne 控制台实测配置：源站 `<服务器IP>`、回源 Host「使用加速域名」、回源协议「协议跟随」（实测**可保留**，80 端口有 301 兜底且 `$host` 拼接正确） |
| v0.10 | ① **缓存规则按控制台核实并定性**：自定义 40 天 + **强制缓存开启**，因此 EdgeOne **不读源站 `Cache-Control`**，7.7 第 ③ 步那条缓存头的收益下调为「只影响浏览器段与直连兜底」，原先「给 EdgeOne 一个可遵循的 TTL」的说法作废<br>② **实测发现 404 会被缓存 40 天**（MISS → HIT → HIT），403 不缓存；补三条对策（只用真实图片测、加随机参数绕开、控制台刷缓存），这是联调期最容易误判成「图片上传失败」的坑<br>③ **新增「防盗链规则」一节**：回填控制台实际条件与 10 行实测矩阵，确认白名单基本有效（含 `http`、多级子域、微信 `servicewechat.com`、空 Referer 放行），但发现 `.*` 结尾导致 **`https://sean.ltd.cn.evil.com/` 这类后缀可绕过**，给出最小修复 `(/|$)`<br>④ 说明防盗链与 5.5「不做口令校验、靠 UUID 兜底」的关系：新增一层但不改变原结论 |
| v0.11 | ① **工程骨架落地**：仓库名 `spot`、本地路径 `E:\sourceCode\spot`，前后端同仓；新增 `.gitignore` / `.dockerignore` / `deploy/docker-compose.dev.yml` / `deploy/Dockerfile` / `deploy/docker-compose.yml` / `deploy/.env.example`<br>② **构建策略定案：镜像在本地构建，服务器只 `docker load` + `up`**。服务器 4GB 已用 2GB，Maven 构建会临时吃 1～1.5GB 并与数据库抢内存，故 `Dockerfile` 不做多阶段构建，只 `COPY target/*.jar`（见 7.6.1）<br>③ **容器与镜像名由 `visit-api` 统一为 `spot-api`**（跟仓库走）；域名 `visit.sean.ltd`、服务器目录 `/home/ubuntu/app/visit`、数据库 `visit_db` 保持不动——`spot.sean.ltd` 实测公网无解析<br>④ 环境变量表补「归属」列，明确 `AMAP_KEY` / `AMAP_SECURITY_CODE` 属前端（`frontend/.env.local`，构建时注入、天然公开），`AMAP_WEB_KEY` 才是后端的；`REDIS_URL` 拆成 `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE`，让「固定 DB 3」这条硬约束显式可见 |
| v0.12 | ① **构建策略改案：镜像改由 GitHub Actions 构建并推送到 GHCR**（`ghcr.io/seanpoke/spot`），服务器只 `docker compose pull` + `up -d`。v0.11 的「本地构建 + `docker save/load`」作废——本地 Windows 构建、手工 gzip 传输太脆，而 GitHub runner 本来就免费。**服务器内存不足这条理由依然成立，只是解法从"本地编译"换成"云端编译"**<br>② `deploy/Dockerfile` 改回多阶段（`maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`），`.dockerignore` 相应反转：必须放行 `backend/pom.xml` 与 `backend/src`，否则构建在 COPY 就失败<br>③ 新增 `.github/workflows/docker-publish.yml`：`push` 到 `main` 且改动 `backend/**` 触发，`workflow_dispatch` 可手工重发；标签 `latest` + `sha-<短哈希>`（回滚用）+ 分支名；启用 `type=gha` 构建缓存<br>④ 补充 GHCR 两个实操坑：**包默认 private**（即使仓库 public）需手动改可见性或 `docker login`；**国内服务器拉 ghcr.io 可能很慢**，兜底方案是同时推 TCR/ACR<br>⑤ **补记地图底图规范**：5.7 新增「地图底图：只用浅色，六色固定」小节。此前"暗色地图白天看不清"这条结论只存在于设计讨论中，没落到任何文档，交给下一个 AI 时很可能重做一版暗色 |

---

## 一、产品定位

### 一句话定义

在地图上记录我去过和想去的地方/人，用星级和段位表达我对它的判断，并保存照片、联系方式、价目和当时的感受。

### 它解决什么问题

| 现状痛点 | 本产品如何解决 |
|---|---|
| 好店记不住，下次去同一个城市想不起来 | 地图可视化，按位置直接看到附近有什么 |
| 联系方式散落在微信、备忘录、截图里 | 统一挂到打卡点上，一处查找 |
| 只记得"去过"但忘了体验好坏 | 每次到访记一条，带星级和一句话 |
| 平台评分不可信，自己想留个判断 | 自己的段位定级，王者到青铜，你说算 |
| 数据存在别人的平台上，随时可能没了 | 自建 + 定期导出，数据完全自主 |

### 目标用户

**唯一用户：你自己。** 单口令访问，无注册、无登录、无多租户。

### 核心场景

1. **在店里当场记录**：打开地图 → 定位到我 → 新建打卡点 → 拍照 → 填联系方式/价目 → 定段位
2. **出门前查附近**：打开地图 → 看当前视野内有哪些点 → 点开看详情和上次评价
3. **回访更新**：又去了一次 → 在该点下新增一条到访记录 → 系统提示是否调整段位
4. **回顾**：翻狂人日志，看今年去了哪些地方、花了多少钱

---

## 二、功能结构

### 模块与优先级

| 模块 | 说明 | 优先级 |
|---|---|---|
| **口令访问** | 单口令签发会话，Redis 存储，防暴力破解 | P0 |
| **地图主页** | 地图 + 按段位着色标记 + 底部抽屉列表 + 当前视野查询 | P0 |
| **打卡点管理** | 新建 / 编辑 / 删除；场所或人物两种类型；人物可挂靠场所 | P0 |
| **到访记录** | 每次一条：日期、整体星级、分项评分、花费、同行人、文字、照片 | P0 |
| **图片处理** | 前端预压 + 服务端派生三档 JPEG | P0 |
| **段位定级** | 七级段位，手动为主 + 系统建议 | P0 |
| **标签** | 自维护标签字典 + 打卡点多对多挂标签，列表页展示、按标签筛选 | P0 |
| **详情页** | 封面、段位数据卡、标签、相册、价目表、联系方式、到访记录 | P0 |
| **数据导出** | JSON / CSV 全量导出 | P0 |
| **搜索与筛选** | 关键词、标签、段位、类型、距离 | P1 |
| **待去清单** | 标记"想去"，地图上用空心标记区分 | P1 |
| **统计看板** | 总数、按段位分布、按城市分布、年度到访与花费 | P1 |
| **PWA 离线** | 可安装到手机主屏，弱网可用 | P2 |
| **安卓打包** | Capacitor 包装成 APK | P2 |

### 明确不做（Non-goals）

- ❌ 用户注册、登录、多账号体系（口令门足够）
- ❌ 内容审核、举报、风控（内容全部由你自己录入）
- ❌ 在线预约、支付、订单（只记录价格，不产生交易）
- ❌ 站内即时通讯（联系方式是拿来"看"的，不是拿来聊的）
- ❌ 分享页、公开主页、SEO（**正因为不做 SEO，才不需要 SSR**）
- ❌ 推荐算法、地图聚合（一两百个点不需要）
- ❌ 多语言
- ❌ 视频、动图（图片够了，视频会把存储和复杂度拉高一个数量级）

---

## 三、页面清单

| 页面 | 路由 | 核心内容 |
|---|---|---|
| 口令门 | `/gate` | 单输入框，验证后写长期 Cookie |
| 地图主页 | `/` | 全屏地图 + 白胶囊标记（环表状态、条表等级）+ 三档底部面板（单卡摘要 / 列表 / 全屏）+ 定位按钮 + 新建入口 |
| 打卡点详情 | `/spot/:id` | 封面 + 段位数据卡 + 标签 + 相册 + 价目 + 联系方式 + 到访记录 + 操作区 |
| 新建/编辑 | `/spot/:id/edit` | 单页表单，支持从地图选点带入坐标；标签可多选、可即时新建 |
| 到访录入 | `/spot/:id/visit/new` | 日期、星级、分项评分、花费、同行人、文字、照片 |
| 狂人日志 | `/timeline` | 全量到访记录倒序，按月份分组（页面名 v0.8 起为「狂人日志」，路由不变） |
| 统计 | `/stats` | 段位分布（7 段条）、每月到访、去得最多前 3、总量数字 |
| 设置 | `/settings` | 导出、备份、口令修改 |

**移动端适配**：所有页面移动优先设计，窄屏即为手机布局。第一版做成响应式，已经能在手机上用。

---

## 四、数据模型（MySQL 8.0）

> 完整可执行脚本见 `db/schema.sql`，所有表、字段、索引均带注释。
> 字符集 `utf8mb4` / `utf8mb4_0900_ai_ci`；注意 MySQL 8 默认开启 `ONLY_FULL_GROUP_BY`。

### 4.1 spot（打卡点）

```sql
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
  `contact_qq`       VARCHAR(60)   DEFAULT NULL            COMMENT 'QQ号',
  `contact_telegram` VARCHAR(60)   DEFAULT NULL            COMMENT 'Telegram 用户名',
  `contact_wechat`   VARCHAR(60)   DEFAULT NULL            COMMENT '微信号',
  `contact_youyu`    VARCHAR(60)   DEFAULT NULL            COMMENT '「与你」ID',
  `packages`         JSON                                  COMMENT '价目表，数组：[{"name":"头部按摩","price":88}]',
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
  KEY `idx_city`   (`city`)                 COMMENT '按城市分组统计',
  KEY `idx_parent` (`parent_spot_id`)       COMMENT '查某场所下挂靠的人物列表',
  CONSTRAINT `fk_spot_parent` FOREIGN KEY (`parent_spot_id`)
    REFERENCES `spot` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='打卡点';
```

`packages` 结构（已简化，去掉议价、套餐组合、时长、说明）：

```json
[
  { "name": "头部按摩", "price": 88 },
  { "name": "木桶浴",   "price": 198 }
]
```

> 若某条项目需要补充说明（如"90 分钟"），直接写进 `name`，不额外设字段。

### 4.2 visit（到访记录）

```sql
CREATE TABLE `visit` (
  `id`         CHAR(36)      NOT NULL                      COMMENT '记录ID，UUID',
  `spot_id`    CHAR(36)      NOT NULL                      COMMENT '所属打卡点ID',
  `visited_at` DATE          NOT NULL                      COMMENT '到访日期，只到天',
  `rating`     TINYINT       NOT NULL                      COMMENT '整体星级 1~5，段位计算的依据',
  `scores`     JSON                                        COMMENT '分项评分：[{"key":"env","label":"环境","value":4}]',
  `cost`       DECIMAL(10,2) DEFAULT NULL                  COMMENT '本次花费，单位元',
  `companions` VARCHAR(120)  DEFAULT NULL                  COMMENT '同行人',
  `note`       TEXT                                        COMMENT '一句话感受',
  `photos`     JSON                                        COMMENT '照片数组：[{"id":"uuid","large":"/uploads/..","thumb":"/uploads/..","w":1600,"h":1200,"size":214000}]',
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
```

**两条外键的含义**：
- `fk_visit_spot ... ON DELETE CASCADE` —— 删掉打卡点时，它的到访记录自动一起删，不留孤儿数据
- `fk_spot_parent ... ON DELETE SET NULL` —— 删掉场所时，挂靠的人物不删，只解除关系

**用 `visit` 代替独立的评论表**：每次到访本身就是一条带评分、文字、照片的记录，这正是"评论"的全部内容。

### 4.3 tag（标签）

标签是**你自己维护的一份字典**，不是代码里写死的分类枚举。同一个标签可以被任意多个打卡点复用，改一次名字全站生效。

```sql
CREATE TABLE `tag` (
  `id`         CHAR(36)     NOT NULL                      COMMENT '标签ID，UUID',
  `name`       VARCHAR(24)  NOT NULL                      COMMENT '标签名，建议不超过 8 个汉字，全库唯一',
  `sort_order` INT          NOT NULL DEFAULT 0            COMMENT '排序值，小的排前面：列表截断时靠前的先露脸',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`name`)             COMMENT '标签名不可重复，兼作按名查找',
  KEY `idx_tag_sort` (`sort_order`, `name`)     COMMENT '标签库与候选列表的统一排序'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='标签';
```

### 4.4 spot_tag（打卡点标签关系）

```sql
CREATE TABLE `spot_tag` (
  `spot_id`    CHAR(36)  NOT NULL                         COMMENT '打卡点ID',
  `tag_id`     CHAR(36)  NOT NULL                         COMMENT '标签ID',
  `sort_order` INT       NOT NULL DEFAULT 0               COMMENT '该标签在此打卡点上的展示顺序，小在前',
  `created_at` DATETIME  NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                          COMMENT '打标签时间',
  PRIMARY KEY (`spot_id`, `tag_id`),
  KEY `idx_st_spot` (`spot_id`, `sort_order`)  COMMENT '按打卡点取标签并按展示顺序排，列表页主用',
  KEY `idx_st_tag`  (`tag_id`)                 COMMENT '按标签反查打卡点，筛选与统计用',
  CONSTRAINT `fk_st_spot` FOREIGN KEY (`spot_id`)
    REFERENCES `spot` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_st_tag` FOREIGN KEY (`tag_id`)
    REFERENCES `tag` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='打卡点标签关系';
```

**为什么把标签从 `spot.tags` JSON 拆成两张表**：JSON 数组里存的是字符串，改标签名要扫全表、按标签筛选无从下手、同一个标签写成"按摩 / 按摩 "也发现不了。拆表之后改名、筛选、统计使用次数都是普通 SQL，代价只是多一次 JOIN——数据量一两百个点，这点开销可以忽略。

**两条外键都是 CASCADE**：删打卡点连带清关系，删标签连带清关系，两边都不会留垃圾。

### 4.5 分项评分（scores）

**取消按类型区分模板，统一为四项**，场所和人物都用同一套：

| key | 名称 | 用于 |
|---|---|---|
| `env` | 环境 | 场地条件、氛围、整洁度 |
| `service` | 服务 | 态度、响应、流程 |
| `tech` | 技术 | 手法、专业度、出品质量 |
| `value` | 性价比 | 价格与体验的匹配度 |

存储格式（`value` 允许为 `null`，表示该项不适用、不评）：

```json
[
  { "key": "env",     "label": "环境",   "value": 4 },
  { "key": "service", "label": "服务",   "value": 5 },
  { "key": "tech",    "label": "技术",   "value": null },
  { "key": "value",   "label": "性价比", "value": 3 }
]
```

**为什么允许 null**：餐厅没有"技术"可评，技师也没有"环境"可评。强行要求四项全填，你会在录入时随手打分了事，数据反而失真。留空是更诚实的选择。

**计算均分时必须跳过 null**，不要当成 0 分把结果拉低。

**分项定义放在 `frontend/src/config/scoreDimensions.ts`** —— 后端只存 JSON 不关心结构。将来要加第五项，改这一个文件即可，不用动表。

**分项与整体分的关系**：
- `rating`（整体星级）仍是**主评分**，参与段位计算
- 打完分项后自动推荐 `rating = round(已填项均值)`，但**可以手动改**
- 理由：整体感受不等于分项平均。环境差但师傅技术好，你可能就是想给 5 星

### 4.6 photos（到访照片）

```json
[
  {
    "id": "uuid",
    "large": "/uploads/2026/10/xxx_l.jpg",
    "thumb": "/uploads/2026/10/xxx_t.jpg",
    "w": 1600, "h": 1200, "size": 214000
  }
]
```

> ⚠️ **只存相对路径**，不存完整 URL。这是换域名不废的关键（见 7.6）。

---

## 五、核心规则

### 5.1 段位体系

七级，从低到高：**青铜 → 白银 → 黄金 → 铂金 → 钻石 → 星耀 → 王者**

语义：**你对这个点的推荐等级**，不是别人的评价。

- 王者 = 闭眼推荐，谁问我都说好
- 星耀 = 很值得
- 钻石 = 不错
- 铂金 = 还行
- 黄金 = 一般
- 白银 = 有点失望
- 青铜 = 翻车，别去

**定级方式（手动为主）**

1. 新建打卡点时直接选段位（手动）
2. 系统根据历史到访算一个**建议段位**，详情页显示"建议：钻石 →"并可一键采纳
3. 一旦手动设置过，`rank_manual = 1`，系统不再主动建议，除非点"重算"

**建议段位计算规则**

```
若 到访次数 = 0 → 不给建议值（留空即可，不提示、不禁用）
平均分 = 所有到访 rating 的均值
  平均 ≥ 4.7 且 到访 ≥ 3  → 王者
  平均 ≥ 4.5              → 星耀
  平均 ≥ 4.0              → 钻石
  平均 ≥ 3.5              → 铂金
  平均 ≥ 3.0              → 黄金
  平均 ≥ 2.0              → 白银
  否则                     → 青铜
```

王者要求到访 ≥ 3 次，避免"只去过一次但很惊艳"就封顶 —— 但随时可手动改成王者。

> **「系统建议」不构成定级门槛（重要）**
> 段位是**我对这个点的推荐等级**，来源可以是听说的、朋友推荐的、网上评价好，不一定非要自己去过。
> **没去过的点照样能定到王者**。上面这套算法只在"有到访记录"时提供一个省事的起点；0 次到访时只是不给建议值，**不是不允许选**。任何"没去过所以只能给低段位 / 不能定级"的实现都是错的。

### 5.2 段位配色规范

沿用游戏段位金银铜体系，**刻意拉开相邻色相与明度**，因为地图标记上的环只有 1.6px 厚、刻度条只有 8px 高，色阶差得太细就分不出来：

| 段位 | 名称 | 主色 | 阶梯选择器里的字 |
|---|---|---|---|
| 0 | 倔强青铜 | `#7A5C42` | 青 |
| 1 | 秩序白银 | `#767E86` | 银 |
| 2 | 荣耀黄金 | `#A87A1E` | 金 |
| 3 | 尊贵铂金 | `#2E8A7A` | 铂 |
| 4 | 永恒钻石 | `#2A6FA8` | 钻 |
| 5 | 至尊星耀 | `#7A4FB8` | 星 |
| 6 | 最强王者 | `#C2452B` | 王 |

**为什么换掉旧的 `#A87B4E / #9AA3AD / #E3B13C / #6FBFB4 / #4FA8E0 / #B284E8 / #F08A3C`**

1. 旧黄金 `#E3B13C` 上压白字只有 **2.9:1** 对比度，读不出来。
2. 旧黄金与旧王者 `#F08A3C` 色相只差 **15°**，在小尺寸标记上就是同一块橙黄。
3. 旧铂金 `#6FBFB4` 与旧想打卡朱砂在冷暖上互抢，五六个点一起出现时像一盒糖果。

#### 段位的呈现方式：只走「7 段刻度条」

**段位一律用 7 段横条表达，不用色块、不用数字、不用单字。**

| 位置 | 尺寸 | 说明 |
|---|---|---|
| 地图标记 | 迷你条 30.4 × 8px | 挂在头像下方，读长度不数格子 |
| 卡片 / 列表右列 | 迷你条 30.4 × 8px | 在分数下方 |
| 详情数据条 | 通栏 7 段，4px 高 | 深色卡上的通栏刻度 |
| 编辑页阶梯选择器 | 选中格下方大号 40.2 × 9px | 见下 |

**刻度条规格（两套，不能混用）**

```
迷你  30.4 × 8px   步长 4.5px（填充 3.4 + 白缝 1.1）→ 满格 30.4
大号  40.2 × 9px   步长 5.9px（填充 4.8 + 白缝 1.1）→ 满格 40.2

填充宽度 = 等级序号 × 步长 − 1.1     （等级序号 0~6）
```

- 填充段 = 段位色；未填充段 = `#DCE0E6`；段间白缝 = `#FFFFFF`。
- 1 级约 3.4px，7 级约 30.4px，靠**长度差**读数，所以精度比装饰重要 —— 不要给刻度条加圆角模糊或渐变。

**被否掉的三种做法（不要重做）**

| 做法 | 为什么不行 |
|---|---|
| 数字角标（标 7） | 地图标记上的数字只有「数量」这一种常见语义，标 7 会被读成"去过 7 次" |
| 汉字首字（青/银/…） | 小尺寸下认不出，且与标签文字混在一起 |
| 尺寸梯度 / 环形分段刻度 | 小尺寸上人眼无法比较大小；段缝会把环切得像虚线，而虚线=未完成 |

**唯一例外**：编辑页的「段位阶梯选择器」用 7 个并排格子，格内写汉字首字。因为那是**选择动作**，7 格位置本身表达顺序，选中后下方立刻显示大号刻度条做确认。

**色值唯一来源是 `frontend/src/config/ranks.ts`**，任何地方不得硬编码。刻度条的填充宽度也必须由这个文件导出计算，不能各处手写 `width`。

### 5.3 地图标记：环表状态，条表等级

一个标记同时要回答两件事：**「我去没去过 / 想不想去」** 和 **「我给它打几级」**。这两件事用两套视觉分工表达，互不干扰。

**标记结构（白胶囊）**

```
┌───────────────────────────┐
│ [头像] 半野咖啡           │   ← 白底 13px 圆角胶囊 + 双层阴影
│        4.8 · 240m         │
│ ▁▁▁▁▁▁▁                  │   ← 7 段刻度条（等级）
└───────────────────────────┘
   ↖ 头像外一圈 1.6px 彩环（状态）
```

- 头像 30 × 30px，8px 圆角；胶囊 `5px 9px 5px 5px` 内边距。
- 名称 12px / 700，元信息 10px（已打卡显示「4.8 · 240m」，想打卡显示「想打卡 · 1.2km」）。

**环 = 状态（四种颜色）**

| 状态 | 环色 | 补充 |
|---|---|---|
| `visited` 已打卡 | `#111318` 近黑 | 默认样式 |
| `wishlist` 想打卡 | `#FF5A1E` 朱砂 | 全场唯一的"待办"强调色 |
| `unvisited` 未打卡 | `#4E86A8` 钢蓝 | 还没去过，中性；新建打卡点的默认状态 |
| `blacklist` 避雷 | `#C2C2C6` 浅灰 | 头像去色（`grayscale(1) brightness(.92)`）+ 白雾 10%，**且不显示刻度条** |

**避雷不要用中灰环 `#84838A`**：近黑环与中灰环在 1.6px 厚度下只差明度，看上去是一样的（试过一次，被否）。
**也不要靠「把已打卡改成墨绿」来区分状态**：绿色太跳，会抢掉想打卡朱砂的强调位，且与铂金 `#2E8A7A` 撞色相。

**环的实现**：`::after` 上 `inset:-3px` + `box-shadow: 0 0 0 1.5px #fff, 0 0 0 3px var(--ring)`，即 1.5px 白边 + 1.6px 彩环。
**环只有 1.6px 厚，所以它只能承载色相/明度的大幅跳变**，承载不了细腻的灰阶差 —— 这也是避雷必须改浅灰 + 去色、而不能只换个中灰的原因。

**条 = 等级（7 段刻度条）**，规格见 5.2。环是圆的、粗的、在外圈；条是方的、细的、在下沿 —— **形状分开，两套色就不会打架**。

**选中态**：胶囊描边转 `#FF5A1E` + 阴影加重（`0 4px 16px -4px rgba(255,90,30,.5)`），不做放大。

**双重编码**：颜色（状态）+ 刻度条长度（等级），色盲也能靠长度读出等级，小屏不用点开。

### 5.4 打卡点类型与关联

- `kind = place`：餐厅、店铺、场地
- `kind = person`：具体的服务者（技师、师傅、老板）
- 人物通过 `parent_spot_id` 挂靠到场所，详情页互相跳转

**为什么要分开**：你记"这家店"和"这家店里的王师傅"是两个不同的判断对象，段位也应该分开评。

### 5.5 联系方式展示

四渠道全部原样展示，点击即复制 / 跳转：

| 渠道 | 交互 |
|---|---|
| QQ | 复制号码 + 跳转 `wpa.qq.com` |
| 微信 | 复制微信号 + 提示"打开微信搜索添加" |
| Telegram | 跳转 `t.me/xxx`（境内可能打不开，显示降级提示） |
| 与你 | 复制 ID + 跳转 App |

**全部字段可空**，没有就不显示对应按钮，不留空占位。

### 5.6 标签

标签回答的是「它是什么、有什么特点」，与段位（推荐等级）、状态（去过没有）互不干扰，三者可以同时存在。

**建标签**

- 在编辑页输入框里直接敲名字回车即可新建，同名标签自动复用，不产生重复项（`uk_tag_name` 兜底）
- 标签名上限 24 字符，界面上按 8 个汉字建议；标签名里不写序号、不写颜色
- 每个打卡点最多挂 **8 个**标签。超过 8 个说明这个点的标签已经失去筛选价值，该合并了

**排序**

展示顺序 = `spot_tag.sort_order` 升序 → `tag.sort_order` 升序 → `tag.name` 升序。
`spot_tag.sort_order` 是「这一格先露谁的脸」，`tag.sort_order` 是全库默认次序，两者分开是为了以后能单独调整某个点的重点标签，而不影响别的点。

**列表页的溢出规则（重点）**

固定显示 N 个的做法是错的——标签长短不一，写死 3 个会让「店」「面」这类短标签白占一行空白，又让「可带电脑久坐」这种长标签被无谓截掉。正确做法是**按可用宽度实测**：

1. 单行不换行，容器 `overflow: hidden`
2. 单个标签最长 94px（约 9 个汉字），超出自己省略
3. 逐颗试放，放不下的整颗隐藏，末尾补一颗 `+N` 灰字，N = 被隐藏的数量
4. 宽度变化（旋转屏幕、字号变化）后重算一次

| 位置 | 规则 |
|---|---|
| 地图底部面板列表 | 单行 + `+N`，行高不随标签数量变化 |
| 狂人日志（到访记录） | 不显示标签，那里展示的是「这一次到访」，不是「这个点」 |
| 详情页 | 换行全量展示，最多 8 个，不再截断 |
| 地图上的照片卡 | 不显示，图上已经有名字和评分，再加标签会糊 |

**筛选**：点标签进入「这个标签下的所有打卡点」列表，按段位倒序。标签筛选与状态筛选可以叠加。

### 5.7 视觉规范（扫街榜明亮卡片风）

一句话：**冷灰底上是白色卡片，卡片里有大小反差和颜色分工，橙色只用来指路。**

#### 三条骨架（违反任何一条整套就散）

1. **冷灰底 + 白卡**：页面底 `#F2F4F6`，内容坐在白色卡片上，12px 圆角，双层低对比阴影。
2. **任何内容都不裸放在灰底上**：文字、数字、图表、列表项都在某张卡里。裸放正是上一版「像奶茶店清冷菜单」的根源 —— 只有线没有面，节奏又完全均匀。
3. **扫街榜橙 `#FF5A1E` 只给三处**：分数、当前选中、主 CTA。其余一律黑白灰。

#### 色板

| 令牌 | 值 | 用途 |
|---|---|---|
| `--bg` | `#F2F4F6` | 页面底色（冷灰，不用米色） |
| `--card` | `#FFFFFF` | 卡片底 |
| `--ink` | `#111318` | 主文字、主按钮底 |
| `--ink2` | `#5A6069` | 次文字、标签名 |
| `--ink3` | `#9AA1AB` | 说明文字、元信息 |
| `--line` | `#EDEFF2` | 卡内分隔线 |
| `--line2` | `#E2E5E9` | 描边（少用，优先用阴影分界） |
| `--hot` | `#FF5A1E` | 分数、选中、主 CTA |
| `--hotbg` | `#FFF2EB` | 橙色浅底（如「推荐」角标） |
| `--r` | `12px` | 卡片圆角 |
| `--sh` | `0 1px 2px rgba(17,24,39,.05), 0 8px 22px -16px rgba(17,24,39,.26)` | 双层低对比阴影 |

另有刻度条未填充 `#DCE0E6`、胶囊白缝 `#FFFFFF`、地图浅色底 `#E9EEEB`。

#### 地图底图：只用浅色，六色固定

做过多轮暗色方案（"深夜版"、"折中版"），结论是**暗色地图白天看不清**，不要重做。底图配色按下表来，六个值都要落到高德 `mapStyle` 的自定义样式里：

| 元素 | 色值 | 说明 |
|---|---|---|
| 底色 | `#E9EEEB` | 冷灰绿，不是纯白 |
| 道路 | `#FFFFFF` | 主干 14–16px、次干 11–13px |
| 道路描边 casing | `#DCE3E0` | 只给主干加，次干不加 |
| 绿地 | `#CFE5C0` | |
| 水域 | `#C8DEEE` | |
| 路名 | `#9BA3A8` | 10–11px，不加描边 |

**不要使用高德默认样式**：默认是米黄底 + 深灰路，与扫街榜的冷灰卡片底 `#F2F4F6` 完全不在一个体系里，叠上白胶囊标记会显脏。

#### 卡片什么时候该用、什么时候就是「AI 味」

判据三条，**全中才可以上卡**：

1. 承担**分组语义** —— 一张卡 = 一件事（不是"一行数据"）。
2. 卡内要有**量的差异** —— 大数字 / 彩色刻度条 / 图 / 通栏条。
3. 卡与卡的间距**随内容重要性变化**，不要全部 9px、12px、16px 均匀排。

反例：把列表项装进等距等大的卡里，卡内还是「左标签右数值」—— 那就是 AI 味，跟圆角多大无关。
12px 圆角本身不是问题，**没有反差才是问题**。

#### 字号与字重

| 用途 | 规格 |
|---|---|
| 页面大标题 | 30–32px / 800 / 负字距 `-.045em` |
| 章节标题 `.sl` | 14px / 700 / `-.02em`，右侧配 11px 说明 |
| 正文 | 13.5px / 500 |
| 说明 / 元信息 | 11–11.5px / 500 / `--ink3` |
| 大数字 | 24–38px / 800 / `-.045em` / `tabular-nums` |

**层级靠字重与负字距拉开，不靠加深颜色。** 字号不要做 13/14/15px 这种三级小跳，要么拉开到 2 倍关系，要么就不跳。

#### 底部面板三档

| 档位 | 高度 | 内容 |
|---|---|---|
| `peek` | 300px | 单卡摘要（92px 缩略图 + 名称 + 分数 + 刻度条 + 一句引语）+「查看完整列表」 |
| `half` | 492px | 完整列表（白卡行：左图右文，右列**分数在上、刻度条在下**） |
| `full` | 690px | 全屏列表 |

- 面板距底 66px（TabBar 高度），圆角 `16px 16px 0 0`，向上投影 `0 -10px 30px -22px`。
- 面板高度超过 `peek + 40` 时，FAB 与右侧工具按钮淡出，并加 `visibility:hidden` + `pointer-events:none`（只写 `opacity:0` 在截图里会残留）。

#### 表单控件

| 控件 | 规格 | 不要这么做 |
|---|---|---|
| 分段（场所/人物、状态） | 白胶囊 + 选中黑底白字 | 「灰底滑块」——底色等于页面底色时容器整个消失 |
| 标签 chip | 白底 + 细阴影，选中黑底白字 | 未选中留透明底，看不出可点 |
| 五分项评分 | 5 段 26 × 7px 圆角条 | 圆点（与段位形态混淆），竖柱图（四项分差只有 20%，柱高看不出差） |
| 输入行 | 白卡、54px 高，左标签 13px 灰 + 右值 13.5px 黑右对齐 | 黑框、下划线 |
| 文本域 | 白卡 | 浅灰底（在灰底页面上等于没有边界） |
| 主按钮 | 黑底白字 48px，或橙底（单页最多一个橙按钮） | 全站到处橙按钮，等于没有主次 |

#### 排版禁忌（这五样是「书卷气 / 萧条感」的来源，别再混进来）

1. 宋体或衬线体做大标题
2. 等宽字体做标签
3. `letter-spacing` 拉开的全大写小标签
4. 用发丝线（1px 浅线）当主要分隔手段
5. 米色 / 暖米灰纸底

#### 实现与验收

- **唯一原型**：`designs/打卡地图/扫街榜原型.html`（9 屏：口令门 / 地图 / 详情 / 到访录入 / 编辑 / 狂人日志 / 统计 / 我的）。改版在这份上原地迭代，不再造平行原型文件。
- 设计令牌集中在 `:root`，组件样式集中在一处 `<style>`，不要散落内联样式。
- 截图核对 `designs/打卡地图/_shots.py`；怀疑"线没渲染 / 对齐偏了"时用 `_probe.py` 量真实盒子与计算样式 —— 在缩略图上用肉眼判断 1px 线是否可见是不可靠的。

---

## 六、图片处理方案

> 目标：**手机随手拍的 4MB 原图，上传 250KB、落地即 250KB**，全屏查看仍然清晰。
> 前提约束：**服务器带宽小**，所有图片相关的设计都要优先考虑流量。

### 6.1 为什么用 JPEG 而不是 WebP

WebP 体积小 30%，但 Java 处理 WebP 必须引 `webp-imageio` 这类第三方库——更新慢、JDK 兼容有风险。**对 AI 维护的项目来说，一个不稳定的原生依赖就是定时炸弹。**

改用纯 Java 链路：`ImageIO`（JDK 自带）+ `Thumbnailator`（纯 Java，零原生依赖）。代价是体积比 WebP 大约 30%（三档合计约 325MB，个人规模完全无感），换来的是永远不会因为依赖问题卡住。

### 6.2 压缩分工：只压一次，不要压两遍

这是最容易做错的细节。如果前端压 q0.82、后端再压 q0.82，同一张图会经历**两次有损压缩**，画质损失是叠加的。

正确做法是让"前端输出质量"直接等于"large 的最终质量"，后端不再重压：

| 环节 | 职责 | 参数 | 说明 |
|---|---|---|---|
| 前端 | 尺寸归一 + **唯一一次**有损压缩 | 最长边 1280px，JPEG **q0.85** | 省上传流量；输出即最终 large |
| 后端 | 兜底 + 派生小图 | 见 6.3 | 不碰 large，只生成 thumb 与 avatar |

4MB 原图 → 上传约 250KB → large 落地即 250KB，**零二次压缩**。

### 6.3 处理链路

```
① 浏览器端（正常路径）
   选图 → EXIF 转正 → Canvas 缩到最长边 1280px
        → toBlob('image/jpeg', 0.85) → 上传
        4MB 原图 → 约 250KB

② 服务端（兜底 + 派生）
   ├─ 检查：尺寸 ≤1280 且 ≤2MB → large 直接原样存，不重压
   ├─ 否则（前端未压成功）      → 缩到 1280、q0.85 后存 large
   ├─ thumb  400px      q0.80   ← 从该文件派生（只压这一次）
   └─ avatar 96×96 裁切  q0.85   ← 同上
```

**为什么还需要后端兜底**：老浏览器不支持 `canvas.toBlob`、或你直接从文件管理器拖图绕过前端压缩时，后端必须能处理原始大图。兜底不是重复劳动，是防线路。

**为什么 thumb 和 avatar 必须后端生成**：这两个规格固定，让前端一次生成三份会卡主线程，尺寸标准也无法统一。

### 6.3 四个容易翻车的点

| 坑 | 处理 |
|---|---|
| **手机照片旋转 90°** | 必须按 EXIF `orientation` 修正后再画 canvas。用 `createImageBitmap(file, {imageOrientation:'from-image'})` 让浏览器自动处理 |
| **iPhone HEIC 格式** | iOS Safari 上传时通常已转 JPEG；从手机导出到桌面的 `.heic` 拖拽进来会失败。前端判断 MIME 类型，不支持的格式直接提示转换 |
| **超大图 iOS canvas 限制** | iOS Safari 对 canvas 面积有限制（约 16.7M 像素），超过返回空白。缩放前先判断，必要时分步降采样 |
| **服务端内存** | Thumbnailator 处理 1600px 图片内存占用可控，但容器需设 `-Xmx512m` 以上，否则并发上传可能 OOM |

### 6.4 CDN 与图片访问路径

计划接 CDN 做图片缓存，这直接决定图片地址怎么存。

**原则：数据库里永远只存相对路径，绝对地址在返回时拼接。**

| 情况 | `CDN_BASE_URL` | 字段值 | 返回给前端 |
|---|---|---|---|
| 本地开发（不接 CDN） | 空 | `/uploads/2026/10/x_l.jpg` | `/uploads/2026/10/x_l.jpg` |
| 线上（已接 CDN） | `https://images.sean.ltd` | `/uploads/2026/10/x_l.jpg` | `https://images.sean.ltd/uploads/2026/10/x_l.jpg` |
| 手填的绝对地址 | 任意 | `https://other.com/a.jpg` | 原样返回（以 http 开头则跳过拼接） |

三种情况都覆盖，前端完全不需要知道 CDN 存不存在。

**为什么不能把绝对路径写进数据库**：一旦存了 `https://images.sean.ltd/...`，换 CDN 服务商或换域名那天，全库图片一起废，还得分批 `UPDATE` 改数据。存相对路径的话，换 CDN 只改一个环境变量。

**拼接逻辑放后端**（Service 组装 DTO 时），前端拿到的是可直接使用的 URL。

**由此派生的三条前端约束**（前端本身不知道 CDN 存在，但有三处会被牵连）：

| 约束 | 原因 | 违反后的症状 |
|---|---|---|
| **前端不得自己拼 `/uploads/` 前缀**，一律用后端返回的完整字符串 | 拼了就绕过 CDN，全部回源打源站 | 线上慢一倍，且换 CDN 要改一堆前端代码 |
| **本地开发用 Vite proxy 转发 `/api` 和 `/uploads` 到 `localhost:8080`** | 本地 `CDN_BASE_URL` 置空 → 拿到相对路径 → 浏览器向 dev server 请求 | 图片全 404，但接口正常，很容易误判成后端问题 |
| **图片组件必须有 `onerror` 占位图** | CDN 配错或缓存穿透时是"静默失败" | 页面出现空白块，无任何报错，排查靠猜 |

唯一真正需要前端配合 CDN 的场景是**把图片画进 canvas**（做分享海报、导出长图时会用到 `drawImage` + `toDataURL`）。跨域图片会污染 canvas，导致导出失败，那时才需要在 CDN 上开 CORS 响应头。**当前不做这个功能，所以不需要开 CORS**——这一点提前记下，避免以后踩坑时误判。

**CDN 缓存策略**：图片文件名带 UUID 永不变化，可以设强缓存：

```
Cache-Control: public, max-age=31536000, immutable
```

回源路径与源站一致（都是 `/uploads/`），CDN 按目录配置缓存即可。

### 6.5 小带宽专项优化

服务器带宽小，图片流量必须省。四条措施：

| 措施 | 做法 | 效果 |
|---|---|---|
| **逐张上传** | 一次选 9 张时逐张上传，并发限 2 | 避免 4.5MB 一口气打满带宽导致超时；配进度条体验更好 |
| **分级加载** | 地图标记用 avatar(96)、列表用 thumb(400)、详情才加载 large(1280) | 首屏流量减少 80% 以上 |
| **懒加载** | 进入视口才请求，未加载时显示占位色块 | 避免滚动前把几十张图全拉下来 |
| **强缓存** | CDN + 浏览器长缓存（见 6.4） | 同一个点反复看不会重复回源 |

### 6.6 是否保留原图

**不保留。** 原图在手机相册里本来就有，系统里再存一份 4MB 纯属浪费磁盘和备份时间。`uploads/` 只存三档 JPEG。

### 6.7 存储目录与体积估算

```
uploads/
  └── YYYY/MM/<uuid>_l.jpg    large  1280px
  └── YYYY/MM/<uuid>_t.jpg    thumb  400px
  └── avatars/<uuid>_a.jpg    avatar 96px
```

按 300 个打卡点、每点 5 张照片估算：

| 规格 | 单张 | 数量 | 小计 |
|---|---|---|---|
| large 1280px q0.85 | ~180 KB | 1500 | ~270 MB |
| thumb 400px q0.80 | ~35 KB | 1500 | ~52 MB |
| avatar 96px | ~6 KB | 300 | ~2 MB |
| **合计** | | | **约 325 MB** |

### 6.8 上传限制

- 单文件上限：**前端 12MB / 服务端校验 15MB**（给未压缩的原图留余量）
- 单次最多 **9 张**，但**逐张上传、并发限 2**
- 服务端校验 MIME 类型 + magic number，不接受仅凭扩展名信任

---

## 七、技术方案

### 7.1 为什么是 Spring Boot + Vue

项目所有者**不直接写代码，后续主要由 AI 维护**。这个前提决定了选型标准不是"哪个技术更先进"，而是**哪个技术栈能自动挡住 AI 的疏漏**。

AI 维护最大的风险不是写不出代码，而是"改了 A 忘了 B"——改了接口忘了同步调用它的页面，改了字段名忘了改另一处引用。

| 判据 | Spring Boot 的表现 |
|---|---|
| 编译期护栏 | 类型不匹配、方法不存在、字段写错直接编译失败，AI 想错都过不去 |
| 结构约束 | Controller / Service / Mapper 各归其位，加功能有固定落点，半年后结构不烂 |
| 改动隔离 | 改后端只动 `.java`，改前端只动 `.vue`，影响面可预测 |
| 依赖稳定 | Spring Boot breaking change 极少，不像前端生态半年换一轮 |
| 可读性审查 | 所有者有 Java 背景，能看懂 Controller 在干什么 |

**为什么不用 Next.js**：它的核心价值是 SSR，但本项目有口令门、不做 SEO，**用不上 SSR**，却要承担 Server Component / Client Component 边界这类"编译不报错、运行时才炸"的复杂度——恰好是 AI 最容易犯且最难自查的错。

### 7.2 技术栈清单

| 层 | 选型 | 版本 |
|---|---|---|
| 后端框架 | Spring Boot | 3.2.x |
| JDK | Eclipse Temurin | 21 LTS |
| 构建 | Maven（单模块） | 3.9+ |
| ORM | MyBatis-Plus | 3.5.x |
| 数据库 | MySQL | 8.0（已有） |
| 缓存 | Redis | DB 3（已有） |
| 图片 | Thumbnailator | 0.4.20 |
| 前端 | Vue 3 + Vite + TypeScript | Vue 3.4+ / Vite 5+ |
| UI 组件 | Element Plus（全量引入） | 2.x |
| 地图 | 高德 JS API | 2.0 |
| 部署 | Docker + Nginx | 已有 |

**不引入 Spring Security**：单口令场景用 Security 配置过于复杂。改为一个 `AuthInterceptor` + Redis 会话，逻辑透明、AI 好改。

**Element Plus 必须换肤**（见 5.7）：原型是纯手写 CSS，没有组件库。EP 只用来兜住交互复杂、自己写容易出错的部件（日期选择、下拉、抽屉、消息提示），并用 CSS 变量覆盖它的主题色、圆角、阴影。**不要直接露出 EP 默认的蓝色主色、4px 圆角和默认阴影**——那会立刻把页面拉回"通用后台模板"的味道。列表、卡片、标记、刻度条、标签、分段控件这些视觉主体一律手写，不要用 `el-card` / `el-tag` / `el-row` 拼。

### 7.3 部署结构

> 本节按**服务器实际状态**重写（v0.9）。服务器上已有一台跑着五个站点的公用 Nginx（容器名 `nginx`），本应用是**纯增量接入**，不改动现有任何站点。

```
浏览器
  ├─ https://visit.sean.ltd ──→ Nginx ─┬─ /        → /var/www/visit（前端 dist）
  │                                    ├─ /api/    → host.docker.internal:8080
  │                                    └─ /uploads/→ host.docker.internal:8080
  │
  └─ https://images.sean.ltd ─→ 腾讯云 EdgeOne ─→ Nginx → /var/www/uploads
                                   （未命中才回源）        （Nginx 直出，不经 JVM）

spot-api 容器（唯一新增）
  /app/uploads ←── 挂载 ──→ /home/ubuntu/app/picture_bed/upload/uploads
                                  ↑ 同一份文件，由 images.sean.ltd 直接对外提供
  ├── MySQL 8.0（已有）
  └── Redis DB 3（已有）
```

**两条链路的分工**：`visit.sean.ltd` 提供页面与接口；`images.sean.ltd` 只提供图片，且图片**不经过 Java**——EdgeOne 回源到 Nginx，Nginx 从磁盘直接读文件。这是小带宽下最省的做法：JVM 线程池不必为图片占线。

**三个不能动的既有事实**（探测所得，动手前先读一遍）：

1. **`sean.conf` 里有一条 `server_name sean.ltd *.sean.ltd`**，通配符会接住任何子域名。因此 `visit.sean.ltd` 必须有自己**精确匹配**的 server 块，否则请求会被打到 `host.docker.internal:8445`（account 系统）。Nginx 优先级是「精确 > 通配符 > 正则」，加了就能抢回来，两个文件并存不冲突。
2. **容器只挂了 `conf.d/` 和 `ssl/`，没有挂 `conf/`**。所以 `conf/nginx.conf` 是宿主机上的一个副本，容器里用的是镜像自带的默认文件——那份文件里写的 `gzip on` **从未生效**（实测 `sean.ltd` 响应无 `Content-Encoding`）。连带 `client_max_body_size` 也是默认 1m。**不要去补挂载**（一改就是五个站点同时受影响），正确做法是在 visit 自己的 server 块里单独声明这两条。
3. **`images.sean.ltd` 的 `location` 里只有 `Access-Control-Allow-Origin`，没有任何缓存头**。图片由 Nginx 直出，后端设的 `Cache-Control` 到不了浏览器，要加得加在 Nginx 侧（收益见 7.7，不是红线）。**EdgeOne 控制台已核实的配置**：源站 `<服务器IP>`（IP，非域名，不会成环）、回源 HOST 头「使用加速域名 → `images.sean.ltd`」（这行决定了能否命中 server 块）、回源协议「协议跟随」——**这个默认值可以保留，不用改**（原因见 7.7 表格第 2 条）。

**关于 CDN：实际是腾讯云 EdgeOne（v0.9 探测更正）**

上一版曾判「`images.sean.ltd` 是裸 Nginx、PRD 里的 CDN 配置要作废」——**这个判断错了**，当时只看了 Nginx 配置没看 DNS。实测：

- `nslookup images.sean.ltd` → CNAME 指向 `images.sean.ltd.eo.dnse4.com`（EdgeOne 加速域名）
- 响应头带 `EO-Cache-Status`、`EO-LOG-UUID`，NEL 上报到 `nel.teo-rum.com`

所以 `CDN_BASE_URL=https://images.sean.ltd` 成立，它就是加速域名本身。

**回源落点也探明了**：请求 `https://images.sean.ltd/uploads/__nope__.jpg` 返回 404 / `Content-Length: 153` / `Server: nginx/1.31.6`，且**没有** `Content-Language: en`（那是 8445 应用的特征头）。说明 EdgeOne 回源直接打到 Nginx 的静态 location，**不经过 spot-api**。

这是最优路径，**不要改**：图片由 Nginx 读磁盘直出，JVM 线程池完全不参与。3 Mbps 下这比反代给 Spring Boot 快得多，也避免了并发头像把 Tomcat 的 20 个线程占满。

CDN 的真实作用（避免上线后误判）：

| 场景 | CDN 的作用 |
|---|---|
| 同一设备重复浏览 | **无作用**。浏览器缓存（immutable + 1 年）已经命中，请求根本不到 CDN |
| 换手机 / 清缓存后首次浏览 | **有作用**。边缘节点命中则不回源 |
| 地图首屏几十个头像并发 | **看命中率**。全部未命中时，边缘并发回源会打满 3 Mbps，比直连略慢（多一跳） |
| 源站带宽压力 | **有作用**。命中后完全不消耗源站带宽 |

所以一个反直觉但重要的事实：**接入 CDN 后，第一次访问可能比直连慢一点点，第二次以后才快**。如果上线后发现"刚发版那几天图片慢"，那是正常的冷缓存，不是配错了。

**EdgeOne 侧要核对的六条**（控制台配置，不在代码里）：

| # | 配项 | 要求 | 不这么配的后果 |
|---|---|---|---|
| 1 | 源站 | ✅ 已配 `<服务器IP>`（IP），回源 Host「使用加速域名」→ `images.sean.ltd` | 填域名会因 CNAME 成环而失败；Host 不对则命中不了 images 那个 server 块 |
| 2 | 回源协议 | ✅ 「协议跟随」**可以保留，不用改** | 实测：HTTP 回源到 80 会被 `sean.conf` 的 `return 301 https://$host$request_uri` 接住并返回 301，而 `$host` 取自回源 Host 头（已改写为 `images.sean.ltd`），所以 Location 是正确的 `https://images.sean.ltd/...`。EdgeOne 把这个 301 **原样透传给客户端**（响应头仍是 `Server: nginx/1.31.6`，没有 EdgeOne 自行跟随的迹象），由浏览器重新发起 HTTPS 请求，**不存在「EdgeOne 自己回源两次」**。再加上 HTTPS 页面引用 HTTP 图片会被 mixed-content 拦截，实际根本走不到这条路径 |
| 3 | 缓存过期 | ✅ 已配「**自定义时间 40 天**」+「强制缓存：**开启**」 | 见下方「缓存规则的三个推论」，其中一条是**404 会被缓存 40 天** |
| 4 | 缓存 key | 忽略查询参数 | 同一张图被存成多份 |
| 5 | 增值功能 | **关闭**「图片自动压缩 / 自动转 WebP」 | 在我们的 q0.85 之外又压一遍，画质白丢，与"只压一次"原则冲突 |
| 6 | 收录 | 返回 `X-Robots-Tag: noindex` | 图床被搜索引擎索引 |

**缓存规则的三个推论**（已按控制台实际配置「自定义 40 天 + 强制缓存开启」核实）：

1. **EdgeOne 不遵循源站的 `Cache-Control`**，「强制缓存」就是忽略源站缓存头的开关。所以 7.7 节第 ③ 步「给 `images.sean.ltd` 补缓存头」**对 EdgeOne 这一段毫无作用**，它的收益只剩浏览器那一段（省掉 `If-None-Match` 的条件请求往返）和本地直连的兜底。之前说的「给 EdgeOne 一个可遵循的 TTL」并不成立。
2. **40 天不是永久**。UUID 文件名永不覆盖，所以到期后 EdgeOne 回源验证一次、再续 40 天，对用户完全无感，不会出现"图片忽然变慢"。
3. ⚠️ **404 会被缓存 40 天，这是联调期最大的坑**。实测：

   ```
   /uploads/__probe_xxx.jpg  第1次 MISS → 第2次 HIT → 第3次 HIT   （404 被缓存）
   /uploads/__nope__.jpg 带非法 Referer  三次都是 403，不带 EO-Cache-Status   （403 不缓存）
   ```

   后果：如果后端 uploads 目录挂错（比如挂成 `picture_bed/upload` 而不是 `uploads` 子目录），**全站图片 404 会被 CDN 缓存 40 天**。等你把挂载改对，图片仍然是 404——因为 CDN 根本没去回源，看起来像"配置没生效"，会反复排查错方向。同理，任何用不存在路径做的探测都会污染缓存。

   **对策（三条一起做）**：
   - 联调阶段一律用真实存在的图片验证，**不要用不存在的路径试探**
   - 怀疑命中坏缓存时，在 URL 后加随机查询参数绕开：`?t=$(date +%s)`
   - 确认要清时，用控制台的「刷新缓存」按 URL/目录刷，**不要指望它自己过期**

**防盗链规则**（控制台「规则引擎」，2026-10-27 实测有效）：

当前生效两条：

| 规则 | 条件 | 动作 |
|---|---|---|
| 缓存 | `HOST 等于 images.sean.ltd` | 节点缓存 TTL：自定义 40 天，强制缓存开启 |
| 防盗链 | `HOST = images.sean.ltd` **且** `Referer 存在` **且** `Referer 正则不匹配 ^(!)(https?://([a-z0-9-]+\.)*(sean\.ltd\|servicewechat\.com)).*` | 返回 `403`，页面「图片防盗链拦截」 |

实测矩阵（2026-10-27，全部为真实请求）：

| Referer | 结果 | 判断 |
|---|---|---|
| 不带 Referer | 放行 | ✅ 空 Referer 放行，常规取舍（否则地址栏直接打开图片会 403） |
| `https://visit.sean.ltd/` | 放行 | ✅ |
| `https://4.sean.ltd/`、`https://a.b.sean.ltd/` | 放行 | ✅ 单级与多级子域都覆盖 |
| `http://visit.sean.ltd/` | 放行 | ✅ 协议不限 |
| `https://servicewechat.com/` | 放行 | ✅ 微信内置浏览器 |
| `https://notsean.ltd/` | **403** | ✅ |
| `https://attacker.com/?r=https://visit.sean.ltd/` | **403** | ✅ `^` 锚定生效，没被查询参数绕过 |
| `https://visit.sean.ltd.evil.com/` | **放行** | ⚠️ **可绕过** |
| `https://sean.ltd.cn.evil.com/` | **放行** | ⚠️ **可绕过** |

**漏洞：结尾的 `.*` 没有限定后接 `/`**，所以 `sean.ltd` 后面接什么都算白名单。攻击者只需在自己拥有的域名下建一个 `sean.ltd.cn.evil.com` 之类的子域，在那个页面上引用图片，Referer 就能过关。

**修复：把结尾的 `.*` 换成 `(/|$)`，其余不动**（`(!)` 这个写法实测有效，不必改动，避免引入新风险）：

```
^(!)(https?://([a-z0-9-]+\.)*(sean\.ltd|servicewechat\.com))(/|$)
```

注意别加 `$` 到整串末尾——Referer 后面还有路径，`(/|$)` 的意思是「主机名之后必须是 `/` 或到此为止」，这是卡住绕过点的关键。

**这条规则与 PRD 既有设计的一致性**：5.5 写着「`/uploads/**` 不做口令校验，靠 UUID 文件名不可枚举兜底」。防盗链是在这之上再加的一层，但**不改变原结论**——不带 Referer 仍可直取，所以真正的兜底依然只有 UUID 文件名。防盗链拦的是"别人的网页盗用你的图"，不是"防人拿到图"。

**上线后的验证手势**：

```bash
# 连跑两次：第一次 MISS，第二次应为 HIT
curl -sI https://images.sean.ltd/uploads/<真实文件名>.jpg | grep -i eo-cache-status
```

一直 MISS 说明缓存规则没生效。判断时**别看 `Cache-Control`**——「强制缓存」已开启，源站返回 `no-cache` 也会被忽略，所以那个头在 EdgeOne 这一段不参与决策。

⚠️ **必须用真实存在的图片来测**。用不存在的文件名试，会得到一个被缓存 40 天的 404；之后即使图片传上去了，同一个 URL 仍然返回 404，而且 `EO-Cache-Status` 会显示 `HIT`，很容易误判成「图片上传失败」。

**`/api/` 天然安全**：EdgeOne 只加速 `images.sean.ltd`，这个域名下不存在接口，所以「接口数据被缓存串数据」「口令门 401 被缓存」两条风险不成立。哪天真给 `visit.sean.ltd` 也上加速，再回来处理这两条。

**`/uploads/` 为什么还留一条反代到后端**：线上图片走 `images.sean.ltd` 由 Nginx 直出，`visit.sean.ltd/uploads/` 只服务两件事——① 本地开发（`CDN_BASE_URL` 置空时前端请求同域相对路径）；② EdgeOne 配错时的兜底直连。保留成本极低，删了本地就起不来。

**一个必须提前决定的安全取舍**：`/uploads/**` **不做口令校验**（可公开访问）。EdgeOne 回源时不带用户 Cookie，一旦加了校验，所有图片回源都是 401 —— 图片全挂。代价是拿到图片 URL 的人无需口令就能看图。图片既然走 `images.sean.ltd` 由 Nginx 直出（连后端都不经过），这条实际是「必须不做」，没有商量空间。

这个取舍可以接受，理由：文件名是 UUID（128 位随机，不可枚举），图片本身是你去过的场所照片，不含证件类内容。若以后要放敏感图，再加一层「回源令牌」——CDN 回源带自定义 Header，源站校验该 Header，浏览器直连则 403。这个方案现在不做，因为多一处可能配错的地方，配错了就是所有图片全挂，收益不抵风险。

### 7.3.1 资源约束（按实测数据定参）

2026-10-26 实测服务器状态（部署本应用**之前**，仅 MySQL + Redis + Nginx + 系统）：

| 项 | 实测值 | 判定 |
|---|---|---|
| CPU | 4 核，占用 1.2% | 完全空闲，不是瓶颈 |
| 内存 | 4 GB，已用 2044 MB（50%） | **可用约 1.9 GB**，够，但需要设上限 |
| 系统盘 | 40 GB，已用 12.2 GB（30.5%） | 可用 27.8 GB，图片占用无压力 |
| 流量包 | 300 GB/月，已用 729.5 MB（0.24%） | **用不完**，详见下方测算 |

**流量测算**：流量包重置时间 2026-11-17，说明计费周期约 10-18 起，至今 8 天用掉 729.5 MB → 日均约 86 MB → 月均约 2.6 GB。剩余约 297 GB，按 large 图 180 KB 计，相当于**每月 160 万次大图浏览**。**流量配额不是约束，任何缩图决策都不需要以省流量为理由。**

**带宽已确认为 3 Mbps（≈384 KB/s）**，结论是**不需要为带宽牺牲图片质量**：

| 资源 | 大小 | 3 Mbps 下耗时 |
|---|---|---|
| large 1280px | 180 KB | 约 0.47 s |
| thumb 400px | 40 KB | 约 0.10 s |
| avatar 96px | 6 KB | 约 0.02 s |
| 首屏 JS（gzip 后） | 110 KB | 约 0.29 s |
| 地图首屏 30 个头像并发 | 180 KB | 约 0.47 s |

首屏完整加载约 1 秒出头，属于"无感"区间。**因此 large 保持 1280px，不降级到 1080px**——上一版留的"若低于 1 Mbps 则降级"分支作废。

3 Mbps 下唯一需要注意的是**并发**：带宽是共享的，同时下载 10 张 large 图（1.8 MB）就是 4.7 秒。所以懒加载和"只加载视口内图片"仍然必须做，这不是省流量，是**把有限带宽用在看得见的地方**。

按 4 GB 内存重新定参（原方案按 2 GB 假设写的 384m，实测后可放宽）：

| 项 | 建议值 | 说明 |
|---|---|---|
| JVM 堆 | `-Xmx512m -Xms256m` | 实测空出 1.9 GB，512m 堆有充足余量，不必压到 384m 牺牲性能 |
| 元空间 | `-XX:MaxMetaspaceSize=192m` | 不给上限时元空间会缓慢增长直到触及容器上限 |
| 直接内存 | `-XX:MaxDirectMemorySize=64m` | 文件上传用得到，给个明确天花板 |
| GC | `-XX:+UseSerialGC` | 堆小于 1 GB 时 SerialGC 的总开销低于 G1，不需要并发回收线程 |
| OOM 行为 | `-XX:+ExitOnOutOfMemoryError` | 直接退出让 Docker 拉起，好过僵死占着内存不响应 |
| 容器内存上限 | `mem_limit: 1g` | **关键**：给容器设硬上限，进程泄漏时被 OOM-kill 的是它自己，不会把 MySQL 一起带走 |
| 连接池 | HikariCP `maximum-pool-size: 5` | 个人项目不需要 10 个连接，多了白占 MySQL 资源 |
| Tomcat 线程 | `server.tomcat.threads.max: 20` | 默认 200 是给高并发准备的，小机器上纯占内存 |

**预估落地后内存水位**：应用 RSS 约 800 MB（堆 512 + 元空间约 120 + 线程栈与直接内存约 100 + 其他），总量从 2044 MB 升到约 2.8 GB / 4 GB（70%）。安全，但**没有富余到可以再加一个 JVM 应用**，后续如果还要部署别的东西，得先清理或升配。

**磁盘与容器日志**：本应用增量约 1 GB（JDK 基础镜像 + jar + 图片）。但要注意 Docker 日志默认无上限，一天几十次请求虽小，长期跑下来容器的 `json.log` 可能悄悄吃几个 G。所有容器统一加日志轮转：
```yaml
logging:
  driver: json-file
  options: { max-size: "10m", max-file: "3" }
```

### 7.4 后端分层

```
controller/   AuthController  SpotController  VisitController  UploadController
service/      SpotService  VisitService  UploadService  RankService
mapper/       SpotMapper  VisitMapper（继承 BaseMapper）
entity/       Spot  Visit
dto/          请求/响应对象，禁止把 Entity 直接返回给前端
common/       ApiResult  BizException  GlobalExceptionHandler
config/       WebMvcConfig（静态映射 + 拦截器注册）  RedisConfig  MybatisPlusConfig
interceptor/  AuthInterceptor（校验会话，未登录返回 401）
```

### 7.5 环境变量

| 变量 | 说明 | 必填 |
|---|---|---|
| `ACCESS_PASSWORD` | 访问口令 | 是 |
| `MYSQL_HOST` / `MYSQL_PORT` | MySQL 地址 | 是 |
| `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_DATABASE` | 数据库账号与库名 | 是 |
| `REDIS_URL` | `redis://redis:6379/3` | 是 |
| `AMAP_KEY` | 高德 JS API Key | 是 |
| `AMAP_SECURITY_CODE` | 高德安全密钥 | 是 |
| `BASE_URL` | 外部访问地址，**可空**，空则从 `X-Forwarded-Host` 推导 | 否 |
| `CDN_BASE_URL` | 图片 CDN 前缀，**默认 `https://images.sean.ltd`**；置空则返回相对路径（同域直连，用于本地开发） | 否 |
| `UPLOAD_DIR` / `UPLOAD_MAX_MB` | 图片目录 / 单文件上限 | 否 |

> JDK 版本由 Docker 镜像决定，**与服务器上装了哪个 JDK 无关**。

### 7.6 域名可配置设计

你提到后期可能换域名或直接用 IP 访问。只加一个 `BASE_URL` 环境变量是不够的，真正决定"换域名痛不痛"的是这三条：

| 设计 | 做法 | 不这么做的后果 |
|---|---|---|
| **数据只存相对路径** | 图片字段存 `/uploads/...`，绝不存完整 URL | 存了绝对地址，换域名全库图片一起挂 |
| **前端请求走相对路径** | 所有请求打 `/api/...`，不写死域名 | `VITE_` 变量是构建时注入的，写死则换域名必须重新构建镜像 |
| **Cookie 不设 domain** | 默认绑定当前 host | 设了 domain，用 IP 访问时登录态失效 |

**`BASE_URL` 的处理**：仅在生成绝对链接的少数地方使用（如导出文件里的引用），**未配置时自动从请求头 `X-Forwarded-Host` + `X-Forwarded-Proto` 推导**。这样切域名、切 IP 都不用改任何配置。

**唯一需要手动同步的是高德 Key 的 referer 白名单**——换域名后要把新域名加进去，否则地图白屏。

### 7.6.1 构建与发布策略（v0.12 改案）

**核心决策：镜像在 GitHub Actions 的 runner 上构建，推送到 GHCR；服务器只 `pull` 和 `up`，本地也不构建。**

v0.11 曾把编译放在本地（`docker save | ssh load`），动机是服务器内存不够。**这条理由依然成立，只是解法换了**——4 GB 已用 2044 MB，Maven 构建会临时吃掉 1～1.5 GB，OOM 时被杀的不一定是 Maven，可能是同机的数据库。既然机器不能编译，就让别人的机器编译：GitHub 公共仓库的 runner 免费，`mvn package` 和镜像构建全在云端完成，产物推到 `ghcr.io`，服务器只拉一层已经编译好的镜像。

这个改案顺带消掉了本地方案的三个毛病：Windows 构建、Linux 运行，镜像要 `gzip` 后手工 `scp`/管道传输；镜像约 250 MB，家里上行带宽传一次要好几分钟；谁在哪台机器上构建的无法追溯，回滚只能靠本地还留着旧 tar。

**链路**

```
push backend/**  →  GitHub Actions (ubuntu-latest)  →  mvn package  →  镜像  →  ghcr.io/seanpoke/spot
                                                                                    ↓ docker compose pull
                                                                              服务器 up -d
```

**仓库里已落地的部署文件**：

| 文件 | 作用 | 什么时候用 |
|---|---|---|
| `deploy/docker-compose.dev.yml` | 本地 MySQL 8.0 + Redis 7，首次启动自动执行 `db/schema.sql` | 日常开发 |
| `deploy/Dockerfile` | **多阶段**：`maven:3.9-eclipse-temurin-21` 编译 → `eclipse-temurin:21-jre-alpine` 运行；非 root，内存参数写死在 `ENV` | CI 构建时 |
| `deploy/docker-compose.yml` | 线上只有 `spot-api` 一个服务，`image: ghcr.io/seanpoke/spot:latest`，`mem_limit 1g` | 发布后端 |
| `deploy/.env.example` | 后端环境变量模板，复制成 `.env` 后填值 | 首次部署 |
| `.github/workflows/docker-publish.yml` | 构建与推送。`push` 到 `main` 且改动 `backend/**` 触发，也可 `workflow_dispatch` 手工重发；打 `latest` + `sha-<短哈希>` + 分支名三个标签；启用 `type=gha` 缓存 | 自动 |
| `deploy/nginx/visit.conf` | 拷到服务器 `conf.d/`，不参与构建 | 首次接入 |

`.dockerignore` 的语义随构建位置一起反转：过去是「只把 `backend/target` 里的 jar 送给 daemon」，现在是「**必须放行 `backend/pom.xml` 与 `backend/src`**，否则 `COPY` 那一步就失败」；`designs`（15 MB）、`frontend`、`backend/target` 仍要挡住。

**为什么 `paths` 只写 `backend/**`**：把 `deploy/Dockerfile`、`.dockerignore`、workflow 自身也放进去，会导致「改一句注释就重发一个镜像」。这三样要重建时走 `workflow_dispatch`。

**三个标签的分工**：`latest` 跟随 main，`up -d` 默认拉它；`sha-<短哈希>` 不可变，出事时把 compose 的 `image:` 临时钉到某次构建即可回滚；分支名标签只作参考。

**GHCR 的两个实操坑**

| 坑 | 现象 | 处理 |
|---|---|---|
| **包默认 private** | 即使仓库是 public，`docker pull ghcr.io/...` 也报 `unauthorized` | GitHub → Packages → 该包 → Package settings → Change visibility 改 Public；或服务器 `docker login ghcr.io -u seanpoke`（PAT 需 `read:packages`） |
| **国内拉取慢** | 拉 `ghcr.io` 只有几十 KB/s，`pull` 卡几分钟 | 兜底是「Actions 同时推到腾讯云 TCR / 阿里云 ACR」，服务器改拉国内仓库。**不要改回 `save/load`**，那等于把服务器内存问题重新捡回来 |

**三种变更的发布路径**（不要混用）：

| 改了什么 | 要做什么 | 耗时量级 |
|---|---|---|
| 只改前端 | `npm run build` → `rsync --delete` 到 `/home/ubuntu/app/visit/dist/` | 十秒，**不重建后端、不重启容器** |
| 改了 Java | push 到 main → 等 Actions 变绿 → 服务器 `pull` + `up -d` | 构建 1～3 分钟，拉镜像看网络 |
| 改了 `Dockerfile` / 依赖版本 | Actions 页 → Docker Publish → Run workflow → 同样 `pull` + `up -d` | 同上 |
| 改了表结构 | 手动执行 SQL，**个人项目不引 Flyway** | 秒级 |

**镜像与容器的名字跟仓库走（`spot-api`），域名和服务器目录跟 `visit` 走**——这个分裂是有意的：域名 `visit.sean.ltd` 已经配好 DNS 与证书，改它要动 EdgeOne、Nginx、高德 referer 三处；容器名只是内部标识，跟着仓库名更好认。**`spot.sean.ltd` 目前公网无解析**，不要以为它可以用。

### 7.7 Nginx 接入方案（v0.9 按实际配置定稿）

服务器上 `/home/ubuntu/app/nginx/` 是一台跑着五个站点的公用 Nginx。本应用**纯增量接入，`sean.conf` 一个字都不改**，只做四件事。

**① 宿主机建目录 + compose 加一行挂载**

```yaml
volumes:
  - ./conf.d:/etc/nginx/conf.d
  - ./ssl:/etc/nginx/ssl
  - /home/ubuntu/app/picture_bed/upload:/var/www/uploads:ro
  - /home/ubuntu/app/visit/dist:/var/www/visit:ro        # 新增：前端产物
```

改完必须 `docker compose up -d`（**不是 `restart`**，volume 变更要重建容器，中断几秒）。

**② 新建 `conf.d/visit.conf`**（完整内容见 `deploy/nginx/visit.conf`）

| 要点 | 写法 | 为什么 |
|---|---|---|
| server_name | `visit.sean.ltd`（精确匹配） | 优先级高于 `sean.conf` 的 `*.sean.ltd`；不写就会被打到 8445 |
| SSL / 代理头 | `include /etc/nginx/conf.d/proxy_common.conf;` | 该文件只有 SSL 与 proxy 参数、**没有 location 块**，可安全 include。**含了它就绝不能再写 `ssl_certificate`**，否则 duplicate 报错起不来 |
| 反代地址 | `http://host.docker.internal:8080` | 与现有五个站点一致。`spot-api` 是独立 compose project、不同 network，写 `spot-api:8080` 会 DNS 失败 502 |
| 上传体积 | `client_max_body_size 20m;` | 写在 server 块。**不要动公共 `conf/nginx.conf`**（它根本没挂载，见 7.3） |
| Gzip | `gzip on` + `gzip_types` | 同上。公共那份的 `gzip on` 从未生效 |
| 前端路由 | `try_files $uri $uri/ /index.html` | Vue Router history 模式必须 |
| `index.html` | `Cache-Control: no-cache` | 缓存了就拿旧 index 引已删的 hash 文件 → 白屏 |
| `/assets/` | `immutable` + 1 年 | Vite 产物带 hash，永不覆盖 |

**③ 给 `images.sean.ltd` 的 location 补缓存头**

```nginx
location / {
    root /var/www/uploads;
    add_header Access-Control-Allow-Origin *;
    add_header Cache-Control "public, max-age=31536000, immutable";   # 新增
    try_files $uri $uri/ =404;
}
```

**这条的收益要说准，别夸大**（v0.9 修正）：EdgeOne 命中时本来就不回源、不消耗源站带宽，所以它不是"不加就每次重新下载"。真实的收益只有两点：

1. **省掉浏览器的条件请求往返**。Nginx 默认带 `ETag` / `Last-Modified`，浏览器即使没有 `Cache-Control` 也会发 `If-None-Match` 问一次，304 不传响应体但要一次 RTT（4G 下 100–300ms）。首屏几十张头像就是几十次排队。加 `immutable` 后一年内连问都不问
2. **给 EdgeOne 一个明确的 TTL**。如果 EdgeOne 缓存规则设的是「遵循源站」，而源站不返回 `Cache-Control`，就没有 TTL 可遵循，可能完全不缓存 —— 那种情况下才会退化成每次回源

所以它是「加了有收益，不加也不坏」，不是硬性红线。判断依据见下面的验证手势。

**验证手法**（需要一张真实存在的图片）：

```bash
# 连跑两次，看三个头
curl -sI https://images.sean.ltd/<真实文件名>.jpg | grep -i 'eo-cache-status\|cache-control\|age'
```

`EO-Cache-Status: HIT` 说明 EdgeOne 已缓存住；`Cache-Control` 有值说明源站带了头；`Age` 递增说明已缓存了多久。**注意别拿 404 / 403 的响应去判断** —— 那两个本来就不进缓存，永远是 MISS。

**④ 80 → 443 不用另写**

`sean.conf` 里 `server_name sean.ltd *.sean.ltd` 那条会接住 `visit.sean.ltd` 的 http 请求，用 `$host` 保留域名跳转，正好合用。

**发布流程**

```bash
cd frontend && npm run build
rsync -av --delete frontend/dist/ ubuntu@<server>:/home/ubuntu/app/visit/dist/
```

只改前端时后端不用重建、容器不用重启——Nginx 读的是磁盘文件。`--delete` 必须加，否则旧 hash 文件越积越多。

**图片目录的唯一对应关系**（弄错就是全站 404，且很难查）：

```
images.sean.ltd 的 root = /var/www/uploads = 宿主机 /home/ubuntu/app/picture_bed/upload
所以 /uploads/abc.jpg → 文件 /home/ubuntu/app/picture_bed/upload/uploads/abc.jpg
所以后端 uploads 必须挂到 picture_bed/upload/uploads（子目录），不能挂 picture_bed/upload
```

**图片缓存头在两处**：Nginx 侧（上面第 ③）保证源站响应带 `immutable`；Spring Boot 静态映射也照旧给 `/uploads/**` 加同样的头（本地开发与兜底直连时才用得上）。两处用同一个值，不要一边 immutable 一边 no-cache。

**要说清这条头的真实作用范围**：EdgeOne 开了「强制缓存 40 天」，不读源站的 `Cache-Control`，所以它**管不到 CDN 那一段**。它真正管的是——① 浏览器与 CDN 之间那段（省掉条件请求往返）；② 本地开发和 EdgeOne 配错时的直连兜底。属于「顺手加上、不加也不坏」，别当成必须项，也别指望它能影响 CDN 的缓存时长。

### 7.8 Redis 用途与容错

| 用途 | Key 设计 | 说明 |
|---|---|---|
| 会话 | `visit:sess:<token>` | SET + 30 天过期，可主动撤销 |
| 口令限流 | `visit:pw:<ip>` | INCR + 300s 过期，超 5 次拒绝 15 分钟 |
| 口令版本号 | `visit:pwdver` | 改口令时 +1，所有旧会话立即失效 |

**容错铁律：Redis 不可用时返回 503，绝不静默放行。** 看起来"友好"的降级，在这个场景等于把私有数据敞开给所有人。

**注意**：若 Redis 未开持久化，容器重启会掉会话，需要重新输一次口令。单用户可接受；不想每次重启都登录就给 Redis 开 AOF。

### 7.9 访问控制

- 单一环境变量 `ACCESS_PASSWORD`，BCrypt 比对
- 验证通过生成随机 token → Redis 存会话 → 写 HttpOnly + Secure + SameSite=Lax 的 Cookie
- `AuthInterceptor` 拦所有 `/api/**`，仅 `/api/auth/login` 放行
- **`/uploads/**` 不拦截**（CDN 回源无 Cookie，拦了图片全挂）。依赖 UUID 文件名不可枚举兜底，见 7.3 的安全取舍说明
- 全站 `robots.txt` 禁收录 + `X-Robots-Tag: noindex`
- 改口令时 `visit:pwdver` +1，token 中带版本号，版本不匹配即失效
- ⚠️ **高德安全密钥必须把 `visit.sean.ltd` 加入 referer 白名单**，否则本地能跑、线上白屏

### 7.10 地理查询策略

**坚持不做空间索引。** 全量拉取 `(id, lat, lng, rank_level, kind, status, name, avatar)` 轻量字段（一两百条约几十 KB），前端按当前视野过滤。这个规模下比任何后端查询都快，MySQL 的空间类型在此是纯负债。

### 7.11 备份方案

每日 crontab：

```bash
docker exec <mysql容器> mysqldump -u visit -p visit_db > /backup/visit_$(date +%F).sql
tar -czf /backup/uploads_$(date +%F).tar.gz /path/to/uploads
```

保留 30 天，定期手动导出一份 JSON 存本地。

---

## 八、风险与合规

| 风险 | 应对 |
|---|---|
| **数据丢失** | 每日 mysqldump + uploads 打包，异地留存一份；应用层 CSV 导出兜底 |
| **地图 Key 白名单漏配** | 上线第一步验证地图；本地与线上域名都加入白名单 |
| **换域名后地图白屏** | 高德 referer 白名单同步更新（代码无需改动，见 7.6） |
| **联系方式被爬** | 口令门挡爬虫；`robots.txt` + `noindex`；不公开贴完整 URL |
| **口令泄露** | Redis 会话可一键失效；改口令 `pwdver +1` 强制全端重新验证 |
| **Redis 不可用** | 明确返回 503，不降级放行 |
| **磁盘增长** | 压缩 + 不存原图；V1.5 可加按年限归档 |
| **AI 改动引入结构混乱** | 维护 `AGENTS.md`，每次改动前先读约定 |
| **人物肖像** | 录入他人头像仅限自己留存，不对外发布 |

---

## 九、V1 开发任务拆解

| # | 任务 | 产出 | 前置 |
|---|---|---|---|
| 1 | 后端骨架：Spring Boot 3 + Maven，连 MySQL 与 Redis，健康检查接口 | `/api/health` 返回两个服务状态 | — |
| 2 | 执行 `db/schema.sql` 建库建表 | 四张表就绪（spot / visit / tag / spot_tag） | — |
| 3 | 口令门：登录接口 + Redis 会话 + 限流 + `AuthInterceptor` | 未登录访问 API 返回 401 | 1 |
| 4 | 图片管道：`UploadController` + `ImageUtil`（Thumbnailator 派生三档）+ 前端 canvas 压缩 | 上传返回三档相对路径 | 1 |
| 5 | spot CRUD：Entity / Mapper / DTO / Service / Controller + 前端编辑页 | 能创建并保存一个打卡点 | 2, 4 |
| 6 | 地图主页：高德接入 + 视野过滤 + **「环表状态 + 条表等级」的白胶囊标记** + 三档面板 | 地图上看到带状态环和段位条的标记 | 2, 3, 5 |
| 7 | 详情页：封面、段位数据卡、标签、相册、价目、联系方式 | 详情完整可读 | 5 |
| 8 | 到访记录：录入表单（四项分项可留空）+ 狂人日志 | 能记一次到访并回看 | 5 |
| 9 | 段位：`RankService` 建议值计算 + 手动定级 + 一键采纳 | 详情页出现"建议：X →" | 8 |
| 10 | 导出：JSON / CSV | 设置页可下载全量数据 | 2 |

> **前端任务（5~8）开工前必读 PRD 5.7 与 `designs/打卡地图/扫街榜原型.html`。** 原型是视觉基线，不是"参考图"——颜色、圆角、字号、间距都按它来，不要自己发挥。

**验收标准（第 10 步完成后）**：手机浏览器打开 `https://visit.sean.ltd`，输口令进入 → 地图看当前城市 → 新建一个点（拍照上传、填价目、选地点）→ 记录一次到访（星级 + 四项分项，至少留空一项）→ 地图出现带状态环和段位刻度条的标记 → 详情页可回看 → 设置页导出 JSON。

---

## 十、配套文档

| 文件 | 用途 |
|---|---|
| `AGENTS.md` | **给 AI 看的项目说明书**，记录目录结构、分层约定、硬性规则、常见改动指引。每次让 AI 改代码前先让它读这个 |
| `db/schema.sql` | 建库建表脚本，可重复执行，全部带注释 |
| 本文件 | 产品与方案基线，改动需求先更新这里再动代码 |

**`AGENTS.md` 的重要性**：不写这份文件，每次新开对话 AI 都要重新摸索项目，然后按自己的习惯放代码，三个月后结构就散了。它的维护质量直接决定这个项目能不能长期由 AI 接手。

---

## 十一、待确认项

**无阻塞项，可以直接开工。** 全部前置信息已对齐：

- 服务器：4 核 / 4 GB 内存 / 40 GB 盘 / 300 GB 月流量 / **3 Mbps 带宽**
- CDN：`https://images.sean.ltd`，实测为**腾讯云 EdgeOne**（CNAME `eo.dnse4.com`），回源直落 Nginx 静态直出
- MySQL 8.0（已有）、Redis DB 3（已有）
- Nginx：五站点共用的公用实例，落在 `/home/ubuntu/app/nginx/`，接入方案见 7.7，配置文件已备在 `deploy/nginx/visit.conf`

剩下的都是**上线时要落地的配置清单**，开发期间不用管：EdgeOne 六条见 7.3，Nginx 四步见 7.7（其中 `visit.conf` 已写好，只需加一行挂载 + 补 `images.sean.ltd` 的缓存头）。

---

*本文档为 v0.11，前置信息全部对齐，视觉规范、部署接入与工程骨架均已定稿，可直接开工。后续需求变更请先更新本文档，再动代码。*
