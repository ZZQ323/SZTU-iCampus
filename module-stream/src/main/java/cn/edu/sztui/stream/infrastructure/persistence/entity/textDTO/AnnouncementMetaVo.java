package cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公告元数据（列表项）
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/persistence/entity/textDTO/AnnouncementMetaVo.java
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementMetaVo {

    /** 公告 ID */
    private String announcementId;

    /** 标题 */
    private String title;

    /** 发布日期 (yyyy-MM-dd) */
    private String publishDate;

    /** 发布部门 */
    private String department;

    /** 分类代码 */
    private String categoryCode;

    /** 分类名称 */
    private String categoryName;

    /** 详情页 URL */
    private String detailUrl;

    /** 是否有附件 */
    private Boolean hasAttachment;

    /** 是否置顶 */
    private Boolean isTop;

    /** 是否已读 */
    private Boolean isRead;

    // ==================== 便捷方法 ====================

    /**
     * 获取 ID（兼容方法）
     */
    public String getId() {
        return this.announcementId;
    }

    /**
     * 设置 ID（兼容方法）
     */
    public void setId(String id) {
        this.announcementId = id;
    }
}
