package cn.edu.sztui.base.infrastructure.persistence.entity.textDTO;

import lombok.Data;

import java.util.List;

/**
 * 公告列表响应
 */
@Data
public class AnnouncementListVo {

    /** 公告列表 */
    private List<AnnouncementMetaVo> list;

    /** 当前最新ID（用于前端比对未读） */
    private String latestId;

    /** 总数 */
    private Long total;

    /** 是否有更多 */
    private Boolean hasMore;
}







