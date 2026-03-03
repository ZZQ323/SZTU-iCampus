package cn.edu.sztui.base.application.vo;

import lombok.Data;

/**
 * 公告元数据
 * <p>
 * 存储在 Redis Hash 中，用于列表展示
 */

@Data
public class AnnouncementMetaVo {

    /** 文章ID，全局唯一 */
    private String id;

    /** 文章ID，全局唯一 */
    // private String author;

    /** 相对路径，如 info/1018/50731.htm */
    private String url;

    /** 发文类别代码：1018/1019/1020/1021/1022 */
    private String category;

    /** 发文类别名称：教务/科研/行政/学工/校园 */
    private String categoryName;

    /** 发文单位/部门 */
    private String department;

    /** 公文标题 */
    private String title;

    /** 发文日期，格式 yyyy-MM-dd */
    private String publishDate;

    /** 爬取时间戳 */
    private Long crawledAt;
}
